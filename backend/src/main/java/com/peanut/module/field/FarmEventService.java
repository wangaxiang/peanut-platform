package com.peanut.module.field;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

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

    @Transactional(readOnly = true)
    public Page<FarmEvent> list(long fieldId, Pageable pageable) {
        if (!fields.existsById(fieldId)) {
            throw FieldService.fieldNotFound(fieldId);
        }
        return events.findByFieldId(fieldId, PageRequest.of(pageable.getPageNumber(), pageable.getPageSize(),
                Sort.by(Sort.Direction.DESC, "eventDate", "id")));
    }

    @Transactional
    public FarmEvent create(long fieldId, FarmEventRequest event) {
        if (fields.lockForEvent(fieldId).isEmpty()) {
            throw FieldService.fieldNotFound(fieldId);
        }
        return events.insert(fieldId, event);
    }
}
