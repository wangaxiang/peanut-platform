package com.peanut.module.field;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

public record FarmEvent(Long id, Long fieldId, FarmEventType eventType, LocalDate eventDate, String operator,
                        BigDecimal amount, String unit, String remark, OffsetDateTime createdAt) {
}
