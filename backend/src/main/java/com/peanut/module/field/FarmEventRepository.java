package com.peanut.module.field;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class FarmEventRepository {
    private final NamedParameterJdbcTemplate jdbc;

    public FarmEventRepository(NamedParameterJdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Page<FarmEvent> findByFieldId(long fieldId, Pageable pageable) {
        List<FarmEvent> items = jdbc.query("""
                SELECT id, field_id, event_type, event_date, operator, amount, unit, remark, created_at
                FROM farm_event WHERE field_id = :fieldId
                ORDER BY event_date DESC, id DESC LIMIT :limit OFFSET :offset
                """, Map.of("fieldId", fieldId, "limit", pageable.getPageSize(), "offset", pageable.getOffset()), this::mapEvent);
        long total = jdbc.queryForObject("SELECT count(*) FROM farm_event WHERE field_id = :fieldId",
                Map.of("fieldId", fieldId), Long.class);
        return new PageImpl<>(items, pageable, total);
    }

    public FarmEvent insert(long fieldId, FarmEventRequest event) {
        MapSqlParameterSource parameters = new MapSqlParameterSource("fieldId", fieldId)
                .addValue("eventType", event.eventType().value()).addValue("eventDate", event.eventDate())
                .addValue("operator", event.operator()).addValue("amount", event.amount())
                .addValue("unit", event.unit()).addValue("remark", event.remark());
        return jdbc.queryForObject("""
                INSERT INTO farm_event (field_id, event_type, event_date, operator, amount, unit, remark)
                VALUES (:fieldId, :eventType, :eventDate, :operator, :amount, :unit, :remark)
                RETURNING id, field_id, event_type, event_date, operator, amount, unit, remark, created_at
                """, parameters, this::mapEvent);
    }

    private FarmEvent mapEvent(ResultSet result, int row) throws SQLException {
        return new FarmEvent(result.getLong("id"), result.getLong("field_id"), FarmEventType.fromValue(result.getString("event_type")),
                result.getObject("event_date", LocalDate.class), result.getString("operator"),
                result.getBigDecimal("amount"), result.getString("unit"), result.getString("remark"),
                result.getObject("created_at", OffsetDateTime.class));
    }
}
