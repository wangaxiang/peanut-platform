package com.peanut.module.iot;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.peanut.module.patrol.PatrolDevice;
import com.peanut.module.patrol.PatrolDeviceService;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class TelemetryIngestionService {
    record Sample(UUID bootId, long sequence, Instant time, Map<String,Double> metrics, Map<String,String> quality) {}
    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final PatrolDeviceService devices;
    private final IotCatalog catalog;
    private final IotInflux influx;

    TelemetryIngestionService(JdbcClient jdbc, ObjectMapper json, PatrolDeviceService devices, IotCatalog catalog, IotInflux influx) {
        this.jdbc=jdbc;
        this.json=json;
        this.devices=devices;
        this.catalog=catalog;
        this.influx=influx;
    }

    @Transactional
    public boolean ingestSimulated(long id, Sample sample) {
        lockDevice(id);
        PatrolDevice device=devices.get(id);
        if (!"active".equals(device.status()) || !"simulated".equals(device.accessMode())
                || !catalog.type(device.deviceType()).enabled()) {
            throw IotCatalog.bad("模拟遥测要求已启用的模拟设备及设备类型");
        }
        var definitions=catalog.capabilities(id);
        var quality=new LinkedHashMap<String,String>();
        var latest=json.createObjectNode();
        if (sample.metrics().isEmpty()) throw IotCatalog.bad("至少需要一个指标值");
        for (var entry:sample.metrics().entrySet()) {
            var definition=definitions.stream().filter(metric -> metric.code().equals(entry.getKey())).findFirst()
                    .orElseThrow(() -> IotCatalog.bad("设备不支持指标 " + entry.getKey()));
            double value=entry.getValue();
            String code=sample.quality().getOrDefault(entry.getKey(),"good");
            if (!Double.isFinite(value) || !List.of("good","suspect","bad").contains(code)
                    || (List.of("battery_pct","signal_strength_pct").contains(entry.getKey()) && (value<0 || value>100))) {
                throw IotCatalog.bad("指标值或质量码无效");
            }
            quality.put(entry.getKey(),code);
            latest.putObject(entry.getKey()).put("value",value).put("unit",definition.canonicalUnit())
                    .put("timestamp",sample.time().toString()).put("source","simulated").put("quality",code);
        }
        String message=sample.bootId()+":"+sample.sequence();
        int inserted=jdbc.sql("""
                INSERT INTO iot_telemetry_dedupe(device_id,message_id,boot_id,sequence_no)
                VALUES (:id,:message,:boot,:sequence) ON CONFLICT DO NOTHING
                """).param("id",id).param("message",message).param("boot",sample.bootId().toString())
                .param("sequence",sample.sequence()).update();
        if (inserted == 0) return false;

        // Retry uses the same series, timestamp and idempotency key.
        influx.write(id,device.deviceSn(),device.fieldId(),device.deviceType(),"simulated",sample.time(),sample.metrics(),quality);
        jdbc.sql("""
                UPDATE patrol_device SET latest_metrics=latest_metrics||CAST(:latest AS jsonb),last_telemetry_at=:time,
                     last_seen_at=now(),online=true,updated_at=now(),
                     battery_pct=coalesce(CAST(:battery AS numeric),battery_pct),
                     signal_strength_pct=coalesce(CAST(:signal AS numeric),signal_strength_pct)
                WHERE id=:id
                """).param("id",id).param("latest",latest.toString()).param("time",sample.time().atOffset(ZoneOffset.UTC))
                .param("battery",sample.metrics().get("battery_pct")).param("signal",sample.metrics().get("signal_strength_pct")).update();
        return true;
    }

    void lockDevice(long id) {
        jdbc.sql("SELECT id FROM patrol_device WHERE id=:id FOR UPDATE").param("id",id).query(Long.class)
                .optional().orElseThrow(() -> IotCatalog.bad("设备不存在"));
    }
}
