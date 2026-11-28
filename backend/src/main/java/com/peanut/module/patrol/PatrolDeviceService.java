package com.peanut.module.patrol;

import static com.peanut.module.patrol.PatrolDeviceRequests.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import com.fasterxml.jackson.databind.JsonNode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
class PatrolDeviceService {
    private static final List<String> TYPES = List.of("drone", "dock", "sensor");
    private static final List<String> STATUSES = List.of("active", "inactive", "maintenance", "retired");
    static final List<String> METRICS = List.of("soil_temp", "soil_moisture", "soil_n", "soil_p", "soil_k");
    static final String SERIAL = "[A-Za-z0-9_-]{1,64}";
    private final PatrolDeviceRepository devices;
    private final PatrolRepository patrol;
    private final Map<String, Long> timeoutSeconds;

    PatrolDeviceService(PatrolDeviceRepository devices, PatrolRepository patrol,
            @Value("${patrol.offline.drone-seconds:90}") long drone,
            @Value("${patrol.offline.dock-seconds:180}") long dock,
            @Value("${patrol.offline.sensor-seconds:180}") long sensor) {
        this.devices = devices;
        this.patrol = patrol;
        if (drone <= 0 || dock <= 0 || sensor <= 0) {
            throw new IllegalArgumentException("Patrol device offline thresholds must be positive");
        }
        timeoutSeconds = Map.of("drone", drone, "dock", dock, "sensor", sensor);
    }

    Page<PatrolDevice> list(String type, Long fieldId, String status, Boolean online, String keyword,
            Long taskFieldId, Pageable page) {
        if ((type != null && !TYPES.contains(type)) || (status != null && !STATUSES.contains(status))) {
            throw bad("设备类型或业务状态无效");
        }
        return devices.list(type, fieldId, status, online, keyword, taskFieldId, page);
    }

    Map<String, Object> stats() {
        var types = devices.stats();
        Totals total = types.values().stream().reduce(new Totals(0,0,0,0), Totals::plus);
        return Map.of("total",total.total(),"online",total.online(),"offline",total.offline(),
                "maintenance",total.maintenance(),"byType",types);
    }

