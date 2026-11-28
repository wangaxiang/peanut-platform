package com.peanut.module.patrol;

import java.time.Instant;

import com.fasterxml.jackson.databind.JsonNode;

public record PatrolFlight(long id, long taskId, String taskName, long fieldId, String fieldName,
        String status, Instant startTime, Instant endTime, Instant scheduledFor, Instant createdAt,
        String rawImagePath, String ndviImagePath, String ndviTileUrl, JsonNode ndviStats, String errorMessage) {}
