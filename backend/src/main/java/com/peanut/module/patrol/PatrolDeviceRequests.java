package com.peanut.module.patrol;

import java.time.Instant;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

final class PatrolDeviceRequests {
    private PatrolDeviceRequests() {}

    record DeviceInput(@NotBlank @Pattern(regexp = "drone|dock|sensor") String deviceType,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String deviceSn,
            @NotBlank @Size(max = 100) String deviceName, @Size(max = 80) String brand,
            @Size(max = 100) String model, @Positive Long fieldId, @Positive Long parentDeviceId,
            JsonNode capabilities, Instant installedAt, @Size(max = 4000) String remark) {}

    record StatusInput(@NotBlank @Pattern(regexp = "active|inactive|maintenance") String status) {}

    record MaintenanceInput(@NotBlank @Pattern(regexp = "inspection|repair|firmware|battery|calibration|other")
            String maintenanceType, @NotBlank @Pattern(regexp = "planned|in_progress|completed|cancelled") String status,
            @NotBlank @Size(max = 4000) String description, @Size(max = 80) String operatorName,
            Instant startedAt, Instant completedAt, Instant nextMaintenanceAt) {}

    record Maintenance(long id, long deviceId, String maintenanceType, String status, String description,
            String operatorName, Instant startedAt, Instant completedAt, Instant nextMaintenanceAt, Instant createdAt) {}

    record Totals(long total, long online, long offline, long maintenance) {
        Totals plus(Totals other) {
            return new Totals(total + other.total, online + other.online, offline + other.offline, maintenance + other.maintenance);
        }
    }
}
