package com.peanut.module.field;

import java.util.List;

import com.peanut.common.ApiResult;
import jakarta.validation.Valid;
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
    public ApiResult<List<FarmEvent>> list(@PathVariable long id) {
        return ApiResult.ok(events.list(id));
    }

    @PostMapping
    public ApiResult<FarmEvent> create(@PathVariable long id, @Valid @RequestBody FarmEventRequest request) {
        return ApiResult.ok(events.create(id, request));
    }
}
