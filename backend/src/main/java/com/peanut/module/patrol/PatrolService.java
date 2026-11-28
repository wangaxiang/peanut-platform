package com.peanut.module.patrol;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
class PatrolService {
    private static final Logger LOG = LoggerFactory.getLogger(PatrolService.class);
    private final PatrolRepository repository;
    private final PatrolMqtt mqtt;
    private final ZoneId zone;

    PatrolService(PatrolRepository repository, PatrolMqtt mqtt, @Value("${patrol.zone}") String zone) {
        this.repository = repository;
        this.mqtt = mqtt;
        this.zone = ZoneId.of(zone);
    }

    PatrolTask create(CreateTask input) {
        String cron;
        try {
            PatrolCron.parse(input.scheduleCron());
            cron = String.join(" ", input.scheduleCron().strip().split("\\s+"));
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cron 无效，请输入分、时、日、月、星期");
        }
        validateRoute(input.routeGeojson());
        if (!repository.fieldExists(input.fieldId())) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "地块不存在");
        }
        PatrolTask task = task(repository.insertTask(input, cron));
        mqtt.refreshSubscriptions();
        return task;
    }

    PatrolTask setStatus(long id, String status) {
        if (!repository.updateTask(id, status)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "巡田任务不存在");
        }
        return task(id);
    }

    PatrolFlight newFlight(long taskId) {
        task(taskId);
        return flight(repository.insertFlight(taskId, null).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.CONFLICT, "请先启用任务")));
    }

    PatrolFlight trigger(long id) {
        PatrolFlight flight = flight(id);
        PatrolTask task = task(flight.taskId());
        if (!mqtt.connected()) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "MQTT 未连接，请稍后重试");
        }
        if (!repository.startFlight(id)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "仅启用任务的待起飞记录可下发");
        }
        try {
            mqtt.publish(task, id);
        } catch (Exception e) {
            repository.dispatchFailed(id);
            LOG.warn("MQTT dispatch failed for flight {}", id, e);
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "MQTT 指令未确认，请查询设备状态");
        }
        return flight(id);
    }

    @Scheduled(fixedDelay = 5000)
    void schedule() {
        ZonedDateTime minute = ZonedDateTime.now(zone).truncatedTo(ChronoUnit.MINUTES);
        Instant slot = minute.toInstant();
        for (PatrolTask task : repository.activeTasks()) {
            if (task.scheduleCron() == null || (task.createdAt() != null && task.createdAt().isAfter(slot))) {
                continue;
            }
            try {
                if (PatrolCron.parse(task.scheduleCron()).matches(minute)) {
                    repository.insertFlight(task.id(), slot);
                }
            } catch (IllegalArgumentException e) {
                LOG.warn("Ignoring invalid cron for patrol task {}", task.id());
            }
        }
        if (mqtt.connected()) {
            for (long id : repository.scheduledFlights(slot)) {
                try {
                    trigger(id);
                } catch (ResponseStatusException e) {
                    LOG.debug("Scheduled flight {} could not be dispatched: {}", id, e.getReason());
                }
            }
        }
    }

    PatrolFlight flight(long id) {
        return repository.flight(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "飞行记录不存在"));
    }

    private PatrolTask task(long id) {
        return repository.task(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "巡田任务不存在"));
    }

    private static void validateRoute(JsonNode route) {
        JsonNode features = route.path("features");
        if (!"FeatureCollection".equals(route.path("type").asText()) || !features.isArray()
                || features.size() < 2 || features.size() > 1000) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "航线需包含 2 至 1000 个 GeoJSON 航点");
        }
        for (JsonNode item : features) {
            JsonNode geometry = item.path("geometry");
            JsonNode coordinates = geometry.path("coordinates");
            if (!"Feature".equals(item.path("type").asText()) || !"Point".equals(geometry.path("type").asText())
                    || !coordinates.isArray() || coordinates.size() < 2 || coordinates.size() > 3
                    || !validCoordinate(coordinates.get(0), 180) || !validCoordinate(coordinates.get(1), 90)
                    || (coordinates.size() == 3 && (!coordinates.get(2).isNumber()
                        || !Double.isFinite(coordinates.get(2).asDouble())))) {
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "航线航点必须为有效的 GeoJSON Point");
            }
        }
    }

    private static boolean validCoordinate(JsonNode node, double maximum) {
        return node.isNumber() && Math.abs(node.asDouble()) <= maximum;
    }

    record CreateTask(@NotNull @Positive Long fieldId, @NotBlank @Size(max = 100) String taskName,
            @NotBlank @Size(max = 50) String scheduleCron,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String droneSn, @NotNull JsonNode routeGeojson) {}
}
