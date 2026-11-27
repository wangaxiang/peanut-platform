package com.peanut.module.field;

import com.peanut.common.ApiResult;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/fields")
public class FieldController {
    private final FieldService fields;

    public FieldController(FieldService fields) {
        this.fields = fields;
    }

    @PostMapping
    public ApiResult<FieldResponse> create(@Valid @RequestBody FieldRequest request) {
        return ApiResult.ok(fields.create(request));
    }

    @GetMapping
    public ApiResult<Page<FieldResponse>> list(@RequestParam(defaultValue = "") String keyword,
            @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC) Pageable pageable) {
        return ApiResult.ok(fields.list(keyword, pageable));
    }

    @GetMapping("/{id}")
    public ApiResult<FieldResponse> get(@PathVariable long id) {
        return ApiResult.ok(fields.get(id));
    }

    @PutMapping("/{id}")
    public ApiResult<FieldResponse> update(@PathVariable long id, @Valid @RequestBody FieldRequest request) {
        return ApiResult.ok(fields.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ApiResult<Void> delete(@PathVariable long id) {
        fields.delete(id);
        return ApiResult.ok(null);
    }

    @GetMapping("/stats")
    public ApiResult<FieldStats> stats() {
        return ApiResult.ok(fields.stats());
    }
}
