package com.peanut.module.field;

import java.time.LocalDate;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record FieldCreateRequest(
        @NotBlank @Size(max = 100) String name,
        @NotBlank String geometry,
        @Size(max = 50) String soilType,
        @Size(max = 100) String variety,
        LocalDate sowDate,
        @Size(max = 50) String ownerName,
        String remark) {
}
