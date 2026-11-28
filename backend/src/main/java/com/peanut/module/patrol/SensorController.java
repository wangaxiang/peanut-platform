package com.peanut.module.patrol;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

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

    SensorController(SensorHistory sensors) {
        this.sensors = sensors;
    }

    @GetMapping
    ApiResult<List<Map<String, Object>>> latest(@RequestParam(required = false) Long fieldId) {
        return ApiResult.ok(sensors.latest(fieldId));
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
        return ApiResult.ok(sensors.history(sn, from, to, interval));
    }
}
