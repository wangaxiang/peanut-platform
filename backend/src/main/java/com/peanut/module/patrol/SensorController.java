package com.peanut.module.patrol;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Objects;

import com.peanut.common.ApiResult;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/iot/sensors")
class SensorController {
    private final SensorHistory sensors;
    private final PatrolDeviceService devices;
    private final PatrolDeviceRepository registry;

    SensorController(SensorHistory sensors, PatrolDeviceService devices, PatrolDeviceRepository registry) {
        this.sensors = sensors;
        this.devices = devices;
        this.registry = registry;
    }

    @GetMapping
    ApiResult<List<Map<String, Object>>> latest(@RequestParam(required = false) Long fieldId) {
        var registered = registry.sensors(fieldId);
        var readings = registered.isEmpty() ? List.<Map<String,Object>>of() : sensors.latest(fieldId);
        return ApiResult.ok(registered.stream().map(device -> summary(device,readings)).toList());
    }

    @GetMapping("/{sn}/latest")
    ApiResult<Map<String,Object>> latestBySerial(@PathVariable String sn) {
        PatrolDevice device = devices.sensor(sn);
        return ApiResult.ok(summary(device,sensors.latest(device.fieldId())));
    }

    private static Map<String,Object> summary(PatrolDevice device, List<Map<String,Object>> readings) {
        var result = new LinkedHashMap<String,Object>();
        readings.stream().filter(reading -> device.deviceSn().equals(reading.get("deviceSn"))
                && Objects.equals(device.fieldId(),reading.get("fieldId"))).findFirst().ifPresent(result::putAll);
        result.put("id",device.id());
        result.put("deviceSn",device.deviceSn());
        result.put("deviceName",device.deviceName());
        result.put("fieldId",device.fieldId());
        result.put("fieldName",device.fieldName());
        result.put("online",device.online());
        result.put("status",device.status());
        result.put("batteryPct",device.batteryPct());
        result.put("lastSeenAt",device.lastSeenAt());
        return result;
    }

    @GetMapping("/{sn}/history")
    ApiResult<List<Map<String, Object>>> history(@PathVariable String sn,
            @RequestParam(required = false) Instant start, @RequestParam(required = false) Instant end,
            @RequestParam(defaultValue = "5m") String interval) {
        Instant to = end == null ? Instant.now() : end;
        Instant from = start == null ? to.minus(24, ChronoUnit.HOURS) : start;
        if (!sn.matches("[A-Za-z0-9_-]{1,64}") || !List.of("1m", "5m", "1h").contains(interval)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "序列号无效或聚合间隔不受支持（1m、5m、1h）");
        }
        if (!from.isBefore(to)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "开始时间必须早于结束时间");
        }
        devices.sensor(sn);
        return ApiResult.ok(sensors.history(sn, from, to, interval));
    }
}
