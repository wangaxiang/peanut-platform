package com.peanut.module.iot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Random;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.peanut.module.patrol.PatrolDevice;
import com.peanut.module.patrol.PatrolDeviceService;
import com.peanut.module.patrol.PatrolDeviceRequests.DeviceInput;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

@Service
class SimulatorService {
    private static final Logger LOG=LoggerFactory.getLogger(SimulatorService.class);
    private static final ZoneId LOCAL_DAY=ZoneId.of("Asia/Shanghai");
    static final Map<String,String> SCENARIOS=Map.of(
            "normal","正常运行","soil_dry","土壤缺水","high_temperature","高温","device_offline","设备离线",
            "low_battery","低电量","sensor_stuck","传感器卡死","spike","异常尖峰");
    private static final Map<String,Double> BASELINES=Map.ofEntries(
            Map.entry("soil_temp_c",24.5),Map.entry("soil_moisture_vwc_pct",37d),Map.entry("soil_ec_us_cm",820d),
            Map.entry("soil_ph",6.5),Map.entry("soil_n_mg_kg",82d),Map.entry("soil_p_mg_kg",28d),Map.entry("soil_k_mg_kg",116d),
            Map.entry("air_temp_c",26d),Map.entry("air_humidity_pct",56d),Map.entry("light_lux",12000d),
            Map.entry("rainfall_mm",0d),Map.entry("wind_speed_m_s",2.7),Map.entry("wind_direction_deg",125d),
            Map.entry("leaf_wetness_pct",24d),Map.entry("battery_pct",91d),Map.entry("signal_strength_pct",84d),
            Map.entry("insect_count",12d),Map.entry("spore_count",8d));
    record MetricSettings(Double baseline, @PositiveOrZero Double noise, Double trend, @PositiveOrZero Double dailyAmplitude) {}
    record Settings(@Min(5) @Max(86400) Integer sampleIntervalSeconds, Long randomSeed,
            @Size(max=64) Map<String,@Valid MetricSettings> metrics, @Min(0) @Max(604800) Integer durationSeconds,
            String impactMetric, @PositiveOrZero Double amplitude, boolean recoverToNormal) {}
    record Control(String scenario, Boolean keepLastOnline, String metricCode, Double value) {}
    record NewSimulator(@NotBlank @Size(max=100) String deviceName, @NotBlank String deviceType,
            @Positive Long fieldId, @Size(max=200) String installLocation, @Size(max=64) List<String> metricCodes,
            @Valid Settings settings) {}
    record Profile(long deviceId, String scenario, String status, int sampleIntervalSeconds,
            long randomSeed, JsonNode config, Instant lastGeneratedAt, Instant nextSampleAt, UUID run,
            long sequence, long step) {}
    record Entry(PatrolDevice device, Profile profile) {}

    private final JdbcClient jdbc;
    private final ObjectMapper json;
    private final IotCatalog catalog;
    private final PatrolDeviceService devices;
    private final TelemetryIngestionService telemetry;
    private final TransactionTemplate transactions;

    SimulatorService(JdbcClient jdbc,ObjectMapper json,IotCatalog catalog,PatrolDeviceService devices,
            TelemetryIngestionService telemetry,TransactionTemplate transactions) {
        this.jdbc=jdbc;
        this.json=json;
        this.catalog=catalog;
        this.devices=devices;
        this.telemetry=telemetry;
        this.transactions=transactions;
    }

    List<Entry> list(IotAccess.Actor actor) {
        return jdbc.sql("SELECT device_id FROM iot_simulator_profile ORDER BY device_id DESC")
                .query(Long.class).list().stream().map(devices::get)
                .filter(device -> actor.fields()==null || actor.fields().contains(device.fieldId()))
                .map(device -> new Entry(device,profile(device.id(),false))).toList();
    }

    List<IotCatalog.DeviceType> templates() {
        return catalog.types().stream().filter(type -> type.enabled() && !type.suggestedMetrics().isEmpty()).toList();
    }

