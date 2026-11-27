package com.peanut.module.field;

import java.math.BigDecimal;
import java.util.List;

public record FieldStats(long totalCount, BigDecimal totalAreaMu, List<VarietyStats> varietyStats) {
    public record VarietyStats(String variety, long count, BigDecimal areaMu) {
    }
}
