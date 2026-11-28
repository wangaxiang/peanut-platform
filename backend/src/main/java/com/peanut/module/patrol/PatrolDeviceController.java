package com.peanut.module.patrol;

import static com.peanut.module.patrol.PatrolDeviceRequests.*;

import java.util.Map;
import com.peanut.common.ApiResult;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
class PatrolDeviceController {
    private final PatrolDeviceService devices;

    PatrolDeviceController(PatrolDeviceService devices) {
        this.devices = devices;
    }

    @GetMapping("/api/patrol/devices")
    ApiResult<Page<PatrolDevice>> list(@RequestParam(required=false) String deviceType,
            @RequestParam(required=false) Long fieldId, @RequestParam(required=false) String status,
            @RequestParam(required=false) Boolean online, @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Long taskFieldId, Pageable page) {
        return ApiResult.ok(devices.list(deviceType,fieldId,status,online,keyword,taskFieldId,page));
    }

    @GetMapping("/api/patrol/devices/stats")
    ApiResult<Map<String,Object>> stats() {
        return ApiResult.ok(devices.stats());
    }

    @GetMapping("/api/patrol/devices/{id}")
    ApiResult<PatrolDevice> get(@PathVariable long id) {
        return ApiResult.ok(devices.get(id));
    }

    @PostMapping("/api/patrol/devices")
    ApiResult<PatrolDevice> create(@Valid @RequestBody DeviceInput input) {
        return ApiResult.ok(devices.create(input));
    }

    @PutMapping("/api/patrol/devices/{id}")
    ApiResult<PatrolDevice> update(@PathVariable long id, @Valid @RequestBody DeviceInput input) {
        return ApiResult.ok(devices.update(id,input));
    }

    @PutMapping("/api/patrol/devices/{id}/status")
    ApiResult<PatrolDevice> status(@PathVariable long id, @Valid @RequestBody StatusInput input) {
        return ApiResult.ok(devices.setStatus(id,input.status()));
    }

    @DeleteMapping("/api/patrol/devices/{id}")
    ApiResult<PatrolDevice> retire(@PathVariable long id) {
        return ApiResult.ok(devices.retire(id));
    }

    @GetMapping("/api/patrol/devices/{id}/maintenance")
    ApiResult<Page<Maintenance>> maintenance(@PathVariable long id, Pageable page) {
        return ApiResult.ok(devices.maintenance(id,page));
    }

    @PostMapping("/api/patrol/devices/{id}/maintenance")
    ApiResult<Maintenance> addMaintenance(@PathVariable long id, @Valid @RequestBody MaintenanceInput input) {
        return ApiResult.ok(devices.addMaintenance(id,input));
    }

    @GetMapping("/internal/patrol/sensors/{sn}/binding")
    Map<String,Long> binding(@PathVariable String sn) {
        return Map.of("fieldId",devices.activeSensorField(sn));
    }
}
