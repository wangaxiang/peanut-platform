package com.peanut.module.patrol;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;

public record PatrolDevice(long id, String deviceType, String deviceSn, String deviceName, String brand, String model,
        Long fieldId, String fieldName, Long parentDeviceId, String parentDeviceName, String parentDeviceSn,
        Boolean parentOnline, String status, boolean online, Instant lastSeenAt, String currentPhase,
        JsonNode lastStatus, Double batteryPct, Double longitude, Double latitude, Double altitudeM,
        String firmwareVersion, JsonNode capabilities, String faultCode, String faultMessage, Instant installedAt,
        Instant lastMaintenanceAt, Instant nextMaintenanceAt, String remark, Instant createdAt, Instant updatedAt) {}
