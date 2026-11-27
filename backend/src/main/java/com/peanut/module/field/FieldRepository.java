package com.peanut.module.field;

import java.io.IOException;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.web.server.ResponseStatusException;

@Repository
public class FieldRepository {
    private static final String COLUMNS = """
            id, name, ST_AsGeoJSON(geometry, 15) AS geometry, area_mu, soil_type, variety,
            sow_date, owner_name, remark, created_at, updated_at
            """;
    private static final String GEOMETRY = """
            WITH parsed AS (
                SELECT ST_SetSRID(ST_GeomFromGeoJSON(:geometry), 4326) AS shape
            ), measured AS (
                SELECT shape, ST_Area(ST_Transform(shape, 32650)) / 666.67 AS area
                FROM parsed
                WHERE NOT ST_IsEmpty(shape) AND ST_IsValid(shape)
            )
            """;
    private static final String FILTER = """
            WHERE :keyword = '' OR strpos(lower(name), lower(:keyword)) > 0
                OR strpos(lower(variety), lower(:keyword)) > 0
            """;
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "id", "id", "name", "name", "areaMu", "area_mu",
            "soilType", "soil_type", "variety", "variety", "sowDate", "sow_date",
            "ownerName", "owner_name", "createdAt", "created_at", "updatedAt", "updated_at");

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper json;

    public FieldRepository(NamedParameterJdbcTemplate jdbc, ObjectMapper json) {
        this.jdbc = jdbc;
        this.json = json;
    }

    public Optional<Field> insert(FieldRequest field) {
        return jdbc.query(GEOMETRY + """
                INSERT INTO field (name, geometry, area_mu, soil_type, variety, sow_date, owner_name, remark)
                SELECT :name, shape, area, :soilType, :variety, :sowDate, :ownerName, :remark
                FROM measured
                RETURNING
                """ + COLUMNS, parameters(field), this::mapField).stream().findFirst();
    }

    public Optional<Field> update(long id, FieldRequest field) {
        return jdbc.query(GEOMETRY + """
                UPDATE field SET name = :name, geometry = shape, area_mu = area, soil_type = :soilType,
                    variety = :variety, sow_date = :sowDate, owner_name = :ownerName,
                    remark = :remark, updated_at = NOW()
                FROM measured WHERE id = :id
                RETURNING
                """ + COLUMNS, parameters(field).addValue("id", id), this::mapField).stream().findFirst();
    }

    public Optional<Field> findById(long id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM field WHERE id = :id",
                Map.of("id", id), this::mapField).stream().findFirst();
    }

    public boolean lockForEvent(long id) {
        return !jdbc.queryForList("SELECT id FROM field WHERE id = :id FOR KEY SHARE",
                Map.of("id", id), Long.class).isEmpty();
    }

    public boolean exists(long id) {
        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT EXISTS (SELECT 1 FROM field WHERE id = :id)", Map.of("id", id), Boolean.class));
    }

    public boolean delete(long id) {
        return jdbc.update("DELETE FROM field WHERE id = :id", Map.of("id", id)) != 0;
    }

    public Page<Field> search(String keyword, Pageable page) {
        String sort = page.getSort().stream().map(order -> {
            String column = SORT_COLUMNS.get(order.getProperty());
            if (column == null) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort field: " + order.getProperty());
            }
            return column + " " + order.getDirection().name();
        }).collect(Collectors.joining(", "));
        String orderBy = sort.isEmpty() ? "id DESC" : sort + ", id DESC";
        Map<String, Object> parameters = Map.of("keyword", keyword, "limit", page.getPageSize(),
                "offset", page.getOffset());
        List<Field> fields = jdbc.query("SELECT " + COLUMNS + " FROM field " + FILTER
                + " ORDER BY " + orderBy + " LIMIT :limit OFFSET :offset", parameters, this::mapField);
        long total = jdbc.queryForObject("SELECT count(*) FROM field " + FILTER, parameters, Long.class);
        return new PageImpl<>(fields, page, total);
    }

    public FieldStats stats() {
        List<FieldStats.VarietyShare> varieties = jdbc.query("""
                SELECT NULLIF(BTRIM(variety), '') AS variety_name, count(*) AS field_count,
                    sum(area_mu) AS total_area,
                    COALESCE(round(sum(area_mu) * 100 / NULLIF(sum(sum(area_mu)) OVER (), 0), 2), 0)
                        AS area_percent
                FROM field
                GROUP BY variety_name
                ORDER BY total_area DESC, variety_name
                """, (result, row) -> new FieldStats.VarietyShare(result.getString("variety_name"),
                result.getLong("field_count"), result.getBigDecimal("total_area"),
                result.getBigDecimal("area_percent")));
        long count = varieties.stream().mapToLong(FieldStats.VarietyShare::fieldCount).sum();
        BigDecimal area = varieties.stream().map(FieldStats.VarietyShare::areaMu)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FieldStats(count, area, varieties);
    }

    private MapSqlParameterSource parameters(FieldRequest field) {
        return new MapSqlParameterSource()
                .addValue("name", field.name()).addValue("geometry", field.geometry().toString())
                .addValue("soilType", field.soilType()).addValue("variety", field.variety())
                .addValue("sowDate", field.sowDate()).addValue("ownerName", field.ownerName())
                .addValue("remark", field.remark());
    }

    private Field mapField(ResultSet result, int row) throws SQLException {
        JsonNode geometry;
        try {
            geometry = json.readTree(result.getString("geometry"));
        } catch (IOException exception) {
            throw new SQLException("Database returned invalid field GeoJSON", exception);
        }
        return new Field(result.getLong("id"), result.getString("name"), geometry,
                result.getBigDecimal("area_mu"), result.getString("soil_type"),
                result.getString("variety"), result.getObject("sow_date", LocalDate.class),
                result.getString("owner_name"), result.getString("remark"),
                result.getObject("created_at", OffsetDateTime.class),
                result.getObject("updated_at", OffsetDateTime.class));
    }
}
