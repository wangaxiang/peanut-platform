package com.peanut.module.patrol;

import java.time.Instant;
import java.util.List;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.DecimalMax;

public final class PatrolDeviceRequests {
    private PatrolDeviceRequests() {}

    public record DeviceInput(@NotBlank @Pattern(regexp = "[a-z][a-z0-9_]{0,63}") String deviceType,
            @NotBlank @Pattern(regexp = "[A-Za-z0-9_-]{1,64}") String deviceSn,
            @NotBlank @Size(max = 100) String deviceName, @Size(max = 80) String brand,
            @Size(max = 100) String model, @Positive Long fieldId, @Positive Long parentDeviceId,
            JsonNode capabilities, Instant installedAt, @Size(max = 4000) String remark,
            @Pattern(regexp="real|simulated") String accessMode,
            @Pattern(regexp="mqtt|http|lorawan_gateway|simulator|vendor_adapter|other") String protocol,
            @Size(max=200) String installLocation, @Size(max=80) String firmwareVersion,
            @Min(5) @Max(86400) Integer sampleIntervalSeconds,
            @DecimalMin("-180") @DecimalMax("180") Double longitude,
            @DecimalMin("-90") @DecimalMax("90") Double latitude,
            @Size(max=64) List<String> metricCodes, @Size(max=20) List<@NotBlank @Size(max=40) String> tags) {}

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
