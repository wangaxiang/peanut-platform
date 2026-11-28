package com.peanut.module.iot;

import java.util.List;
import java.util.Map;
import com.peanut.common.ApiResult;
import com.peanut.module.patrol.PatrolDeviceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/iot/simulator")
class SimulatorController {
    private final SimulatorService simulator;
    private final IotAccess access;
    private final IotInflux influx;
    private final PatrolDeviceService devices;
    private final JdbcClient jdbc;

    SimulatorController(SimulatorService simulator,IotAccess access,IotInflux influx,PatrolDeviceService devices,JdbcClient jdbc) {
        this.simulator=simulator;
        this.access=access;
        this.influx=influx;
        this.devices=devices;
        this.jdbc=jdbc;
    }

    @GetMapping("/templates")
    ApiResult<Map<String,Object>> templates(HttpServletRequest request) {
        access.actor(request);
        return ApiResult.ok(Map.of("templates",simulator.templates(),"scenarios",SimulatorService.SCENARIOS));
    }

    @GetMapping("/devices")
    ApiResult<List<SimulatorService.Entry>> list(HttpServletRequest request) {
        return ApiResult.ok(simulator.list(access.actor(request)));
    }

    @PostMapping("/devices")
    ApiResult<SimulatorService.Entry> create(HttpServletRequest request,@Valid @RequestBody SimulatorService.NewSimulator input) {
        return ApiResult.ok(simulator.create(input,access.actor(request)));
    }

    @PutMapping("/devices/{id}/profile")
    ApiResult<SimulatorService.Entry> configure(@PathVariable long id,HttpServletRequest request,
            @Valid @RequestBody SimulatorService.Settings settings) {
        return ApiResult.ok(simulator.configure(id,settings,access.actor(request)));
    }

    @PostMapping("/devices/{id}/{action:start|pause|resume|stop|reset|inject}")
    ApiResult<SimulatorService.Entry> control(@PathVariable long id,@PathVariable String action,HttpServletRequest request,
            @RequestBody(required=false) SimulatorService.Control input) {
        return ApiResult.ok(simulator.control(id,action,input,access.actor(request)));
    }

    @PostMapping("/scenarios/{code}/run")
    ApiResult<List<SimulatorService.Entry>> run(@PathVariable String code,HttpServletRequest request,
            @Valid @RequestBody Batch input) {
        var actor=access.actor(request);
        actor.require(IotAccess.Role.ADMIN);
        for (long id:input.deviceIds()) {
            actor.checkField(devices.get(id).fieldId());
            simulator.profile(id,false);
        }
        return ApiResult.ok(input.deviceIds().stream().distinct()
                .map(id -> simulator.control(id,"start",new SimulatorService.Control(code,null,null,null),actor)).toList());
    }

    @GetMapping("/devices/{id}/preview")
    ApiResult<Map<String,Object>> preview(@PathVariable long id,HttpServletRequest request) {
        access.actor(request).checkField(devices.get(id).fieldId());
        simulator.profile(id,false);
        var events=jdbc.sql("""
                SELECT event_type,summary,created_at FROM iot_device_event WHERE device_id=:id AND event_type LIKE 'simulator_%'
                ORDER BY id DESC LIMIT 15
                """).param("id",id).query((rs,n) -> Map.of("eventType",rs.getString(1),
                        "summary",rs.getString(2),"time",rs.getObject(3,java.time.OffsetDateTime.class).toInstant())).list();
        return ApiResult.ok(Map.of("points",influx.recent(id),"events",events));
    }

    record Batch(@NotEmpty @Size(max=20) List<@jakarta.validation.constraints.NotNull Long> deviceIds) {}
}
