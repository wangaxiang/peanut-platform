package com.peanut.module.field;

import com.peanut.common.ApiResult;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fields/{id}/events")
public class FarmEventController {
    private final FarmEventService events;

    public FarmEventController(FarmEventService events) {
        this.events = events;
    }

    @GetMapping
    public ApiResult<Page<FarmEvent>> list(@PathVariable long id, Pageable pageable) {
        return ApiResult.ok(events.list(id, pageable));
    }

    @PostMapping
    public ApiResult<FarmEvent> create(@PathVariable long id, @Valid @RequestBody FarmEventRequest request) {
        return ApiResult.ok(events.create(id, request));
    }
}
