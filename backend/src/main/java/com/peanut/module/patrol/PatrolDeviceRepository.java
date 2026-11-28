package com.peanut.module.patrol;

import static com.peanut.module.patrol.PatrolDeviceRequests.*;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class PatrolDeviceRepository {
    private static final String COLUMNS = """
            SELECT d.*, f.name AS field_name, parent.device_name AS parent_name,
                   parent.device_sn AS parent_sn, parent.online AS parent_online
            """;
    private static final String FROM = """
            FROM patrol_device d LEFT JOIN field f ON f.id=d.field_id
            LEFT JOIN patrol_device parent ON parent.id=d.parent_device_id WHERE true
            """;
    private final JdbcClient jdbc;
    private final ObjectMapper json;

    PatrolDeviceRepository(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    // Serializes metadata/relationship changes with creation and claiming of flights.
    void lockRegistry() {
        jdbc.sql("SELECT pg_advisory_xact_lock(742031105)").query((rs, n) -> true).single();
    }

    Optional<PatrolDevice> find(long id) {
        return jdbc.sql(COLUMNS + FROM + " AND d.id=:id").param("id", id).query(this::device).optional();
    }

    Optional<PatrolDevice> find(String sn, String type) {
        return jdbc.sql(COLUMNS + FROM + " AND d.device_sn=:sn AND d.device_type=:type")
                .param("sn", sn).param("type", type).query(this::device).optional();
    }

    List<PatrolDevice> sensors(Long field) {
        var query = jdbc.sql(COLUMNS + FROM + " AND d.device_type='sensor' AND d.status<>'retired'"
                + (field == null ? "" : " AND d.field_id=:field") + " ORDER BY d.id DESC");
        if (field != null) {
            query.param("field",field);
        }
        return query.query(this::device).list();
    }

    Page<PatrolDevice> list(String type, Long field, String status, Boolean online, String keyword,
            Long taskFieldId, Pageable page) {
        var where = new StringBuilder(FROM);
        var params = new LinkedHashMap<String, Object>();
        filter(where, params, "d.device_type", "type", type);
        filter(where, params, "d.field_id", "field", field);
        filter(where, params, "d.status", "status", status);
        filter(where, params, "d.online", "online", online);
        if (keyword != null && !keyword.isBlank()) {
            where.append(" AND (strpos(lower(d.device_name),lower(:keyword))>0 OR strpos(lower(d.device_sn),lower(:keyword))>0)");
            params.put("keyword", keyword.strip());
        }
        if (taskFieldId != null) {
            where.append(" AND d.device_type='drone' AND d.status='active' AND (d.field_id IS NULL OR d.field_id=:taskField)");
            params.put("taskField", taskFieldId);
        }
        var rows = jdbc.sql(COLUMNS + where + " ORDER BY d.id DESC LIMIT :size OFFSET :offset").params(params)
                .param("size", page.getPageSize()).param("offset", page.getOffset()).query(this::device).list();
        long count = jdbc.sql("SELECT count(*) " + where).params(params).query(Long.class).single();
        return new PageImpl<>(rows, page, count);
    }

    private static void filter(StringBuilder where, Map<String,Object> params, String column, String key, Object value) {
        if (value != null) {
            where.append(" AND ").append(column).append("=:").append(key);
            params.put(key, value);
        }
    }

    Map<String, Totals> stats() {
        var result = new LinkedHashMap<String, Totals>();
        for (String type : new String[]{"drone", "dock", "sensor"}) {
            result.put(type, new Totals(0,0,0,0));
        }
        jdbc.sql("""
                SELECT device_type, count(*) AS total, count(*) FILTER (WHERE online) AS online,
                       count(*) FILTER (WHERE status='maintenance') AS maintenance
                FROM patrol_device GROUP BY device_type ORDER BY device_type
                """).query((rs, n) -> {
                    long total = rs.getLong("total"), online = rs.getLong("online");
                    result.put(rs.getString("device_type"), new Totals(total, online, total-online, rs.getLong("maintenance")));
                    return true;
                }).list();
        return result;
    }

    Optional<Long> insert(DeviceInput input) {
        return jdbc.sql("""
                INSERT INTO patrol_device(device_type,device_sn,device_name,brand,model,field_id,parent_device_id,
                    capabilities,installed_at,remark)
                VALUES (:type,:sn,:name,:brand,:model,:field,:parent,CAST(:cap AS jsonb),:installed,:remark)
                ON CONFLICT (device_sn) DO NOTHING RETURNING id
                """).param("type", input.deviceType()).param("sn", input.deviceSn()).params(metadata(input))
                .query(Long.class).optional();
    }

    void update(long id, DeviceInput input) {
        jdbc.sql("""
                UPDATE patrol_device SET device_name=:name,brand=:brand,model=:model,field_id=:field,
                    parent_device_id=:parent,capabilities=CAST(:cap AS jsonb),installed_at=:installed,
                    remark=:remark,updated_at=now() WHERE id=:id
                """).param("id", id).params(metadata(input)).update();
    }

    private static Map<String,Object> metadata(DeviceInput input) {
        var values = new LinkedHashMap<String,Object>();
        values.put("name", input.deviceName().strip());
        values.put("brand", input.brand());
        values.put("model", input.model());
        values.put("field", input.fieldId());
        values.put("parent", input.parentDeviceId());
        values.put("cap", input.capabilities() == null ? "{}" : input.capabilities().toString());
        values.put("installed", PatrolRepository.timestamp(input.installedAt()));
        values.put("remark", input.remark());
        return values;
    }

    void setStatus(long id, String status) {
        jdbc.sql("""
                UPDATE patrol_device SET status=:status, online=CASE WHEN :status='retired' THEN false ELSE online END,
                    updated_at=now() WHERE id=:id
                """).param("id", id).param("status", status).update();
    }

    boolean hasActiveReferences(long id) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM patrol_task WHERE drone_device_id=:id AND status='active')
                    OR EXISTS (SELECT 1 FROM patrol_flight WHERE drone_device_id=:id
                        AND status IN ('pending','dispatching','flying','processing'))
                    OR EXISTS (SELECT 1 FROM patrol_device WHERE parent_device_id=:id AND status<>'retired')
                """).param("id", id).query(Boolean.class).single();
    }

    boolean conflictsWithChildren(long id, long fieldId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM patrol_device WHERE parent_device_id=:id AND status<>'retired' AND field_id<>:field)
                """).param("id", id).param("field", fieldId).query(Boolean.class).single();
    }

    boolean conflictsWithTasks(long id, long fieldId) {
        return jdbc.sql("""
                SELECT EXISTS (SELECT 1 FROM patrol_task WHERE drone_device_id=:id AND status='active' AND field_id<>:field)
                """).param("id", id).param("field", fieldId).query(Boolean.class).single();
    }

    boolean report(long id, String type, Long field, String raw, boolean activeOnly) {
        return jdbc.sql("""
                UPDATE patrol_device d SET last_seen_at=now(),online=true,last_status=data.report,
                  current_phase=coalesce(left(data.report->>'phase',30),current_phase),
                  battery_pct=coalesce(CAST(data.report->>'battery' AS numeric),battery_pct),
                  longitude=coalesce(CAST(data.report->>'lng' AS numeric),longitude),
                  latitude=coalesce(CAST(data.report->>'lat' AS numeric),latitude),
                  altitude_m=coalesce(CAST(data.report->>'altitude' AS numeric),altitude_m),
                  firmware_version=coalesce(left(data.report->>'firmwareVersion',80),firmware_version),
                  fault_code=CASE WHEN jsonb_exists(data.report,'faultCode') THEN left(data.report->>'faultCode',80) ELSE fault_code END,
                  fault_message=CASE WHEN jsonb_exists(data.report,'faultMessage') THEN left(data.report->>'faultMessage',4000) ELSE fault_message END,
                  updated_at=now()
                FROM (SELECT CAST(:report AS jsonb) AS report) data WHERE d.id=:id AND d.device_type=:type
                  AND d.status<>'retired' AND (NOT :activeOnly OR (d.status='active' AND d.field_id=CAST(:field AS bigint)))
                """)
                .param("id",id).param("type",type).param("report",raw).param("activeOnly",activeOnly)
                .param("field", field).update() == 1;
    }

    void expire(String type, Instant threshold) {
        jdbc.sql("""
                UPDATE patrol_device SET online=false,updated_at=now()
                WHERE device_type=:type AND online AND last_seen_at<=:threshold
                """).param("type",type).param("threshold",PatrolRepository.timestamp(threshold)).update();
    }

    Page<Maintenance> maintenance(long id, Pageable page) {
        var rows = jdbc.sql("""
                SELECT * FROM patrol_device_maintenance WHERE device_id=:id ORDER BY id DESC LIMIT :size OFFSET :offset
                """).param("id",id).param("size",page.getPageSize()).param("offset",page.getOffset())
                .query(this::maintenanceRow).list();
        long total = jdbc.sql("SELECT count(*) FROM patrol_device_maintenance WHERE device_id=:id")
                .param("id",id).query(Long.class).single();
        return new PageImpl<>(rows,page,total);
    }

    Maintenance addMaintenance(long id, MaintenanceInput input, Instant completedAt) {
        Maintenance record = jdbc.sql("""
                INSERT INTO patrol_device_maintenance(device_id,maintenance_type,status,description,operator_name,
                    started_at,completed_at,next_maintenance_at)
                VALUES (:id,:type,:status,:description,:operator,:start,:completed,:next) RETURNING *
                """).param("id",id).param("type",input.maintenanceType()).param("status",input.status())
                .param("description",input.description().strip()).param("operator",input.operatorName())
                .param("start",PatrolRepository.timestamp(input.startedAt())).param("completed",PatrolRepository.timestamp(completedAt))
                .param("next",PatrolRepository.timestamp(input.nextMaintenanceAt())).query(this::maintenanceRow).single();
        jdbc.sql("""
                UPDATE patrol_device SET last_maintenance_at=greatest(last_maintenance_at,CAST(:completed AS timestamptz)),
                    next_maintenance_at=coalesce(CAST(:next AS timestamptz),next_maintenance_at),updated_at=now()
                WHERE id=:id
                """).param("id",id).param("completed",PatrolRepository.timestamp(completedAt))
                .param("next",PatrolRepository.timestamp(input.nextMaintenanceAt())).update();
        return record;
    }

    private Maintenance maintenanceRow(ResultSet rs, int row) throws SQLException {
        return new Maintenance(rs.getLong("id"),rs.getLong("device_id"),rs.getString("maintenance_type"),
                rs.getString("status"),rs.getString("description"),rs.getString("operator_name"),
                PatrolRepository.time(rs,"started_at"),PatrolRepository.time(rs,"completed_at"),
                PatrolRepository.time(rs,"next_maintenance_at"),PatrolRepository.time(rs,"created_at"));
    }

    private PatrolDevice device(ResultSet rs, int row) throws SQLException {
        return new PatrolDevice(rs.getLong("id"),rs.getString("device_type"),rs.getString("device_sn"),
                rs.getString("device_name"),rs.getString("brand"),rs.getString("model"),rs.getObject("field_id",Long.class),
                rs.getString("field_name"),rs.getObject("parent_device_id",Long.class),rs.getString("parent_name"),
                rs.getString("parent_sn"),rs.getObject("parent_online",Boolean.class),rs.getString("status"),
                rs.getBoolean("online"),PatrolRepository.time(rs,"last_seen_at"),rs.getString("current_phase"),
                readJson(rs,"last_status"),number(rs,"battery_pct"),number(rs,"longitude"),
                number(rs,"latitude"),number(rs,"altitude_m"),rs.getString("firmware_version"),
                readJson(rs,"capabilities"),rs.getString("fault_code"),rs.getString("fault_message"),
                PatrolRepository.time(rs,"installed_at"),PatrolRepository.time(rs,"last_maintenance_at"),
                PatrolRepository.time(rs,"next_maintenance_at"),rs.getString("remark"),PatrolRepository.time(rs,"created_at"),
                PatrolRepository.time(rs,"updated_at"));
    }

    private static Double number(ResultSet rs, String column) throws SQLException {
        var value = rs.getBigDecimal(column);
        return value == null ? null : value.doubleValue();
    }

    private JsonNode readJson(ResultSet rs, String column) throws SQLException {
        try {
            return json.readTree(rs.getString(column));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Invalid device JSON", e);
        }
    }
}
