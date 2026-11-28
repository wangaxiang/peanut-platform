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
    private static final String TASK_COLUMNS = """
            t.*, f.name AS field_name, coalesce(d.device_sn,t.drone_sn) AS display_sn,
            d.device_name AS drone_name, d.online AS drone_online, dock.device_name AS dock_name
            """;
    private static final String FLIGHT_COLUMNS = """
            p.*, t.task_name, f.name AS field_name, d.device_name AS drone_name,
            coalesce(d.device_sn,t.drone_sn) AS display_sn
            """;
    private static final String TASKS = """
            FROM patrol_task t JOIN field f ON f.id = t.field_id
            LEFT JOIN patrol_device d ON d.id=t.drone_device_id
            LEFT JOIN patrol_device dock ON dock.id=d.parent_device_id
            WHERE t.status <> 'deleted'
            """;
    private static final String FLIGHTS = """
            FROM patrol_flight p JOIN patrol_task t ON t.id = p.task_id JOIN field f ON f.id = p.field_id
            LEFT JOIN patrol_device d ON d.id=p.drone_device_id
            WHERE true
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PatrolRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    Page<PatrolTask> tasks(Long fieldId, Pageable page) {
        return page(TASK_COLUMNS, TASKS, "t", fieldId, page, this::taskRow);
    }

    Page<PatrolFlight> flights(Long fieldId, boolean ndviOnly, Pageable page) {
        String from = ndviOnly ? FLIGHTS + " AND p.status='done' AND p.ndvi_tile_url IS NOT NULL" : FLIGHTS;
        return page(FLIGHT_COLUMNS, from, "p", fieldId, page, this::flightRow);
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
        return jdbc.sql("SELECT " + TASK_COLUMNS + TASKS + " AND t.id = :id")
                .param("id", id).query(this::taskRow).optional();
    }

    Optional<PatrolFlight> flight(long id) {
        return jdbc.sql("SELECT " + FLIGHT_COLUMNS + FLIGHTS + " AND p.id = :id")
                .param("id", id).query(this::flightRow).optional();
    }

    boolean fieldExists(long id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM field WHERE id = :id)")
                .param("id", id).query(Boolean.class).single();
    }

    long insertTask(PatrolService.CreateTask input, String cron) {
        return jdbc.sql("""
                INSERT INTO patrol_task(field_id, task_name, schedule_cron, scheduled_at, drone_device_id, requires_dock, route_geojson)
                VALUES (:field, :name, :cron, :scheduledAt, :drone, :dock, CAST(:route AS jsonb)) RETURNING id
                """).param("field", input.fieldId()).param("name", input.taskName().strip())
                .param("cron", cron).param("scheduledAt", timestamp(input.scheduledAt()))
                .param("drone", input.droneDeviceId()).param("dock", input.requiresDock())
                .param("route", input.routeGeojson().toString())
                .query(Long.class).single();
    }

    boolean updateTask(long id, String status) {
        return jdbc.sql("UPDATE patrol_task SET status = :status WHERE id = :id AND status <> 'deleted'")
                .param("id", id).param("status", status).update() == 1;
    }

    List<PatrolTask> activeTasks(Instant slot) {
        return jdbc.sql("SELECT " + TASK_COLUMNS + TASKS
                + " AND t.status = 'active' AND (t.scheduled_at IS NULL OR t.scheduled_at = :slot)")
                .param("slot", timestamp(slot)).query(this::taskRow).list();
    }

    boolean mapLegacyTask(long id, long droneId) {
        return jdbc.sql("""
                UPDATE patrol_task SET drone_device_id=:drone
                WHERE id=:id AND status<>'deleted' AND drone_device_id IS NULL AND drone_sn IS NOT NULL
                """).param("id",id).param("drone",droneId).update() == 1;
    }

    Optional<Long> insertFlight(long taskId, Instant scheduledFor) {
        return jdbc.sql("""
                INSERT INTO patrol_flight(task_id, field_id, scheduled_for, drone_device_id)
                SELECT id, field_id, :slot, drone_device_id FROM patrol_task WHERE id = :task AND status = 'active'
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
                UPDATE patrol_flight p SET status = 'dispatching', start_time = now()
                FROM patrol_task t WHERE p.id = :id AND t.id = p.task_id
                    AND p.status = 'pending' AND t.status = 'active'
                """).param("id", id).update() == 1;
    }

    void confirmDispatch(long id) {
        jdbc.sql("UPDATE patrol_flight SET status='flying' WHERE id=:id AND status='dispatching'")
                .param("id",id).update();
    }

    void failPendingFlight(long id, String error) {
        jdbc.sql("""
                UPDATE patrol_flight SET status='failed',end_time=now(),error_message=:error
                WHERE id=:id AND status='pending'
                """).param("id",id).param("error",error).update();
    }

    void dispatchFailed(long id, String error) {
        jdbc.sql("""
                UPDATE patrol_flight SET status='failed',end_time=now(),error_message=:error
                WHERE id=:id AND status IN ('pending','dispatching')
                """).param("id",id).param("error",error).update();
    }

    void handleDroneReport(long id, long deviceId, String sn, String phase, String rawPath, String fault) {
        jdbc.sql("""
                UPDATE patrol_flight p
                SET status = CASE WHEN :phase = 'done' THEN 'processing' ELSE 'failed' END,
                    end_time = now(), raw_image_path = coalesce(:raw, p.raw_image_path),
                    error_message = CASE WHEN :phase = 'done' THEN NULL ELSE :fault END
                FROM patrol_task t WHERE p.id=:id AND t.id=p.task_id
                    AND (p.drone_device_id=:device OR (p.drone_device_id IS NULL AND t.drone_sn=:sn))
                    AND p.status IN ('dispatching','flying')
                """).param("id",id).param("device",deviceId).param("sn",sn).param("phase",phase)
                .param("raw", rawPath).param("fault",fault).update();
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
                rs.getString("task_name"), rs.getString("schedule_cron"), time(rs, "scheduled_at"), rs.getString("display_sn"),
                readJson(rs, "route_geojson"), rs.getString("status"), time(rs, "created_at"),
                rs.getObject("drone_device_id",Long.class),rs.getString("drone_name"),rs.getObject("drone_online",Boolean.class),
                rs.getString("dock_name"),rs.getBoolean("requires_dock"));
    }

    private PatrolFlight flightRow(ResultSet rs, int row) throws SQLException {
        return new PatrolFlight(rs.getLong("id"), rs.getLong("task_id"), rs.getString("task_name"),
                rs.getLong("field_id"), rs.getString("field_name"), rs.getString("status"),
                time(rs, "start_time"), time(rs, "end_time"), time(rs, "scheduled_for"), time(rs, "created_at"),
                rs.getString("raw_image_path"), rs.getString("ndvi_image_path"), rs.getString("ndvi_tile_url"),
                readJson(rs, "ndvi_stats"), rs.getString("error_message"),rs.getObject("drone_device_id",Long.class),
                rs.getString("drone_name"),rs.getString("display_sn"));
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        String value = rs.getString(column);
        try {
            return value == null ? null : json.readTree(value);
        } catch (JsonProcessingException e) {
            throw new SQLException("Invalid patrol JSON", e);
        }
    }

    static Instant time(ResultSet rs, String column) throws SQLException {
        OffsetDateTime value = rs.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    static OffsetDateTime timestamp(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    record ImageJob(long id, String rawPath, Instant startedAt) {}
}
