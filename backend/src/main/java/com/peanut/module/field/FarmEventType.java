package com.peanut.module.field;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

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
}
