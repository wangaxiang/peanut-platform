package com.peanut.module.iot;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class IotCatalog {
    static final String CODE = "[a-z][a-z0-9_]{0,63}";
    public record DeviceType(String code, String name, String category, String description,
            Integer defaultSampleIntervalSeconds, int defaultOfflineTimeoutSeconds, JsonNode capabilitySchema,
            boolean system, boolean enabled) {
        public boolean needsField() { return capabilitySchema.path("fieldRequired").asBoolean(false); }
        public List<String> suggestedMetrics() { return codes(capabilitySchema.path("metrics")); }
    }
    public record TypeInput(@NotBlank @Pattern(regexp=CODE) String code, @NotBlank @Size(max=80) String name,
            @NotBlank @Size(max=40) String category, @Size(max=2000) String description,
            @Min(5) @Max(86400) Integer defaultSampleIntervalSeconds,
            @Min(1) @Max(604800) int defaultOfflineTimeoutSeconds, JsonNode capabilitySchema, boolean enabled) {}
    public record Metric(String code, String displayName, String category, String canonicalUnit,
            int precisionDigits, Double recommendedMin, Double recommendedMax, String description,
            int displayOrder, boolean enabled) {}
    public record MetricInput(@NotBlank @Pattern(regexp=CODE) String code, @NotBlank @Size(max=100) String displayName,
            @NotBlank @Size(max=40) String category, @NotBlank @Size(max=32) String canonicalUnit,
            @Min(0) @Max(8) int precisionDigits, Double recommendedMin, Double recommendedMax,
            @Size(max=2000) String description, int displayOrder, boolean enabled) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;

    IotCatalog(JdbcClient jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    List<DeviceType> types() {
        return jdbc.sql("SELECT * FROM iot_device_type ORDER BY is_system DESC,name").query(this::typeRow).list();
    }

    public DeviceType type(String code) {
        return jdbc.sql("SELECT * FROM iot_device_type WHERE code=:code").param("code",code).query(this::typeRow)
                .optional().orElseThrow(() -> bad("设备类型未登记"));
    }

    List<Metric> metrics() {
        return jdbc.sql("SELECT * FROM iot_metric_definition ORDER BY display_order,code").query(this::metricRow).list();
    }

    List<Metric> capabilities(long id) {
        return jdbc.sql("""
                SELECT m.* FROM iot_device_metric_capability c JOIN iot_metric_definition m ON m.code=c.metric_code
                WHERE c.device_id=:id AND c.enabled AND m.enabled ORDER BY m.display_order,m.code
                """).param("id",id).query(this::metricRow).list();
    }

    public List<String> validateMetrics(List<String> values) {
        var unique = values.stream().distinct().toList();
        if (values.size() > 64 || unique.stream().anyMatch(code -> code == null || !code.matches(CODE))) {
            throw bad("指标编号无效或数量超过 64");
        }
        if (!unique.isEmpty() && jdbc.sql("SELECT count(*) FROM iot_metric_definition WHERE enabled AND code IN (:codes)")
                .param("codes",unique).query(Long.class).single() != unique.size()) {
            throw bad("包含未登记或已停用的指标");
        }
        return unique;
    }

    public void setCapabilities(long id, List<String> codes) {
        jdbc.sql("UPDATE iot_device_metric_capability SET enabled=false WHERE device_id=:id AND enabled")
                .param("id",id).update();
        for (String code : codes) {
            jdbc.sql("""
                    INSERT INTO iot_device_metric_capability(device_id,metric_code) VALUES (:id,:code)
                    ON CONFLICT (device_id,metric_code) DO UPDATE SET enabled=true
                    """).param("id",id).param("code",code).update();
        }
    }

    @Transactional
    public DeviceType saveType(String pathCode, TypeInput input) {
        checkCode(pathCode,input.code());
        JsonNode schema = Optional.ofNullable(input.capabilitySchema()).orElseGet(json::createObjectNode);
        JsonNode defaults = schema.path("metrics");
        if (!schema.isObject() || (!defaults.isMissingNode() && !defaults.isArray())
                || (schema.has("fieldRequired") && !schema.get("fieldRequired").isBoolean())) throw bad("设备类型能力配置无效");
        validateMetrics(codes(defaults));
        String sql = pathCode == null ? """
                INSERT INTO iot_device_type(code,name,category,description,default_sample_interval_seconds,
                    default_offline_timeout_seconds,capability_schema,enabled)
                VALUES (:code,:name,:category,:description,:sample,:offline,CAST(:schema AS jsonb),:enabled)
                ON CONFLICT (code) DO NOTHING
                """ : """
                UPDATE iot_device_type SET name=:name,category=:category,description=:description,
                    default_sample_interval_seconds=:sample,default_offline_timeout_seconds=:offline,
                    capability_schema=CAST(:schema AS jsonb),enabled=:enabled,updated_at=now() WHERE code=:code
                """;
        int updated = jdbc.sql(sql).param("code",input.code()).param("name",input.name().strip())
                .param("category",input.category().strip()).param("description",input.description())
                .param("sample",input.defaultSampleIntervalSeconds()).param("offline",input.defaultOfflineTimeoutSeconds())
                .param("schema",schema.toString()).param("enabled",input.enabled()).update();
        if (updated == 0) throw bad("设备类型编号已存在或待编辑类型不存在");
        return type(input.code());
    }

    @Transactional
    public Metric saveMetric(String pathCode, MetricInput input) {
        checkCode(pathCode,input.code());
        if ((input.recommendedMin()!=null && !Double.isFinite(input.recommendedMin()))
                || (input.recommendedMax()!=null && !Double.isFinite(input.recommendedMax()))
                || (input.recommendedMin()!=null && input.recommendedMax()!=null
                && input.recommendedMin()>input.recommendedMax())) throw bad("指标参考范围无效");
        if (pathCode != null) {
            var previous = jdbc.sql("SELECT canonical_unit FROM iot_metric_definition WHERE code=:code")
                    .param("code",pathCode).query(String.class).optional().orElseThrow(() -> bad("指标不存在"));
            if (!previous.equals(input.canonicalUnit().strip()) && jdbc.sql("""
                    SELECT EXISTS(SELECT 1 FROM iot_device_metric_capability WHERE metric_code=:code)
                    """).param("code",pathCode).query(Boolean.class).single()) throw bad("已使用的指标单位固定，请登记新指标");
        }
        String sql = pathCode == null ? """
                INSERT INTO iot_metric_definition(code,display_name,category,canonical_unit,precision_digits,
                    recommended_min,recommended_max,description,display_order,enabled)
                VALUES (:code,:name,:category,:unit,:precision,:min,:max,:description,:ordering,:enabled)
                ON CONFLICT (code) DO NOTHING RETURNING *
                """ : """
                UPDATE iot_metric_definition SET display_name=:name,category=:category,canonical_unit=:unit,
                    precision_digits=:precision,recommended_min=:min,recommended_max=:max,description=:description,
                    display_order=:ordering,enabled=:enabled WHERE code=:code RETURNING *
                """;
        return jdbc.sql(sql).param("code",input.code()).param("name",input.displayName().strip())
                .param("category",input.category().strip()).param("unit",input.canonicalUnit().strip())
                .param("precision",input.precisionDigits()).param("min",input.recommendedMin()).param("max",input.recommendedMax())
                .param("description",input.description()).param("ordering",input.displayOrder()).param("enabled",input.enabled())
                .query(this::metricRow).optional().orElseThrow(() -> bad("指标编号已存在"));
    }

    public void event(Long device, Long field, String kind, String summary, String actor, JsonNode payload) {
        jdbc.sql("""
                INSERT INTO iot_device_event(device_id,field_id,event_type,summary,actor,payload)
                VALUES (:device,:field,:kind,:summary,:actor,CAST(:payload AS jsonb))
                """).param("device",device).param("field",field).param("kind",kind).param("summary",summary)
                .param("actor",actor).param("payload",payload==null?"{}":payload.toString()).update();
    }

    static List<String> codes(JsonNode node) {
        var result = new ArrayList<String>();
        node.forEach(item -> result.add(item.asText()));
        return result;
    }

    JsonNode json(String value) throws SQLException {
        try {
            return json.readTree(value);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new SQLException("Invalid IoT JSON",e);
        }
    }

    private DeviceType typeRow(ResultSet rs, int row) throws SQLException {
        return new DeviceType(rs.getString("code"),rs.getString("name"),rs.getString("category"),rs.getString("description"),
                rs.getObject("default_sample_interval_seconds",Integer.class),rs.getInt("default_offline_timeout_seconds"),
                json(rs.getString("capability_schema")),rs.getBoolean("is_system"),rs.getBoolean("enabled"));
    }

    private Metric metricRow(ResultSet rs, int row) throws SQLException {
        return new Metric(rs.getString("code"),rs.getString("display_name"),rs.getString("category"),rs.getString("canonical_unit"),
                rs.getInt("precision_digits"),rs.getObject("recommended_min",Double.class),rs.getObject("recommended_max",Double.class),
                rs.getString("description"),rs.getInt("display_order"),rs.getBoolean("enabled"));
    }

    private static void checkCode(String expected, String code) {
        if (expected != null && !expected.equals(code)) throw bad("编号登记后不可修改");
    }

    public static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,message);
    }
}
