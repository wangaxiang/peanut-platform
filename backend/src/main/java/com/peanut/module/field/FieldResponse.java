package com.peanut.module.field;

import com.fasterxml.jackson.annotation.JsonUnwrapped;

public record FieldResponse(@JsonUnwrapped Field field, String currentGrowthStage, Long daysAfterSow) {
}
