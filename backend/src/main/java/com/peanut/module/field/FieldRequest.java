package com.peanut.module.field;

import java.time.LocalDate;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FieldRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull JsonNode geometry,
        @Size(max = 50) String soilType,
        @Size(max = 100) String variety,
        LocalDate sowDate,
        @Size(max = 50) String ownerName,
        String remark) {
}
