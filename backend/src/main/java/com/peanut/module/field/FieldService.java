package com.peanut.module.field;

import java.time.Clock;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FieldService {
    private final FieldRepository fields;
    private final Clock clock;

    public FieldService(FieldRepository fields, Clock clock) {
        this.fields = fields;
        this.clock = clock;
    }

    public FieldResponse create(FieldRequest request) {
        validatePolygon(request.geometry());
        Field field = fields.insert(request).orElseThrow(FieldService::invalidPolygon);
        return response(field, LocalDate.now(clock));
    }

    public FieldResponse update(long id, FieldRequest request) {
        validatePolygon(request.geometry());
        Field field = fields.update(id, request).orElseThrow(() ->
                fields.exists(id) ? invalidPolygon() : fieldNotFound(id));
        return response(field, LocalDate.now(clock));
    }

    public FieldResponse get(long id) {
        Field field = fields.findById(id).orElseThrow(() -> fieldNotFound(id));
        return response(field, LocalDate.now(clock));
    }

    public Page<FieldResponse> list(String keyword, Pageable pageable) {
        LocalDate today = LocalDate.now(clock);
        return fields.search(keyword.strip(), pageable).map(field -> response(field, today));
    }

    public void delete(long id) {
        if (!fields.delete(id)) {
            throw fieldNotFound(id);
        }
    }

    public FieldStats stats() {
        return fields.stats();
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

    private FieldResponse response(Field field, LocalDate today) {
        Long days = daysAfterSow(field.sowDate(), today);
        return new FieldResponse(field, growthStage(days), days);
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

    private void validatePolygon(JsonNode geometry) {
        JsonNode coordinates = geometry.path("coordinates");
        if (!"Polygon".equals(geometry.path("type").asText()) || !coordinates.isArray()
                || coordinates.isEmpty() || geometry.has("crs")) {
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
            JsonNode start = ring.get(0);
            JsonNode end = ring.get(ring.size() - 1);
            if (start.get(0).doubleValue() != end.get(0).doubleValue()
                    || start.get(1).doubleValue() != end.get(1).doubleValue()) {
                throw invalidPolygon();
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
