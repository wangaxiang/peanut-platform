package com.peanut.module.field;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.OffsetDateTime;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonAutoDetect.Visibility;
import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;

@Entity
@Table(name = "farm_event")
@JsonAutoDetect(fieldVisibility = Visibility.ANY)
public class FarmEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long fieldId;

    @Column(nullable = false)
    @Convert(converter = FarmEventType.DatabaseValue.class)
    private FarmEventType eventType;

    @Column(nullable = false)
    private LocalDate eventDate;

    private String operator;
    private BigDecimal amount;
    private String unit;
    private String remark;

    @CreationTimestamp
    private OffsetDateTime createdAt;

    protected FarmEvent() {
    }

    FarmEvent(long fieldId, FarmEventRequest request) {
        this.fieldId = fieldId;
        this.eventType = request.eventType();
        this.eventDate = request.eventDate();
        this.operator = request.operator();
        this.amount = request.amount();
        this.unit = request.unit() == null ? null : request.unit().strip();
        this.remark = request.remark();
    }
}
