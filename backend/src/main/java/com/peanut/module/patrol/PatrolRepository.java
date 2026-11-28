package com.peanut.module.patrol;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PatrolRepository {
    private static final String TASKS = """
            FROM patrol_task t JOIN field f ON f.id = t.field_id
            WHERE t.status <> 'deleted'
            """;
    private static final String FLIGHTS = """
            FROM patrol_flight p JOIN patrol_task t ON t.id = p.task_id JOIN field f ON f.id = p.field_id
            WHERE true
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PatrolRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Page<PatrolTask> tasks(Long fieldId, Pageable page) {
        return page("t.*, f.name AS field_name", TASKS, "t", fieldId, page, this::taskRow);
    }

    Page<PatrolFlight> flights(Long fieldId, Pageable page) {
        return page("p.*, t.task_name, f.name AS field_name", FLIGHTS, "p", fieldId, page, this::flightRow);
    }

    private <T> Page<T> page(String columns, String from, String alias, Long fieldId, Pageable page, RowMapper<T> mapper) {
        Map<String, Long> filter = fieldId == null ? Map.of() : Map.of("fieldId", fieldId);
        String where = from + (fieldId == null ? "" : " AND " + alias + ".field_id = :fieldId");
        List<T> rows = jdbc.sql("SELECT " + columns + " " + where + " ORDER BY " + alias
                + ".id DESC LIMIT :limit OFFSET :offset").params(filter)
                .param("limit", page.getPageSize()).param("offset", page.getOffset()).query(mapper).list();
        long count = jdbc.sql("SELECT count(*) " + where).params(filter).query(Long.class).single();
        return new PageImpl<>(rows, page, count);
    }

    Optional<PatrolTask> task(long id) {
        return jdbc.sql("SELECT t.*, f.name AS field_name " + TASKS + " AND t.id = :id")
                .param("id", id).query(this::taskRow).optional();
    }

    Optional<PatrolFlight> flight(long id) {
        return jdbc.sql("SELECT p.*, t.task_name, f.name AS field_name " + FLIGHTS + " AND p.id = :id")
                .param("id", id).query(this::flightRow).optional();
    }

    boolean fieldExists(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM field WHERE id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    long insertTask(PatrolService.CreateTask input, String cron) {
        return jdbc.sql("""
                INSERT INTO patrol_task(field_id, task_name, schedule_cron, drone_sn, route_geojson)
                VALUES (:field, :name, :cron, :sn, CAST(:route AS jsonb)) RETURNING id
                """).param("field", input.fieldId()).param("name", input.taskName().strip())
                .param("cron", cron).param("sn", input.droneSn()).param("route", input.routeGeojson().toString())
                .query(Long.class).single();
    }

    boolean updateTask(long id, String status) {
        return jdbc.sql("UPDATE patrol_task SET status = :status WHERE id = :id AND status <> 'deleted'")
                .param("id", id).param("status", status).update() == 1;
    }

    List<PatrolTask> activeTasks() {
        return jdbc.sql("SELECT t.*, f.name AS field_name " + TASKS + " AND t.status = 'active'")
                .query(this::taskRow).list();
    }

    List<String> droneSerials() {
        return jdbc.sql("SELECT DISTINCT drone_sn FROM patrol_task WHERE drone_sn IS NOT NULL AND status <> 'deleted'")
                .query(String.class).list();
    }

    Optional<Long> insertFlight(long taskId, Instant scheduledFor) {
        return jdbc.sql("""
                INSERT INTO patrol_flight(task_id, field_id, scheduled_for)
                SELECT id, field_id, :slot FROM patrol_task WHERE id = :task AND status = 'active'
                ON CONFLICT (task_id, scheduled_for) DO NOTHING RETURNING id
                """).param("task", taskId).param("slot", timestamp(scheduledFor)).query(Long.class).optional();
    }

    List<Long> scheduledFlights(Instant slot) {
        jdbc.sql("""
                UPDATE patrol_flight SET status = 'failed', error_message = '定时下发窗口已过'
                WHERE status = 'pending' AND scheduled_for < :slot
                """).param("slot", timestamp(slot)).update();
        return jdbc.sql("""
                SELECT id FROM patrol_flight WHERE status = 'pending' AND scheduled_for = :slot ORDER BY id
                """).param("slot", timestamp(slot)).query(Long.class).list();
    }

    boolean startFlight(long id) {
        return jdbc.sql("""
                UPDATE patrol_flight p SET status = 'flying', start_time = now()
                FROM patrol_task t WHERE p.id = :id AND t.id = p.task_id
                    AND p.status = 'pending' AND t.status = 'active'
                """).param("id", id).update() == 1;
    }

    void dispatchFailed(long id) {
        jdbc.sql("""
                UPDATE patrol_flight SET status = 'failed', end_time = now(), error_message = 'MQTT 指令未确认'
                WHERE id = :id AND status = 'flying'
                """).param("id", id).update();
    }

    void handleDroneReport(long id, String sn, String phase, String rawPath) {
        jdbc.sql("""
                UPDATE patrol_flight p
                SET status = CASE WHEN :phase = 'done' THEN 'processing' ELSE 'failed' END,
                    end_time = now(), raw_image_path = coalesce(:raw, p.raw_image_path),
                    error_message = CASE WHEN :phase = 'done' THEN NULL ELSE '无人机报告飞行失败' END
                FROM patrol_task t WHERE p.id = :id AND t.id = p.task_id AND t.drone_sn = :sn
                    AND p.status = 'flying'
                """).param("id", id).param("sn", sn).param("phase", phase).param("raw", rawPath).update();
    }

    boolean attachImage(long id, String rawPath) {
        return jdbc.sql("""
                UPDATE patrol_flight SET raw_image_path = :raw, error_message = NULL,
                    status = CASE WHEN status = 'failed' THEN 'processing' ELSE status END
                WHERE id = :id AND (status = 'flying'
                    OR (status = 'processing' AND processing_started_at IS NULL)
                    OR (status = 'failed' AND error_message LIKE 'NDVI%'))
                """).param("id", id).param("raw", rawPath).update() == 1;
    }

    Optional<ImageJob> claimImage() {
        return jdbc.sql("""
                UPDATE patrol_flight SET processing_started_at = now()
                WHERE id = (
                    SELECT id FROM patrol_flight WHERE status = 'processing' AND raw_image_path IS NOT NULL
                        AND (processing_started_at IS NULL OR processing_started_at < now() - interval '10 minutes')
                    ORDER BY id LIMIT 1 FOR UPDATE SKIP LOCKED
                ) RETURNING id, raw_image_path, processing_started_at
                """).query((rs, row) -> new ImageJob(rs.getLong("id"), rs.getString("raw_image_path"),
                        time(rs, "processing_started_at"))).optional();
    }

    void finishImage(ImageJob job, JsonNode result, String error) {
        boolean done = result != null;
        jdbc.sql("""
                UPDATE patrol_flight SET status = :status, ndvi_stats = CAST(:stats AS jsonb),
                    ndvi_tile_url = :tiles, ndvi_image_path = :image, error_message = :error,
                    processing_started_at = NULL
                WHERE id = :id AND status = 'processing' AND processing_started_at = :lease
                """).param("id", job.id()).param("lease", timestamp(job.startedAt()))
                .param("status", done ? "done" : "failed")
                .param("stats", done ? result.get("stats").toString() : null)
                .param("image", done ? result.get("ndviPath").asText() : null)
                .param("tiles", done ? "/api/patrol/flights/" + job.id() + "/ndvi/tilejson" : null)
                .param("error", error).update();
    }

    private PatrolTask taskRow(ResultSet rs, int row) throws SQLException {
        return new PatrolTask(rs.getLong("id"), rs.getLong("field_id"), rs.getString("field_name"),
                rs.getString("task_name"), rs.getString("schedule_cron"), rs.getString("drone_sn"),
                readJson(rs, "route_geojson"), rs.getString("status"), time(rs, "created_at"));
    }

    private PatrolFlight flightRow(ResultSet rs, int row) throws SQLException {
        return new PatrolFlight(rs.getLong("id"), rs.getLong("task_id"), rs.getString("task_name"),
                rs.getLong("field_id"), rs.getString("field_name"), rs.getString("status"),
                time(rs, "start_time"), time(rs, "end_time"), time(rs, "scheduled_for"), time(rs, "created_at"),
                rs.getString("raw_image_path"), rs.getString("ndvi_image_path"), rs.getString("ndvi_tile_url"),
                readJson(rs, "ndvi_stats"), rs.getString("error_message"));
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        try {
            return value == null ? null : json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid patrol JSON", e);
        }
    }

    private static Instant time(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    record ImageJob(long id, String rawPath, Instant startedAt) {}
}
