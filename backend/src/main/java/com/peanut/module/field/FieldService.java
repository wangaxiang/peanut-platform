package com.peanut.module.field;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.locationtech.jts.geom.Geometry;
import org.locationtech.jts.geom.Polygon;
import org.locationtech.jts.io.ParseException;
import org.locationtech.jts.io.WKBWriter;
import org.locationtech.jts.io.geojson.GeoJsonReader;
import org.locationtech.jts.io.geojson.GeoJsonWriter;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
@Transactional(readOnly = true)
public class FieldService {
    private static final Set<String> SORT_PROPERTIES = Set.of("id", "name", "areaMu", "soilType",
            "variety", "sowDate", "ownerName", "createdAt", "updatedAt");

    private final FieldRepository fields;
    private final Clock clock;
    private final ObjectMapper json;

    public FieldService(FieldRepository fields, Clock clock, ObjectMapper json) {
        this.fields = fields;
        this.clock = clock;
        this.json = json;
    }

    @Transactional
    public FieldVO create(FieldCreateRequest request) {
        Field field = new Field();
        apply(field, request);
        return response(fields.save(field), LocalDate.now(clock));
    }

    @Transactional
    public FieldVO update(long id, FieldCreateRequest request) {
        Field field = requireField(id);
        apply(field, request);
        return response(field, LocalDate.now(clock));
    }

    public FieldVO get(long id) {
        return response(requireField(id), LocalDate.now(clock));
    }

    public Page<FieldVO> list(String keyword, Pageable pageable) {
        for (Sort.Order order : pageable.getSort()) {
            if (!SORT_PROPERTIES.contains(order.getProperty())) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unsupported sort field: " + order.getProperty());
            }
        }
        String escaped = keyword.strip().replace("!", "!!").replace("%", "!%").replace("_", "!_");
        Sort sort = pageable.getSort();
        if (sort.getOrderFor("id") == null) {
            sort = sort.and(Sort.by(Sort.Direction.DESC, "id"));
        }
        Pageable sorted = PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(), sort);
        LocalDate today = LocalDate.now(clock);
        return fields.search("%" + escaped + "%", sorted).map(field -> response(field, today));
    }

    @Transactional
    public void delete(long id) {
        fields.delete(requireField(id));
        fields.flush();
    }

    public FieldStats stats() {
        List<FieldStats.VarietyStats> varieties = fields.summarizeByVariety().stream()
                .map(row -> new FieldStats.VarietyStats(row.getVariety(), row.getCount(), row.getAreaMu()))
                .toList();
        long count = varieties.stream().mapToLong(FieldStats.VarietyStats::count).sum();
        BigDecimal area = varieties.stream().map(FieldStats.VarietyStats::areaMu)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new FieldStats(count, area, varieties);
    }

    public String calcGrowthStage(LocalDate sowDate) {
        return growthStage(calcDaysAfterSow(sowDate));
    }

    public Long calcDaysAfterSow(LocalDate sowDate) {
        return daysAfterSow(sowDate, LocalDate.now(clock));
    }

    static ResponseStatusException fieldNotFound(long id) {
        return new ResponseStatusException(HttpStatus.NOT_FOUND, "Field " + id + " was not found");
    }

    private Field requireField(long id) {
        return fields.findById(id).orElseThrow(() -> fieldNotFound(id));
    }

    private void apply(Field field, FieldCreateRequest request) {
        Polygon polygon = parsePolygon(request.geometry());
        BigDecimal area = fields.calculateAreaMu(new WKBWriter(2, true).write(polygon))
                .setScale(2, RoundingMode.HALF_UP);
        if (area.precision() > 10) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Field area exceeds the supported range");
        }
        field.update(request, polygon, area, OffsetDateTime.now(clock));
    }

    private FieldVO response(Field field, LocalDate today) {
        GeoJsonWriter writer = new GeoJsonWriter(15);
        writer.setEncodeCRS(false);
        JsonNode geometry;
        try {
            geometry = json.readTree(writer.write(field.geometry));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("Could not serialize field geometry", exception);
        }
        Long days = daysAfterSow(field.sowDate, today);
        return new FieldVO(field.id, field.name, geometry, field.areaMu, field.soilType,
                field.variety, field.sowDate, field.ownerName, field.remark, field.createdAt,
                field.updatedAt, growthStage(days), days);
    }

    private Long daysAfterSow(LocalDate sowDate, LocalDate today) {
        return sowDate == null ? null : ChronoUnit.DAYS.between(sowDate, today);
    }

    private String growthStage(Long days) {
        if (days == null || days < 0) {
            return "未播种";
        }
        if (days < 25) {
            return "苗期";
        }
        if (days < 55) {
            return "花针期";
        }
        if (days < 85) {
            return "结荚期";
        }
        if (days < 120) {
            return "饱果期";
        }
        return "收获期";
    }

    private Polygon parsePolygon(String input) {
        try {
            JsonNode geometry = json.readTree(input);
            validateCoordinates(geometry);
            Geometry parsed = new GeoJsonReader().read(geometry.toString());
            if (!(parsed instanceof Polygon polygon) || polygon.isEmpty() || !polygon.isValid()) {
                throw invalidPolygon();
            }
            polygon.setSRID(4326);
            return polygon;
        } catch (JsonProcessingException | ParseException | IllegalArgumentException exception) {
            throw invalidPolygon();
        }
    }

    private void validateCoordinates(JsonNode geometry) {
        if (geometry == null || geometry.has("crs") || !"Polygon".equals(geometry.path("type").asText())) {
            throw invalidPolygon();
        }
        JsonNode coordinates = geometry.path("coordinates");
        if (!coordinates.isArray() || coordinates.isEmpty()) {
            throw invalidPolygon();
        }
        for (JsonNode ring : coordinates) {
            if (!ring.isArray() || ring.size() < 4) {
                throw invalidPolygon();
            }
            for (JsonNode point : ring) {
                if (!point.isArray() || point.size() != 2
                        || !withinBounds(point.get(0), 180) || !withinBounds(point.get(1), 90)) {
                    throw invalidPolygon();
                }
            }
        }
    }

    private boolean withinBounds(JsonNode coordinate, int limit) {
        return coordinate.isNumber() && Math.abs(coordinate.doubleValue()) <= limit;
    }

    private static ResponseStatusException invalidPolygon() {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "geometry must be a valid WGS84 GeoJSON Polygon with closed two-dimensional rings");
    }
}
