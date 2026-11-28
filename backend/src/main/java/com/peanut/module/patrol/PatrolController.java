package com.peanut.module.patrol;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;

import com.peanut.common.ApiResult;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/patrol")
class PatrolController {
    private final PatrolRepository repository;
    private final PatrolService patrol;
    private final PatrolImages images;

    PatrolController(PatrolRepository repository, PatrolService patrol, PatrolImages images) {
        this.repository = repository;
        this.patrol = patrol;
        this.images = images;
    }

    @PostMapping("/tasks")
    ApiResult<PatrolTask> create(@Valid @RequestBody PatrolService.CreateTask input) {
        return ApiResult.ok(patrol.create(input));
    }

    @GetMapping("/tasks")
    ApiResult<Page<PatrolTask>> tasks(@RequestParam(required = false) Long fieldId, Pageable page) {
        return ApiResult.ok(repository.tasks(fieldId, page));
    }

    @PutMapping("/tasks/{id}/status")
    ApiResult<PatrolTask> status(@PathVariable long id, @RequestBody @Valid TaskStatus input) {
        return ApiResult.ok(patrol.setStatus(id, input.status()));
    }

    @PutMapping("/tasks/{id}/drone")
    ApiResult<PatrolTask> mapLegacyTask(@PathVariable long id, @RequestBody @Valid LegacyDrone input) {
        return ApiResult.ok(patrol.mapLegacyTask(id,input.droneDeviceId()));
    }

    @PostMapping("/tasks/{id}/flights")
    ApiResult<PatrolFlight> newFlight(@PathVariable long id) {
        return ApiResult.ok(patrol.newFlight(id));
    }

    @GetMapping("/flights")
    ApiResult<Page<PatrolFlight>> flights(@RequestParam(required = false) Long fieldId,
            @RequestParam(defaultValue = "false") boolean ndviOnly, Pageable page) {
        return ApiResult.ok(repository.flights(fieldId,ndviOnly,page));
    }

    @GetMapping("/flights/{id}")
    ApiResult<PatrolFlight> flight(@PathVariable long id) {
        return ApiResult.ok(patrol.flight(id));
    }

    @PostMapping("/flights/{id}/trigger")
    ApiResult<PatrolFlight> trigger(@PathVariable long id) {
        return ApiResult.ok(patrol.trigger(id));
    }

    @PostMapping(path = "/flights/{id}/image", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    ApiResult<PatrolFlight> upload(@PathVariable long id, @RequestPart("file") MultipartFile file) throws IOException {
        return ApiResult.ok(images.attach(id, file));
    }

    @GetMapping("/flights/{id}/ndvi/tilejson")
    Map<String, Object> tileJson(@PathVariable long id) {
        return images.tileJson(id);
    }

    @GetMapping("/flights/{id}/ndvi/tiles/{z}/{x}/{y}.png")
    ResponseEntity<byte[]> tile(@PathVariable long id, @PathVariable int z, @PathVariable int x, @PathVariable int y) {
        return ResponseEntity.ok().contentType(MediaType.IMAGE_PNG).cacheControl(CacheControl.maxAge(Duration.ofHours(1)))
                .body(images.tile(id, z, x, y));
    }

    record TaskStatus(@NotBlank @Pattern(regexp = "active|paused") String status) {}
    record LegacyDrone(@NotNull @Positive Long droneDeviceId) {}
}