    PatrolDevice get(long id) {
        return devices.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "设备不存在"));
    }

    @Transactional
    public PatrolDevice create(DeviceInput input) {
        devices.lockRegistry();
        validateMetadata(input, null);
        return get(devices.insert(input).orElseThrow(() -> bad("设备序列号已登记")));
    }

    @Transactional
    public PatrolDevice update(long id, DeviceInput input) {
        devices.lockRegistry();
        PatrolDevice current = get(id);
        mutable(current);
        if (!current.deviceSn().equals(input.deviceSn()) || !current.deviceType().equals(input.deviceType())) {
            throw bad("设备类型和序列号在登记后固定");
        }
        validateMetadata(input, id);
        devices.update(id, input);
        return get(id);
    }

    @Transactional
    public PatrolDevice setStatus(long id, String status) {
        devices.lockRegistry();
        mutable(get(id));
        devices.setStatus(id, status);
        return get(id);
    }

    @Transactional
    public PatrolDevice retire(long id) {
        devices.lockRegistry();
        PatrolDevice device = get(id);
        if ("retired".equals(device.status())) {
            return device;
        }
        if (devices.hasActiveReferences(id)) {
            throw conflict("设备仍关联启用任务、未结束飞行或在用无人机，暂不能退役");
        }
        devices.setStatus(id, "retired");
        return get(id);
    }

    Page<Maintenance> maintenance(long id, Pageable page) {
        get(id);
        return devices.maintenance(id, page);
    }

    @Transactional
    public Maintenance addMaintenance(long id, MaintenanceInput input) {
        devices.lockRegistry();
        mutable(get(id));
        if (!"completed".equals(input.status()) && input.completedAt() != null) {
            throw bad("完成时间只适用于已完成的维护记录");
        }
        Instant completed = "completed".equals(input.status())
                ? Objects.requireNonNullElseGet(input.completedAt(), Instant::now) : null;
        if (completed != null && input.startedAt() != null && completed.isBefore(input.startedAt())) {
            throw bad("维护完成时间必须晚于或等于开始时间");
        }
        if (input.nextMaintenanceAt() != null && completed != null && input.nextMaintenanceAt().isBefore(completed)) {
            throw bad("下次维护时间必须晚于或等于完成时间");
        }
        return devices.addMaintenance(id,input,completed);
    }

    PatrolDevice requireDrone(Long id, long fieldId, boolean requiresDock) {
        if (id == null) {
            throw conflict("历史任务须先核对并绑定已登记的无人机");
        }
        PatrolDevice drone = get(id);
        if (!"drone".equals(drone.deviceType()) || !"active".equals(drone.status())) {
            throw conflict("请选择已启用的无人机，停用、维护中和退役设备不可下发");
        }
        if (drone.fieldId() != null && drone.fieldId() != fieldId) {
            throw conflict("无人机绑定的地块与巡田地块不一致");
        }
        if (!isOnline(drone)) {
            throw conflict("无人机离线，请等待设备上报");
        }
        if (requiresDock) {
            if (drone.parentDeviceId() == null) {
                throw conflict("自动起降任务需要先绑定机库");
            }
            PatrolDevice dock = get(drone.parentDeviceId());
            if (!"active".equals(dock.status()) || !isOnline(dock)) {
                throw conflict("自动起降所需机库未启用或离线");
            }
            if (dock.fieldId() != null && dock.fieldId() != fieldId) {
                throw conflict("自动起降机库绑定的地块与巡田地块不一致");
            }
        }
        return drone;
    }

    PatrolDevice sensor(String serial) {
        return devices.find(serial, "sensor")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "传感器未登记"));
    }

    Long activeSensorField(String serial) {
        PatrolDevice sensor = sensor(serial);
        if (!"active".equals(sensor.status())) {
            throw conflict("传感器当前未启用");
        }
        return sensor.fieldId();
    }

    // The same predicate is used by the writer before putting a reading into InfluxDB.
    static boolean validSensorReport(JsonNode report, long fieldId) {
        for (String key : List.of("fieldId","field_id")) {
            if (report.has(key) && (!report.get(key).isIntegralNumber() || !report.get(key).canConvertToLong()
                    || report.get(key).longValue() != fieldId)) {
                return false;
            }
        }
        JsonNode timestamp = report.path("timestamp");
        if (!timestamp.isMissingNode() && !timestamp.isNull() && (!timestamp.isIntegralNumber() || !timestamp.canConvertToLong()
                || timestamp.longValue() <= 0 || timestamp.longValue() > 9223372036854L)) {
            return false;
        }
        JsonNode metrics = report.has("metrics") ? report.get("metrics") : report;
        return metrics.isObject() && METRICS.stream().anyMatch(metrics::has)
                && METRICS.stream().filter(metrics::has).map(metrics::get)
                        .allMatch(value -> value.isNumber() && Double.isFinite(value.doubleValue()));
    }

    PatrolDevice report(String type, String serial, JsonNode data) {
        PatrolDevice device = devices.find(serial,type).orElse(null);
        if (device == null || "retired".equals(device.status()) || !validTelemetry(data)) {
            return null;
        }
        boolean sensor = "sensor".equals(type);
        if (sensor && (!"active".equals(device.status()) || !validSensorReport(data, device.fieldId()))) {
            return null;
        }
        return devices.report(device.id(),type,device.fieldId(),data.toString(),sensor) ? device : null;
    }

    @Scheduled(fixedDelayString = "${patrol.offline.scan-ms:5000}")
    void expireDevices() {
        Instant now = Instant.now();
        timeoutSeconds.forEach((type, seconds) -> devices.expire(type, now.minusSeconds(seconds)));
    }

    private boolean isOnline(PatrolDevice row) {
        return row.online() && row.lastSeenAt() != null
                && row.lastSeenAt().isAfter(Instant.now().minusSeconds(timeoutSeconds.get(row.deviceType())));
    }

    private void validateMetadata(DeviceInput input, Long id) {
        if (input.capabilities() != null && !input.capabilities().isObject()) {
            throw bad("设备能力必须是 JSON 对象");
        }
        if ("sensor".equals(input.deviceType()) && input.fieldId() == null) {
            throw bad("巡田传感器必须绑定地块");
        }
        if (input.fieldId() != null && !patrol.fieldExists(input.fieldId())) {
            throw bad("绑定地块不存在");
        }
        if (input.parentDeviceId() != null) {
            if (!"drone".equals(input.deviceType())) {
                throw bad("只有无人机可绑定机库");
            }
            PatrolDevice parent = devices.find(input.parentDeviceId()).orElseThrow(() -> bad("关联机库不存在"));
            if (!"dock".equals(parent.deviceType()) || "retired".equals(parent.status())) {
                throw bad("请选择有效机库");
            }
            if (input.fieldId() != null && parent.fieldId() != null && !input.fieldId().equals(parent.fieldId())) {
                throw bad("无人机与机库必须绑定同一地块，或保持一方为共享设备");
            }
        }
        if (id != null && input.fieldId() != null) {
            if ("dock".equals(input.deviceType()) && devices.conflictsWithChildren(id,input.fieldId())) {
                throw bad("修改机库地块将与已绑定无人机的地块冲突");
            }
            if ("drone".equals(input.deviceType()) && devices.conflictsWithTasks(id,input.fieldId())) {
                throw conflict("修改无人机地块将与已启用任务冲突");
            }
        }
    }

    private static boolean validTelemetry(JsonNode data) {
        return data.isObject()
                && inRange(data.path("battery"),0,100)
                && inRange(data.path("lng"),-180,180)
                && inRange(data.path("lat"),-90,90)
                && inRange(data.path("altitude"),-999999,999999);
    }

    private static boolean inRange(JsonNode value, double min, double max) {
        return value.isMissingNode() || value.isNull()
                || (value.isNumber() && value.doubleValue() >= min && value.doubleValue() <= max);
    }

    private static void mutable(PatrolDevice device) {
        if ("retired".equals(device.status())) {
            throw conflict("已退役设备仅可查询");
        }
    }

    private static ResponseStatusException bad(String message) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, message);
    }

    private static ResponseStatusException conflict(String message) {
        return new ResponseStatusException(HttpStatus.CONFLICT, message);
    }
}