    @Transactional
    public Entry create(NewSimulator input, IotAccess.Actor actor) {
        actor.require(IotAccess.Role.ADMIN);
        actor.checkField(input.fieldId());
        String serial="SIM-" + UUID.randomUUID().toString().substring(0,12).toUpperCase(java.util.Locale.ROOT);
        Integer interval=input.settings()==null?null:input.settings().sampleIntervalSeconds();
        PatrolDevice device=devices.create(new DeviceInput(input.deviceType(),serial,input.deviceName(),null,null,
                input.fieldId(),null,null,null,null,"simulated","simulator",input.installLocation(),null,
                interval,null,null,input.metricCodes(),null),actor.name());
        if (input.settings()!=null) return configure(device.id(),input.settings(),actor);
        return new Entry(device,profile(device.id(),false));
    }

    @Transactional
    public Entry configure(long id, Settings settings, IotAccess.Actor actor) {
        actor.require(IotAccess.Role.ADMIN);
        telemetry.lockDevice(id);
        PatrolDevice device=devices.get(id);
        actor.checkField(device.fieldId());
        Profile profile=profile(id,true);
        if ("retired".equals(device.status())) throw IotCatalog.bad("已退役设备仅可查询");
        var enabled=catalog.capabilities(id).stream().map(IotCatalog.Metric::code).toList();
        if (settings.impactMetric()!=null && !settings.impactMetric().isBlank() && !enabled.contains(settings.impactMetric())) {
            throw IotCatalog.bad("场景影响指标必须属于设备能力");
        }
        if (settings.metrics()!=null) {
            settings.metrics().forEach((key,value) -> {
                if (!enabled.contains(key) || value==null) throw IotCatalog.bad("模拟参数包含未启用的指标");
                for (Double number : new Double[]{value.baseline(),value.noise(),value.trend(),value.dailyAmplitude()}) {
                    if (number!=null && (!Double.isFinite(number) || Math.abs(number)>1e9)) throw IotCatalog.bad("模拟参数超出可用范围");
                }
            });
        }
        if (settings.amplitude()!=null && (!Double.isFinite(settings.amplitude()) || settings.amplitude()>1e9)) {
            throw IotCatalog.bad("模拟幅度超出可用范围");
        }
        int interval=Objects.requireNonNullElse(settings.sampleIntervalSeconds(),profile.sampleIntervalSeconds());
        jdbc.sql("UPDATE patrol_device SET sample_interval_seconds=:sample WHERE id=:id")
                .param("id",id).param("sample",interval).update();
        jdbc.sql("""
                UPDATE iot_simulator_profile SET sample_interval_seconds=:sample,random_seed=:seed,config=CAST(:config AS jsonb),
                    next_sample_at=CASE WHEN simulator_status='running' THEN now()+make_interval(secs=>:sample) ELSE NULL END
                WHERE device_id=:id
                """).param("id",id).param("sample",interval).param("seed",Objects.requireNonNullElse(settings.randomSeed(),profile.randomSeed()))
                .param("config",json.valueToTree(settings).toString()).update();
        catalog.event(id,device.fieldId(),"simulator_config","保存模拟配置",actor.name(),null);
        return new Entry(devices.get(id),profile(id,false));
    }

    @Transactional
    public Entry control(long id, String action, Control input, IotAccess.Actor actor) {
        actor.require(IotAccess.Role.ADMIN);
        telemetry.lockDevice(id);
        PatrolDevice device=devices.get(id);
        actor.checkField(device.fieldId());
        Profile current=profile(id,true);
        if (!"active".equals(device.status()) || !catalog.type(device.deviceType()).enabled()) throw IotCatalog.bad("请先启用设备和设备类型");
        String scenario=input==null || input.scenario()==null ? current.scenario() : input.scenario();
        if (!SCENARIOS.containsKey(scenario)) throw IotCatalog.bad("模拟场景不存在");
        switch (action) {
            case "start" -> {
                setRun(id,"running",scenario,Instant.now(),current.run(),current.sequence(),0);
            }
            case "resume" -> {
                if (!"paused".equals(current.status())) throw IotCatalog.bad("只有已暂停的模拟可以继续");
                setRun(id,"running",current.scenario(),Instant.now(),current.run(),current.sequence(),current.step());
            }
            case "pause" -> {
                if (!"running".equals(current.status())) throw IotCatalog.bad("模拟设备当前未运行");
                setRun(id,"paused",current.scenario(),null,current.run(),current.sequence(),current.step());
            }
            case "stop" -> {
                setRun(id,"stopped",current.scenario(),null,current.run(),current.sequence(),current.step());
                if (input!=null && Boolean.FALSE.equals(input.keepLastOnline())) offline(id);
            }
            case "reset" -> {
                setRun(id,"stopped","normal",null,UUID.randomUUID(),0,0);
                offline(id);
                jdbc.sql("UPDATE iot_simulator_profile SET started_at=NULL,last_generated_at=NULL WHERE device_id=:id")
                        .param("id",id).update();
            }
            case "inject" -> {
                if (input==null || input.metricCode()==null || input.value()==null) throw IotCatalog.bad("请选择指标并填写注入值");
                Instant now=Instant.now();
                telemetry.ingestSimulated(id,new TelemetryIngestionService.Sample(current.run(),current.sequence()+1,now,
                        Map.of(input.metricCode(),input.value()),Map.of(input.metricCode(),"suspect")));
                advance(id,current.sequence()+1,current.step(),current.scenario(),current.nextSampleAt(),now);
            }
            default -> throw IotCatalog.bad("模拟操作无效");
        }
        catalog.event(id,device.fieldId(),"simulator_"+action,"模拟设备 · "+action,actor.name(),
                json.createObjectNode().put("source","simulated").put("scenario",scenario));
        return new Entry(devices.get(id),profile(id,false));
    }

