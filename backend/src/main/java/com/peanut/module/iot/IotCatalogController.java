package com.peanut.module.iot;

import java.util.List;
import com.peanut.common.ApiResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
class IotCatalogController {
    private final IotCatalog catalog;
    private final IotAccess access;

    IotCatalogController(IotCatalog catalog, IotAccess access) {
        this.catalog = catalog;
        this.access = access;
    }

    @GetMapping("/api/iot/session")
    ApiResult<IotAccess.Actor> session(HttpServletRequest request) {
        return ApiResult.ok(access.actor(request));
    }

    @GetMapping("/api/patrol/device-types")
    ApiResult<List<IotCatalog.DeviceType>> types(HttpServletRequest request) {
        access.actor(request);
        return ApiResult.ok(catalog.types());
    }

    @PostMapping("/api/patrol/device-types")
    ApiResult<IotCatalog.DeviceType> createType(HttpServletRequest request, @Valid @RequestBody IotCatalog.TypeInput input) {
        return updateType(null,request,input);
    }

    @PutMapping("/api/patrol/device-types/{code}")
    ApiResult<IotCatalog.DeviceType> updateType(@PathVariable String code, HttpServletRequest request,
            @Valid @RequestBody IotCatalog.TypeInput input) {
        access.actor(request).require(IotAccess.Role.ADMIN);
        return ApiResult.ok(catalog.saveType(code,input));
    }

    @GetMapping("/api/iot/metrics")
    ApiResult<List<IotCatalog.Metric>> metrics(HttpServletRequest request) {
        access.actor(request);
        return ApiResult.ok(catalog.metrics());
    }

    @PostMapping("/api/iot/metrics")
    ApiResult<IotCatalog.Metric> createMetric(HttpServletRequest request, @Valid @RequestBody IotCatalog.MetricInput input) {
        return updateMetric(null,request,input);
    }

    @PutMapping("/api/iot/metrics/{code}")
    ApiResult<IotCatalog.Metric> updateMetric(@PathVariable String code, HttpServletRequest request,
            @Valid @RequestBody IotCatalog.MetricInput input) {
        access.actor(request).require(IotAccess.Role.ADMIN);
        return ApiResult.ok(catalog.saveMetric(code,input));
    }
}
