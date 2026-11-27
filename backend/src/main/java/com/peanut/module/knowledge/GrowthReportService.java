package com.peanut.module.knowledge;

import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.peanut.module.field.FieldVO;
import com.peanut.module.field.FieldService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class GrowthReportService {
    private final NamedParameterJdbcTemplate jdbc;
    private final FieldService fields;
    private final GrowthReportSensors sensors;
    private final GrowthReportRenderer renderer;
    private final GrowthReportStorage storage;
    private final ZoneId zone;

    GrowthReportService(NamedParameterJdbcTemplate jdbc, FieldService fields, GrowthReportSensors sensors,
            GrowthReportRenderer renderer, GrowthReportStorage storage, @Value("${growth-report.zone}") String zone) {
        this.jdbc = jdbc;
        this.fields = fields;
        this.sensors = sensors;
        this.renderer = renderer;
        this.storage = storage;
        this.zone = ZoneId.of(zone);
    }

    List<Long> activeFields(LocalDate date) {
        return jdbc.queryForList("""
                SELECT field.id FROM field WHERE field.sow_date <= :date
                  AND NOT EXISTS (
                    SELECT 1 FROM farm_event WHERE farm_event.field_id = field.id AND event_type = 'harvest'
                      AND event_date >= field.sow_date AND event_date <= :date
                  )
                ORDER BY field.id
                """, Map.of("date", date), Long.class);
    }

    Report generate(long fieldId, LocalDate date) throws IOException {
        FieldVO field = fields.get(fieldId);
        LocalDate start = date.minusDays(7);
        var from = start.atStartOfDay(zone).toInstant();
        var to = date.atStartOfDay(zone).toInstant();
        Map<String, Double> averages = sensors.weeklyAverages(fieldId, from, to);
        List<Activity> events = jdbc.query("""
                SELECT event_type, event_date, amount, unit, remark FROM farm_event
                WHERE field_id = :fieldId AND event_date >= :start AND event_date < :end
                ORDER BY event_date DESC, id DESC
                """, Map.of("fieldId", fieldId, "start", start, "end", date),
                (row, index) -> new Activity(row.getObject("event_date", LocalDate.class), eventType(row.getString("event_type")),
                        row.getBigDecimal("amount") == null ? "—" : row.getBigDecimal("amount").stripTrailingZeros().toPlainString()
                                + " " + java.util.Objects.toString(row.getString("unit"), ""),
                        java.util.Objects.toString(row.getString("remark"), "")));
        long alerts = jdbc.queryForObject("""
                SELECT count(*) FROM pest_detection
                WHERE field_id = :fieldId AND alert_sent IS TRUE AND created_at >= :start AND created_at < :end
                """, Map.of("fieldId", fieldId, "start", from.atOffset(java.time.ZoneOffset.UTC),
                "end", to.atOffset(java.time.ZoneOffset.UTC)), Long.class);

        String key = "reports/" + fieldId + "/" + date + ".pdf";
        String url = "/api/reports/growth/" + fieldId + "/pdf?date=" + date;
        byte[] bytes = renderer.render(Map.ofEntries(
                Map.entry("field", field), Map.entry("start", start), Map.entry("end", date.minusDays(1)),
                Map.entry("generated", date), Map.entry("events", events), Map.entry("alerts", alerts),
                Map.entry("temp", sensorValue(averages.get("soil_temp"), "℃")),
                Map.entry("moisture", sensorValue(averages.get("soil_moisture"), "%"))));
        storage.save(key, bytes);
        jdbc.update("""
                INSERT INTO growth_report (field_id, report_date, storage_key, pdf_url)
                VALUES (:fieldId, :date, :key, :url)
                ON CONFLICT (field_id, report_date) DO UPDATE SET storage_key = EXCLUDED.storage_key,
                    pdf_url = EXCLUDED.pdf_url, created_at = NOW()
                """, Map.of("fieldId", fieldId, "date", date, "key", key, "url", url));
        return new Report(fieldId, date, url);
    }

    byte[] download(long fieldId, LocalDate date) throws IOException {
        try {
            String key = jdbc.queryForObject("""
                    SELECT storage_key FROM growth_report WHERE field_id = :fieldId AND report_date = :date
                    """, Map.of("fieldId", fieldId, "date", date), String.class);
            return storage.read(key);
        } catch (EmptyResultDataAccessException error) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "生长报告不存在");
        }
    }

    private static String sensorValue(Double value, String unit) {
        return value == null || !Double.isFinite(value) ? "暂无数据" : String.format(Locale.ROOT, "%.1f %s", value, unit);
    }

    private static String eventType(String type) {
        return switch (type) {
            case "sow" -> "播种";
            case "fertilize" -> "施肥";
            case "spray" -> "打药";
            case "harvest" -> "收获";
            case "irrigate" -> "灌溉";
            default -> type;
        };
    }

    record Report(long fieldId, LocalDate reportDate, String pdfUrl) {
    }

    public record Activity(LocalDate date, String type, String amount, String remark) {
    }
}
