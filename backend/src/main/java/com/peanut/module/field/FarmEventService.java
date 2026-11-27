package com.peanut.module.field;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class FarmEventService {
    private final FieldRepository fields;
    private final FarmEventRepository events;

    public FarmEventService(FieldRepository fields, FarmEventRepository events) {
        this.fields = fields;
        this.events = events;
    }

    public List<FarmEvent> list(long fieldId) {
        if (!fields.exists(fieldId)) {
            throw FieldService.fieldNotFound(fieldId);
        }
        return events.findByFieldId(fieldId);
    }

    @Transactional
    public FarmEvent create(long fieldId, FarmEventRequest event) {
        if (!fields.lockForEvent(fieldId)) {
            throw FieldService.fieldNotFound(fieldId);
        }
        return events.insert(fieldId, event);
    }
}
