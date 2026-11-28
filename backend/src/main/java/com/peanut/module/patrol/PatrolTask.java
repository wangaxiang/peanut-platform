package com.peanut.module.patrol;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record PatrolTask(long id, long fieldId, String fieldName, String taskName, String scheduleCron,
        String droneSn, JsonNode routeGeojson, String status, Instant createdAt) {}
