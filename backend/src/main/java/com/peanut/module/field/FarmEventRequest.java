package com.peanut.module.field;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

public record FarmEventRequest(
        @NotBlank @Pattern(regexp = "sow|fertilize|spray|harvest|irrigate") String eventType,
        @NotNull LocalDate eventDate,
        @Size(max = 50) String operator,
        @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        @Size(max = 20) String unit,
        String remark) {
}
