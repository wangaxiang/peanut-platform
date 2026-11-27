package com.peanut.module.field;

import java.math.BigDecimal;
import java.util.List;

public record FieldStats(long totalFields, BigDecimal totalAreaMu, List<VarietyShare> varieties) {
    public record VarietyShare(String variety, long fieldCount, BigDecimal areaMu, BigDecimal areaPercent) {
    }
}
