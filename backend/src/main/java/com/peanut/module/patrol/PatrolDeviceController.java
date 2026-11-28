package com.peanut.module.patrol;

import static com.peanut.module.patrol.PatrolDeviceRequests.*;

import java.util.Map;
import com.peanut.common.ApiResult;
import com.peanut.module.iot.IotAccess;
import com.peanut.module.iot.IotAccess.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.web.bind.annotation.*;

@RestController
class PatrolDeviceController {
    private final PatrolDeviceService devices;
    private final IotAccess access;

    PatrolDeviceController(PatrolDeviceService devices, IotAccess access) {
        this.devices = devices;
        this.access = access;
    }

    @GetMapping("/api/patrol/devices")
    ApiResult<Page<PatrolDevice>> list(@RequestParam(required=false) String deviceType,
            @RequestParam(required=false) Long fieldId, @RequestParam(required=false) String status,
            @RequestParam(required=false) Boolean online, @RequestParam(required=false) String keyword,
            @RequestParam(required=false) Long taskFieldId, @RequestParam(required=false) String source,
            @RequestParam(required=false) Long groupId, Pageable page, HttpServletRequest request) {
        var actor = access.actor(request);
        if (fieldId != null) actor.checkField(fieldId);
        if (taskFieldId != null) actor.checkField(taskFieldId);
        return ApiResult.ok(devices.list(deviceType,fieldId,status,online,keyword,taskFieldId,source,groupId,actor.fields(),page));
    }

    @GetMapping("/api/patrol/devices/stats")
    ApiResult<Map<String,Object>> stats(HttpServletRequest request) {
        return ApiResult.ok(devices.stats(access.actor(request).fields()));
    }

    @GetMapping("/api/patrol/devices/{id}")
    ApiResult<PatrolDevice> get(@PathVariable long id, HttpServletRequest request) {
        var actor = access.actor(request);
        PatrolDevice device = devices.get(id);
        actor.checkField(device.fieldId());
        return ApiResult.ok(device);
    }

    @PostMapping("/api/patrol/devices")
    ApiResult<PatrolDevice> create(@Valid @RequestBody DeviceInput input, HttpServletRequest request) {
        var actor = access.actor(request);
        actor.require(Role.OPERATOR);
        if ("simulated".equals(input.accessMode())) actor.require(Role.ADMIN);
        actor.checkField(input.fieldId());
        if (input.parentDeviceId() != null) actor.checkField(devices.get(input.parentDeviceId()).fieldId());
        return ApiResult.ok(devices.create(input,actor.name()));
    }

    @PutMapping("/api/patrol/devices/{id}")
    ApiResult<PatrolDevice> update(@PathVariable long id, @Valid @RequestBody DeviceInput input, HttpServletRequest request) {
        var actor = authorized(request,id,Role.OPERATOR);
        if ("simulated".equals(devices.get(id).accessMode())) actor.require(Role.ADMIN);
        actor.checkField(input.fieldId());
        if (input.parentDeviceId() != null) actor.checkField(devices.get(input.parentDeviceId()).fieldId());
        return ApiResult.ok(devices.update(id,input,actor.name()));
    }

    @PutMapping("/api/patrol/devices/{id}/status")
    ApiResult<PatrolDevice> status(@PathVariable long id, @Valid @RequestBody StatusInput input, HttpServletRequest request) {
        var actor = authorized(request,id,Role.OPERATOR);
        if ("simulated".equals(devices.get(id).accessMode())) actor.require(Role.ADMIN);
        return ApiResult.ok(devices.setStatus(id,input.status(),actor.name()));
    }

    @DeleteMapping("/api/patrol/devices/{id}")
    ApiResult<PatrolDevice> delete(@PathVariable long id, HttpServletRequest request) {
        return retire(id,null,request);
    }

    @PostMapping("/api/patrol/devices/{id}/retire")
    ApiResult<PatrolDevice> retire(@PathVariable long id, @Valid @RequestBody(required=false) Retirement input,
            HttpServletRequest request) {
        var actor = authorized(request,id,Role.ADMIN);
        return ApiResult.ok(devices.retire(id,input == null ? null : input.reason(),actor.name()));
    }

    @PostMapping("/api/patrol/devices/{id}/enable")
    ApiResult<PatrolDevice> enable(@PathVariable long id, HttpServletRequest request) {
        return status(id,new StatusInput("active"),request);
    }

    @PostMapping("/api/patrol/devices/{id}/disable")
    ApiResult<PatrolDevice> disable(@PathVariable long id, HttpServletRequest request) {
        return status(id,new StatusInput("inactive"),request);
    }

    @GetMapping("/api/patrol/devices/{id}/maintenance")
    ApiResult<Page<Maintenance>> maintenance(@PathVariable long id, Pageable page, HttpServletRequest request) {
        authorized(request,id,Role.VIEWER);
        return ApiResult.ok(devices.maintenance(id,page));
    }

    @PostMapping("/api/patrol/devices/{id}/maintenance")
    ApiResult<Maintenance> addMaintenance(@PathVariable long id, @Valid @RequestBody MaintenanceInput input, HttpServletRequest request) {
        authorized(request,id,Role.OPERATOR);
        return ApiResult.ok(devices.addMaintenance(id,input));
    }

    @GetMapping("/internal/patrol/sensors/{sn}/binding")
    Map<String,Long> binding(@PathVariable String sn) {
        return Map.of("fieldId",devices.activeSensorField(sn));
    }

    private IotAccess.Actor authorized(HttpServletRequest request, long id, Role role) {
        var actor = access.actor(request);
        actor.require(role);
        actor.checkField(devices.get(id).fieldId());
        return actor;
    }

    record Retirement(@Size(max=1000) String reason) {}
}