    private void offline(long id) {
        jdbc.sql("UPDATE patrol_device SET online=false WHERE id=:id").param("id",id).update();
    }

    private void setRun(long id,String status,String scenario,Instant next,UUID run,long sequence,long step) {
        jdbc.sql("""
                UPDATE iot_simulator_profile SET simulator_status=:status,scenario_code=:scenario,next_sample_at=:next,
                   run_id=:run,sequence_no=:sequence,scenario_step=:step,
                   started_at=CASE WHEN :status='running' AND :step=0 THEN now() ELSE started_at END,
                   paused_at=CASE WHEN :status='paused' THEN now() ELSE NULL END
                WHERE device_id=:id
                """).param("id",id).param("status",status).param("scenario",scenario).param("next",time(next))
                .param("run",run).param("sequence",sequence).param("step",step).update();
    }

    Profile profile(long id,boolean lock) {
        return jdbc.sql("SELECT * FROM iot_simulator_profile WHERE device_id=:id" + (lock?" FOR UPDATE":""))
                .param("id",id).query((rs,n) -> new Profile(id,rs.getString("scenario_code"),rs.getString("simulator_status"),
                        rs.getInt("sample_interval_seconds"),rs.getLong("random_seed"),catalog.json(rs.getString("config")),
                        instant(rs.getObject("last_generated_at",OffsetDateTime.class)),instant(rs.getObject("next_sample_at",OffsetDateTime.class)),
                        rs.getObject("run_id",UUID.class),rs.getLong("sequence_no"),rs.getLong("scenario_step")))
                .optional().orElseThrow(() -> IotCatalog.bad("设备没有模拟配置"));
    }

    @Scheduled(fixedDelayString="${iot.simulator-tick-ms:1000}")
    void sampleDue() {
        var due=jdbc.sql("""
                SELECT s.device_id FROM iot_simulator_profile s JOIN patrol_device d ON d.id=s.device_id
                  JOIN iot_device_type t ON t.code=d.device_type
                WHERE s.simulator_status='running' AND s.next_sample_at<=now() AND d.status='active' AND t.enabled
                ORDER BY s.next_sample_at LIMIT 50
                """).query(Long.class).list();
        for (long id:due) {
            try {
                transactions.executeWithoutResult(status -> tick(id));
            } catch (RuntimeException e) {
                LOG.warn("Simulator {} will retry its uncommitted sample: {}",id,e.getMessage());
            }
        }
    }

