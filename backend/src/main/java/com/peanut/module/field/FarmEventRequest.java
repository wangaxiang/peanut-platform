package com.peanut.module.field;

import java.math.BigDecimal;
import java.time.LocalDate;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record FarmEventRequest(
        @NotNull FarmEventType eventType,
        @NotNull LocalDate eventDate,
        @Size(max = 50) String operator,
        @DecimalMin("0") @Digits(integer = 8, fraction = 2) BigDecimal amount,
        @Size(max = 20) String unit,
        String remark) {
    @AssertTrue(message = "amount and unit must be provided together")
    public boolean isAmountUnitPaired() {
        boolean hasUnit = unit != null && !unit.isBlank();
        return (amount != null) == hasUnit;
    }
}
