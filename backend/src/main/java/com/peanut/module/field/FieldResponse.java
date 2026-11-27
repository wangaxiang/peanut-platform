package com.peanut.module.field;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.databind.JsonNode;

public record FieldResponse(Long id, String name, JsonNode geometry, BigDecimal areaMu, String soilType,
                            String variety, LocalDate sowDate, String ownerName, String remark,
                            OffsetDateTime createdAt, OffsetDateTime updatedAt,
                            String currentGrowthStage, Long daysAfterSow) {
}