    private void tick(long id) {
        telemetry.lockDevice(id);
        Profile p=profile(id,true);
        Instant now=Instant.now();
        if (!"running".equals(p.status()) || p.nextSampleAt()==null || p.nextSampleAt().isAfter(now)) return;
        var device=devices.get(id);
        if (!"active".equals(device.status())) return;
        boolean offline="device_offline".equals(p.scenario());
        if (!offline) {
            Map<String,Double> samples=new LinkedHashMap<>();
            Map<String,String> quality=new LinkedHashMap<>();
            for (var metric:catalog.capabilities(id)) {
                double value=value(metric,p);
                samples.put(metric.code(),value);
                if (isSpike(metric.code(),p)) quality.put(metric.code(),"suspect");
            }
            telemetry.ingestSimulated(id,new TelemetryIngestionService.Sample(p.run(),p.sequence()+1,p.nextSampleAt(),samples,quality));
        }
        int duration=p.config().path("durationSeconds").asInt(0);
        boolean finished=duration>0 && (p.step()+1)*p.sampleIntervalSeconds()>=duration;
        String scenario=finished && p.config().path("recoverToNormal").asBoolean()?"normal":p.scenario();
        boolean stop=finished && !p.config().path("recoverToNormal").asBoolean(false);
        advance(id,p.sequence()+(offline?0:1),finished?0:p.step()+1,scenario,
                stop?null:now.plusSeconds(p.sampleIntervalSeconds()),offline?null:p.nextSampleAt());
        if (stop) setRun(id,"stopped",scenario,null,p.run(),p.sequence()+(offline?0:1),0);
    }

    private void advance(long id,long sequence,long step,String scene,Instant next,Instant sampled) {
        jdbc.sql("""
                UPDATE iot_simulator_profile SET sequence_no=:sequence,scenario_step=:step,scenario_code=:scene,
                  last_generated_at=coalesce(CAST(:sampled AS timestamptz),last_generated_at),next_sample_at=:next
                WHERE device_id=:id
                """).param("id",id).param("sequence",sequence).param("step",step).param("scene",scene)
                .param("sampled",time(sampled)).param("next",time(next)).update();
    }

    private boolean isSpike(String key,Profile p) {
        String target=p.config().path("impactMetric").asText("");
        return "spike".equals(p.scenario()) && p.step()==0 && (target.isBlank() || target.equals(key));
    }

    double value(IotCatalog.Metric metric,Profile p) {
        String key=metric.code();
        JsonNode tuning=p.config().path("metrics").path(key);
        double baseline=tuning.path("baseline").asDouble(BASELINES.getOrDefault(key,20d));
        double noise=tuning.path("noise").asDouble(Math.max(0.1,Math.abs(baseline)*0.02));
        double trend=tuning.path("trend").asDouble(0);
        double daily=tuning.path("dailyAmplitude").asDouble(0);
        long step=Math.min(10000,p.step());
        var random=new Random(p.randomSeed() ^ key.hashCode() ^ Long.rotateLeft(p.step(),29));
        int seconds=p.nextSampleAt().atZone(LOCAL_DAY).toLocalTime().toSecondOfDay();
        double result=baseline+(random.nextDouble()*2-1)*noise+trend*step+daily*Math.sin(seconds*2*Math.PI/86400);
        String target=p.config().path("impactMetric").asText("");
        if (target.isBlank() || target.equals(key)) {
            Double amplitude=p.config().path("amplitude").isNumber()?p.config().path("amplitude").doubleValue():null;
            result=switch(p.scenario()) {
                case "soil_dry" -> key.equals("soil_moisture_vwc_pct")?Math.max(0,result-Math.min(30,step*Objects.requireNonNullElse(amplitude,4d))):result;
                case "high_temperature" -> key.endsWith("_temp_c")?result+Math.min(20,(step+1)*Objects.requireNonNullElse(amplitude,3d)):result;
                case "low_battery" -> key.equals("battery_pct")?Math.max(0,20-step*Objects.requireNonNullElse(amplitude,2d)):result;
                case "sensor_stuck" -> baseline;
                case "spike" -> isSpike(key,p)?result+Objects.requireNonNullElse(amplitude,Math.max(30,Math.abs(baseline)*2)):result;
                default -> result;
            };
        }
        if (List.of("battery_pct","signal_strength_pct","soil_moisture_vwc_pct","air_humidity_pct","leaf_wetness_pct").contains(key)) {
            result=Math.max(0,Math.min(100,result));
        } else if (List.of("rainfall_mm","light_lux","wind_speed_m_s","insect_count","spore_count").contains(key)) {
            result=Math.max(0,result);
        }
        return BigDecimal.valueOf(result).setScale(metric.precisionDigits(),RoundingMode.HALF_UP).doubleValue();
    }

    static OffsetDateTime time(Instant time) { return time==null?null:time.atOffset(ZoneOffset.UTC); }
    private static Instant instant(OffsetDateTime value) { return value==null?null:value.toInstant(); }
}
