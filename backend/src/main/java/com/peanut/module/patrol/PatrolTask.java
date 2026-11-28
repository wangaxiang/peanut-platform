package com.peanut.module.patrol;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record PatrolTask(long id, long fieldId, String fieldName, String taskName, String scheduleCron,
        Instant scheduledAt, String droneSn, JsonNode routeGeojson, String status, Instant createdAt,
        Long droneDeviceId, String droneName, Boolean droneOnline, String dockName, boolean requiresDock) {}
