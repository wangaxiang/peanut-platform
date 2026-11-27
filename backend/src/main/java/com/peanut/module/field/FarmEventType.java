package com.peanut.module.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

public enum FarmEventType {
    SOW("sow"),
    FERTILIZE("fertilize"),
    SPRAY("spray"),
    HARVEST("harvest"),
    IRRIGATE("irrigate");

    private final String value;

    FarmEventType(String value) {
        this.value = value;
    }

    @JsonValue
    public String value() {
        return value;
    }

    @JsonCreator
    public static FarmEventType fromValue(String value) {
        for (FarmEventType type : values()) {
            if (type.value.equals(value)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown farm event type");
    }

    @Converter
    public static class DatabaseValue implements AttributeConverter<FarmEventType, String> {
        @Override
        public String convertToDatabaseColumn(FarmEventType value) {
            return value == null ? null : value.value();
        }

        @Override
        public FarmEventType convertToEntityAttribute(String value) {
            return value == null ? null : fromValue(value);
        }
    }
}
