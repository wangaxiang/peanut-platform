# 花生种植精准服务平台 · AI 可执行技术规格文档（增强修订版）

> **阅读说明**：本文档面向 AI 编程助手直接执行。每个模块包含：约定 → DDL → API 定义 → 核心算法 → 验收标准。按模块/阶段顺序实现；模块二增强按第 3.9 节分阶段执行，每阶段完成后必须等待用户确认。

---

## 0. 全局约定

### 0.1 技术栈

| 层 | 选型 |
|----|------|
| 后端语言 | Java 21 + Spring Boot 3.2 |
| 构建工具 | Maven 3.9 |
| 关系型数据库 | PostgreSQL 16 + PostGIS 3.4 |
| 时序数据库 | InfluxDB 2.7 |
| 向量数据库 | pgvector 0.7（PostgreSQL 扩展） |
| 消息队列 | RabbitMQ 3.13 |
| MQTT Broker | EMQX 5.x |
| 影像处理 | Python 3.11 + rasterio + GDAL + numpy |
| AI 推理服务 | Python 3.11 + FastAPI + ultralytics（YOLOv8）|
| RAG 服务 | Python 3.11 + FastAPI + LangChain + Qwen2-7B |
| 前端 | Vue 3.4 + Vite 5 + Element Plus 2.x + MapLibre GL JS 4.x |
| 移动端 | 微信小程序（原生）|
| 容器化 | Docker Compose（开发）/ Kubernetes（生产）|
| 认证 | JWT（access token 2h + refresh token 7d）|

### 0.2 目录结构

```
peanut-platform/
├── backend/                        # Spring Boot 主服务
│   ├── src/main/java/com/peanut/
│   │   ├── common/                 # 统一响应、异常、工具类
│   │   ├── config/                 # 数据库、MQ、MQTT 配置
│   │   ├── module/
│   │   │   ├── field/              # 模块一：农田管理
│   │   │   ├── patrol/             # 模块二：智能巡田
│   │   │   ├── pest/               # 模块三：病虫害
│   │   │   ├── prescription/       # 模块四：处方图
│   │   │   ├── irrigation/         # 模块五：水肥控制
│   │   │   └── knowledge/          # 模块六：知识库
│   │   └── PeanutApplication.java
│   └── src/main/resources/
│       ├── application.yml
│       └── db/migration/           # Flyway 迁移脚本
├── ai-service/                     # Python AI 推理服务
│   ├── pest_detection/             # 病虫害检测
│   ├── ndvi_processor/             # 多光谱影像处理
│   ├── prescription/               # 处方图生成
│   └── rag/                        # 知识问答
├── frontend/                       # Vue 3 Web 端
│   └── src/
│       ├── views/
│       ├── components/
│       ├── api/                    # axios 封装，与后端 API 一一对应
│       └── stores/                 # Pinia 状态管理
├── docker-compose.yml
└── docs/                           # 本文档所在目录
```

### 0.3 统一响应格式

```java
// 所有接口返回此结构
public record ApiResult<T>(
    int code,       // 200=成功, 400=参数错误, 401=未登录, 500=服务异常
    String msg,
    T data
) {
    public static <T> ApiResult<T> ok(T data) {
        return new ApiResult<>(200, "success", data);
    }
    public static <T> ApiResult<T> fail(int code, String msg) {
        return new ApiResult<>(code, msg, null);
    }
}
```

### 0.4 分页请求约定

```
GET /api/xxx?page=0&size=20&sort=createdAt,desc
```

响应体 `data` 字段为 Spring `Page<T>` 的 JSON 序列化结果。

---

## 1. 数据库完整 DDL

> 文件路径：`backend/src/main/resources/db/migration/V1__init.sql`

```sql
-- 启用扩展
CREATE EXTENSION IF NOT EXISTS postgis;
CREATE EXTENSION IF NOT EXISTS vector;
CREATE EXTENSION IF NOT EXISTS "uuid-ossp";

-- ==================== 模块一：农田信息 ====================

CREATE TABLE field (
    id           BIGSERIAL PRIMARY KEY,
    name         VARCHAR(100)  NOT NULL,
    geometry     GEOMETRY(POLYGON, 4326) NOT NULL,
    area_mu      DECIMAL(10,2) NOT NULL,          -- 亩
    soil_type    VARCHAR(50),                      -- 沙壤土/壤土/黏壤土
    variety      VARCHAR(100),                     -- 花生品种
    sow_date     DATE,
    owner_name   VARCHAR(50),
    remark       TEXT,
    created_at   TIMESTAMPTZ DEFAULT NOW(),
    updated_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_field_geometry ON field USING GIST(geometry);

CREATE TABLE farm_event (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    event_type   VARCHAR(50) NOT NULL,  -- sow/fertilize/spray/harvest/irrigate
    event_date   DATE NOT NULL,
    operator     VARCHAR(50),
    amount       DECIMAL(10,2),         -- 用量（单位随 event_type 而异）
    unit         VARCHAR(20),
    remark       TEXT,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块二：巡田任务 ====================

CREATE TABLE patrol_task (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    task_name    VARCHAR(100),
    schedule_cron VARCHAR(50),          -- 定时表达式，如 0 6 * * 1,4
    drone_sn     VARCHAR(64),           -- 机库/无人机序列号
    route_geojson JSONB,                -- 航线 GeoJSON
    status       VARCHAR(20) DEFAULT 'active',  -- active/paused/deleted
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE patrol_flight (
    id           BIGSERIAL PRIMARY KEY,
    task_id      BIGINT NOT NULL REFERENCES patrol_task(id) ON DELETE CASCADE,
    field_id     BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    start_time   TIMESTAMPTZ,
    end_time     TIMESTAMPTZ,
    status       VARCHAR(20) DEFAULT 'pending',  -- pending/flying/processing/done/failed
    raw_image_path TEXT,                -- 原始多光谱影像存储路径
    ndvi_tile_url  TEXT,                -- NDVI 切片服务 URL
    ndvi_stats   JSONB,                 -- {min, max, mean, std, red_pct, yellow_pct, green_pct}
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块三：病虫害 ====================

CREATE TABLE pest_detection (
    id           BIGSERIAL PRIMARY KEY,
    flight_id    BIGINT REFERENCES patrol_flight(id) ON DELETE SET NULL,
    field_id     BIGINT NOT NULL REFERENCES field(id),
    source       VARCHAR(20) NOT NULL,  -- flight/manual_upload
    image_url    TEXT NOT NULL,
    detections   JSONB NOT NULL,        -- 检测结果数组（见 3.3 节格式）
    alert_sent   BOOLEAN DEFAULT FALSE,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_pest_field ON pest_detection(field_id);

-- ==================== 模块四：处方图 ====================

CREATE TABLE prescription_map (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id),
    flight_id    BIGINT REFERENCES patrol_flight(id) ON DELETE SET NULL,
    map_type     VARCHAR(20) NOT NULL,  -- fertilize/spray
    zones        JSONB NOT NULL,        -- 分区 GeoJSON FeatureCollection，每个 Feature 含 amount 属性
    status       VARCHAR(20) DEFAULT 'draft',  -- draft/confirmed/executed
    confirmed_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块五：水肥一体化 ====================

CREATE TABLE irrigation_device (
    id           BIGSERIAL PRIMARY KEY,
    field_id     BIGINT NOT NULL REFERENCES field(id),
    device_sn    VARCHAR(64) NOT NULL UNIQUE,
    device_name  VARCHAR(100),
    zone_count   INT DEFAULT 1,         -- 分区数
    online       BOOLEAN DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ,
    created_at   TIMESTAMPTZ DEFAULT NOW()
);

CREATE TABLE irrigation_task (
    id              BIGSERIAL PRIMARY KEY,
    field_id        BIGINT NOT NULL REFERENCES field(id),
    device_id       BIGINT NOT NULL REFERENCES irrigation_device(id),
    zone_ids        INT[] NOT NULL,
    trigger_type    VARCHAR(20) NOT NULL,  -- manual/scheduled/sensor
    water_amount_m3 DECIMAL(8,2),
    fertilizer      JSONB,                 -- {n_kg, p_kg, k_kg, concentration_pct}
    duration_min    INT,
    status          VARCHAR(20) DEFAULT 'pending',  -- pending/running/done/failed
    scheduled_at    TIMESTAMPTZ,
    started_at      TIMESTAMPTZ,
    finished_at     TIMESTAMPTZ,
    created_at      TIMESTAMPTZ DEFAULT NOW()
);

-- ==================== 模块六：知识库 ====================

CREATE TABLE knowledge_chunk (
    id           BIGSERIAL PRIMARY KEY,
    source_title VARCHAR(200),
    content      TEXT NOT NULL,
    embedding    VECTOR(1536),          -- BGE-M3 的 1024 维结果统一补零后写入
    metadata     JSONB,                 -- {category, variety, growth_stage, ...}
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX idx_knowledge_embedding ON knowledge_chunk
    USING ivfflat (embedding vector_cosine_ops) WITH (lists = 100);

CREATE TABLE qa_log (
    id           BIGSERIAL PRIMARY KEY,
    question     TEXT NOT NULL,
    answer       TEXT NOT NULL,
    sources      JSONB,                 -- 引用的 chunk id 列表
    field_id     BIGINT REFERENCES field(id),
    created_at   TIMESTAMPTZ DEFAULT NOW()
);
```

### 1.1 已部署项目的巡田设备增量迁移（V5）

> 现有环境已经执行 V1 初始化、V2 生长周报、V3 巡田监测、V4 单次巡田计划以及 V5 巡田设备台账。V1–V5 是不可变迁移。V5 文件见后端 Flyway 目录。

迁移注意事项：
- V1 的 `patrol_task.drone_sn` 注释同时写“机库/无人机序列号”，无法可靠判断历史值究竟是哪种设备。V5 **不得自动把所有旧值都认定为无人机或机库**；保留旧列供历史兼容，由管理员核对后再登记并映射。新任务一律要求 `droneDeviceId`。
- 新建巡田任务必须通过 `drone_device_id` 关联 `patrol_device` 中 `device_type='drone'` 且 `status='active'` 的设备；`drone_sn` 仅用于旧数据兼容，不再作为新建任务的输入字段。
- 设备采用逻辑退役，不物理删除有任务/飞行/维护历史的设备；API 的 DELETE 实际将 `status` 设为 `retired`。由于设备与地块采用 RESTRICT 关联，删除地块前必须先解绑或迁移关联设备，不能级联丢失设备归属。
- `patrol_device` 不包含模块五的灌溉控制器。巡田无人机/机库/土壤传感器与 `irrigation_device` 是两类设备台账，分别管理。


### 1.2 物联网监测中心扩展迁移（V6）

> 本次增强把原来固定的设备类型扩展为可配置类型与指标能力。经审计，现有环境已执行 V1–V5，原建议的 V3 已被巡田监测占用。阶段 A 使用 V6，现有库从 V5 增量升级，空库按 V1 → V6 顺序执行。部署以仓库 Flyway 脚本为准，以下 SQL 保留为设计参考。

```sql
-- 1) 可扩展设备类型注册表：保留 drone/dock/sensor 旧 code，向后兼容 V5。
CREATE TABLE iot_device_type (
    code VARCHAR(64) PRIMARY KEY,
    name VARCHAR(80) NOT NULL,
    category VARCHAR(40) NOT NULL,
    description TEXT,
    default_sample_interval_seconds INT,
    default_offline_timeout_seconds INT NOT NULL DEFAULT 180,
    capability_schema JSONB NOT NULL DEFAULT '{}'::jsonb,
    is_system BOOLEAN NOT NULL DEFAULT FALSE,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
INSERT INTO iot_device_type(code,name,category,description,default_sample_interval_seconds,default_offline_timeout_seconds,is_system)
VALUES
 ('drone','无人机','aircraft','执行巡田航线与影像采集',NULL,90,TRUE),
 ('dock','无人机机库','gateway','无人机停放、充电与自动起降（按型号能力）',NULL,180,TRUE),
 ('sensor','巡田传感器','soil_sensor','兼容旧版土壤传感器类型',300,900,TRUE),
 ('soil_multi_sensor','土壤多参数传感器','soil_sensor','土壤温度、含水率、EC、pH、NPK 等',300,900,TRUE),
 ('soil_moisture_sensor','土壤水分温度传感器','soil_sensor','土壤水分与温度',300,900,TRUE),
 ('weather_station','农业气象站','weather','空气温湿度、光照、降雨、风速风向',300,900,TRUE),
 ('insect_light','智能虫情灯','pest_monitor','诱虫计数、图像与运行状态',900,2700,TRUE),
 ('spore_trap','孢子捕捉器','pest_monitor','孢子采样与图像/计数数据',900,2700,TRUE),
 ('leaf_sensor','叶面湿度/光照传感器','leaf_sensor','叶面湿度、光照等',300,900,TRUE),
 ('other_sensor','其他环境传感器','other','管理员扩展的通用传感器',300,900,TRUE)
ON CONFLICT (code) DO NOTHING;

-- V5 patrol_device 的 CHECK 只允许三种类型；移除该限制并改由注册表外键约束。
DO $$
DECLARE c RECORD;
BEGIN
  FOR c IN
    SELECT conname FROM pg_constraint
    WHERE conrelid = 'patrol_device'::regclass AND contype = 'c'
      AND pg_get_constraintdef(oid) ILIKE '%device_type%'
  LOOP
    EXECUTE format('ALTER TABLE patrol_device DROP CONSTRAINT %I', c.conname);
  END LOOP;
END $$;
ALTER TABLE patrol_device ALTER COLUMN device_type TYPE VARCHAR(64);
ALTER TABLE patrol_device ADD CONSTRAINT fk_patrol_device_type_code
    FOREIGN KEY (device_type) REFERENCES iot_device_type(code);

-- 2) 统一指标字典。单位统一在入库层归一化；UI 不根据名称猜单位。
CREATE TABLE iot_metric_definition (
    code VARCHAR(64) PRIMARY KEY,
    display_name VARCHAR(100) NOT NULL,
    category VARCHAR(40) NOT NULL,
    canonical_unit VARCHAR(32) NOT NULL,
    data_type VARCHAR(20) NOT NULL DEFAULT 'number',
    precision_digits INT NOT NULL DEFAULT 1,
    recommended_min DOUBLE PRECISION,
    recommended_max DOUBLE PRECISION,
    description TEXT,
    display_order INT NOT NULL DEFAULT 100,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
INSERT INTO iot_metric_definition(code,display_name,category,canonical_unit,precision_digits,recommended_min,recommended_max,display_order,description)
VALUES
 ('soil_temp_c','土壤温度','soil','°C',1,-10,60,10,'土壤温度'),
 ('soil_moisture_vwc_pct','土壤体积含水率','soil','%VWC',1,0,100,20,'土壤体积含水率；旧 soil_moisture 映射至此'),
 ('soil_ec_us_cm','土壤电导率 EC','soil','µS/cm',0,0,20000,30,'土壤电导率'),
 ('soil_ph','土壤 pH','soil','pH',2,0,14,40,'土壤酸碱度'),
 ('soil_n_mg_kg','土壤氮','soil','mg/kg',1,0,10000,50,'土壤有效氮，具体检测方法需按传感器校准'),
 ('soil_p_mg_kg','土壤磷','soil','mg/kg',1,0,10000,60,'土壤有效磷，具体检测方法需按传感器校准'),
 ('soil_k_mg_kg','土壤钾','soil','mg/kg',1,0,10000,70,'土壤有效钾，具体检测方法需按传感器校准'),
 ('air_temp_c','空气温度','weather','°C',1,-40,70,80,'气象站空气温度'),
 ('air_humidity_pct','空气相对湿度','weather','%',1,0,100,90,'空气相对湿度'),
 ('light_lux','光照强度','weather','lx',0,0,200000,100,'照度'),
 ('rainfall_mm','降雨量','weather','mm',1,0,1000,110,'按设备定义累计或区间降雨，需在 metadata 标注语义'),
 ('wind_speed_m_s','风速','weather','m/s',1,0,100,120,'风速'),
 ('wind_direction_deg','风向','weather','°',0,0,360,130,'风向角度'),
 ('leaf_wetness_pct','叶面湿度','leaf','%',1,0,100,140,'设备输出相对叶面湿润度'),
 ('battery_pct','电池电量','device','%',0,0,100,150,'设备电量'),
 ('signal_strength_pct','信号强度','device','%',0,0,100,160,'归一化信号强度；原始 RSSI 需先转换或另存'),
 ('insect_count','虫情计数','pest','count',0,0,NULL,170,'虫情灯采集计数'),
 ('spore_count','孢子计数','pest','count',0,0,NULL,180,'孢子捕捉器采集计数')
ON CONFLICT (code) DO NOTHING;

-- 3) 设备通用属性与连接配置。旧 brand/capabilities/lat/lng 字段继续保留。
ALTER TABLE patrol_device ADD COLUMN protocol VARCHAR(24) NOT NULL DEFAULT 'mqtt';
ALTER TABLE patrol_device ADD COLUMN access_mode VARCHAR(24) NOT NULL DEFAULT 'real';
ALTER TABLE patrol_device ADD COLUMN install_location VARCHAR(200);
ALTER TABLE patrol_device ADD COLUMN sample_interval_seconds INT;
ALTER TABLE patrol_device ADD COLUMN signal_strength_pct DECIMAL(5,2);
ALTER TABLE patrol_device ADD COLUMN last_telemetry_at TIMESTAMPTZ;
ALTER TABLE patrol_device ADD COLUMN device_tags TEXT[] NOT NULL DEFAULT ARRAY[]::TEXT[];
ALTER TABLE patrol_device ADD COLUMN device_metadata JSONB NOT NULL DEFAULT '{}'::jsonb;
ALTER TABLE patrol_device ADD COLUMN created_by BIGINT;
ALTER TABLE patrol_device ADD COLUMN retired_at TIMESTAMPTZ;
ALTER TABLE patrol_device ADD COLUMN retired_reason TEXT;
ALTER TABLE patrol_device ADD CONSTRAINT chk_patrol_device_protocol
    CHECK (protocol IN ('mqtt','http','lorawan_gateway','simulator','vendor_adapter','other'));
ALTER TABLE patrol_device ADD CONSTRAINT chk_patrol_device_access_mode
    CHECK (access_mode IN ('real','simulated'));

-- 4) 设备指标能力：设备有哪些指标，由能力表决定，不按设备类型硬编码展示。
CREATE TABLE iot_device_metric_capability (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    metric_code VARCHAR(64) NOT NULL REFERENCES iot_metric_definition(code),
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    unit_override VARCHAR(32),
    display_name_override VARCHAR(100),
    calibration_offset DOUBLE PRECISION NOT NULL DEFAULT 0,
    calibration_scale DOUBLE PRECISION NOT NULL DEFAULT 1,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(device_id, metric_code)
);
CREATE INDEX idx_iot_device_metric_device ON iot_device_metric_capability(device_id, enabled);

-- 5) 设备分组。地块归属仍使用 patrol_device.field_id，分组用于跨地块运维视图。
CREATE TABLE iot_device_group (
    id BIGSERIAL PRIMARY KEY,
    group_name VARCHAR(100) NOT NULL,
    description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(group_name)
);
CREATE TABLE iot_device_group_member (
    group_id BIGINT NOT NULL REFERENCES iot_device_group(id) ON DELETE CASCADE,
    device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY(group_id, device_id)
);

-- 6) 阈值规则、告警、设备事件。
CREATE TABLE iot_threshold_rule (
    id BIGSERIAL PRIMARY KEY,
    rule_name VARCHAR(120) NOT NULL,
    scope_type VARCHAR(20) NOT NULL DEFAULT 'device_type',
    device_id BIGINT REFERENCES patrol_device(id) ON DELETE CASCADE,
    device_type_code VARCHAR(64) REFERENCES iot_device_type(code),
    rule_type VARCHAR(32) NOT NULL, -- metric_threshold/offline/low_battery/low_signal/data_timeout/fault
    metric_code VARCHAR(64) REFERENCES iot_metric_definition(code),
    operator VARCHAR(8),            -- gt/gte/lt/lte/eq
    threshold_value DOUBLE PRECISION,
    recovery_value DOUBLE PRECISION,
    duration_seconds INT NOT NULL DEFAULT 0,
    cooldown_seconds INT NOT NULL DEFAULT 900,
    severity VARCHAR(16) NOT NULL DEFAULT 'warning', -- info/warning/critical
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CHECK (scope_type IN ('global','device_type','device')),
    CHECK (rule_type IN ('metric_threshold','offline','low_battery','low_signal','data_timeout','fault')),
    CHECK (severity IN ('info','warning','critical')),
    CHECK (operator IS NULL OR operator IN ('gt','gte','lt','lte','eq')),
    CHECK (duration_seconds >= 0 AND cooldown_seconds >= 0)
);
CREATE INDEX idx_iot_threshold_rule_scope ON iot_threshold_rule(enabled, scope_type, device_type_code, device_id);

CREATE TABLE iot_alert (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    rule_id BIGINT REFERENCES iot_threshold_rule(id) ON DELETE SET NULL,
    alert_type VARCHAR(32) NOT NULL,
    severity VARCHAR(16) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active', -- active/acknowledged/resolved/closed
    dedupe_key VARCHAR(180) NOT NULL,
    title VARCHAR(160) NOT NULL,
    message TEXT NOT NULL,
    metric_code VARCHAR(64) REFERENCES iot_metric_definition(code),
    observed_value DOUBLE PRECISION,
    threshold_value DOUBLE PRECISION,
    triggered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    last_triggered_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    acknowledged_at TIMESTAMPTZ,
    acknowledged_by VARCHAR(100),
    resolved_at TIMESTAMPTZ,
    resolved_by VARCHAR(100),
    resolution_note TEXT,
    details JSONB NOT NULL DEFAULT '{}'::jsonb,
    CHECK (severity IN ('info','warning','critical')),
    CHECK (status IN ('active','acknowledged','resolved','closed'))
);
CREATE INDEX idx_iot_alert_status_time ON iot_alert(status, severity, triggered_at DESC);
CREATE INDEX idx_iot_alert_device_time ON iot_alert(device_id, triggered_at DESC);

CREATE TABLE iot_device_event (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT,
    field_id BIGINT REFERENCES field(id) ON DELETE SET NULL,
    event_type VARCHAR(40) NOT NULL,
    level VARCHAR(16) NOT NULL DEFAULT 'info',
    summary VARCHAR(240) NOT NULL,
    payload JSONB NOT NULL DEFAULT '{}'::jsonb,
    actor VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_iot_device_event_device_time ON iot_device_event(device_id, created_at DESC);

-- 7) 模拟设备配置与遥测去重。模拟设备仍使用 patrol_device 统一台账。
CREATE TABLE iot_simulator_profile (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL UNIQUE REFERENCES patrol_device(id) ON DELETE CASCADE,
    scenario_code VARCHAR(40) NOT NULL DEFAULT 'normal',
    sample_interval_seconds INT NOT NULL DEFAULT 60,
    random_seed BIGINT NOT NULL DEFAULT 20261128,
    config JSONB NOT NULL DEFAULT '{}'::jsonb,
    simulator_status VARCHAR(20) NOT NULL DEFAULT 'stopped', -- stopped/running/paused
    started_at TIMESTAMPTZ,
    paused_at TIMESTAMPTZ,
    last_generated_at TIMESTAMPTZ,
    CHECK (sample_interval_seconds BETWEEN 5 AND 86400),
    CHECK (simulator_status IN ('stopped','running','paused'))
);
CREATE TABLE iot_telemetry_dedupe (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE CASCADE,
    message_id VARCHAR(100) NOT NULL,
    boot_id VARCHAR(80),
    sequence_no BIGINT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    UNIQUE(device_id, message_id)
);
CREATE UNIQUE INDEX uq_iot_telemetry_device_boot_sequence
    ON iot_telemetry_dedupe(device_id, boot_id, sequence_no)
    WHERE boot_id IS NOT NULL AND sequence_no IS NOT NULL;
CREATE INDEX idx_iot_telemetry_dedupe_received ON iot_telemetry_dedupe(received_at);

-- 8) 设备凭证只保存哈希，不保存明文密钥。
CREATE TABLE iot_device_credential (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE CASCADE,
    credential_name VARCHAR(80) NOT NULL,
    credential_type VARCHAR(24) NOT NULL DEFAULT 'mqtt_password_hash',
    secret_hash VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_iot_device_credential_device ON iot_device_credential(device_id, enabled);

-- 9) 维护记录扩展为可跟踪的维护工单，不删除 V5 原表和历史。
ALTER TABLE patrol_device_maintenance ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'normal';
ALTER TABLE patrol_device_maintenance ADD COLUMN assigned_to VARCHAR(100);
ALTER TABLE patrol_device_maintenance ADD COLUMN due_at TIMESTAMPTZ;
ALTER TABLE patrol_device_maintenance ADD COLUMN resolution TEXT;
ALTER TABLE patrol_device_maintenance ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

-- 10) 查询索引与约束。
CREATE INDEX idx_patrol_device_last_seen ON patrol_device(status, last_seen_at);
CREATE INDEX idx_patrol_device_type_field ON patrol_device(device_type, field_id, status);
```

**迁移实施要求：** 执行迁移前核对已部署的 DDL 和 `flyway_schema_history`，备份现有数据库并记录业务表行数。全新库执行 V1–V6；已有 V5 的数据库通过 Flyway 增量执行 V6。升级后校验原设备、任务、飞行、地块和历史数据。生产环境禁止自动创建演示账号或开放匿名 MQTT。

### 1.3 InfluxDB 时序数据约定

- 新统一 measurement：`device_telemetry`，bucket：`sensors`。
- tags：`device_id`、`device_sn`、`field_id`、`device_type`、`source`。`source` 仅为 `simulated` 或 `real`；不要把设备名称、用户备注、任意高基数字段写成 tag。
- fields：设备能力表允许的指标值（例如 `soil_temp_c`、`soil_moisture_vwc_pct`、`soil_ec_us_cm`、`soil_ph`、`soil_n_mg_kg`、`soil_p_mg_kg`、`soil_k_mg_kg`、`air_temp_c`、`air_humidity_pct`、`light_lux`、`rainfall_mm`、`wind_speed_m_s`、`wind_direction_deg`、`leaf_wetness_pct`、`battery_pct`、`signal_strength_pct`），另存 `quality_code`。指标名称与单位以 `iot_metric_definition` 为准。
- 设备未上报的指标不写入，不允许填 0。历史聚合结果缺失时返回 `null`，前端显示“暂无数据”。
- `source=simulated` 必须写入 tag，所有查询响应都返回 source；页面必须标注“模拟数据”。
- 开发环境 bucket 保留期建议 365d，通过 `INFLUXDB_SENSORS_RETENTION` 配置；生产保留期需按数据量、法规和成本另行确认。历史原 `soil_sensor` measurement 不删除；兼容期内对旧 `sensor` 设备双写旧 measurement 或在查询层做映射，保证 `/api/iot/sensors/{sn}/history` 原接口继续可用。
- 新写入统一由 `TelemetryIngestionService` 进行设备、能力、单位、时间戳、质量码、幂等和权限校验，再写 InfluxDB；不允许模拟器绕过该服务。

---

---

## 2. 模块一：农田信息管理

### 2.1 API 定义

```
POST   /api/fields                  # 新建地块
GET    /api/fields                  # 分页查询（?page&size&keyword）
GET    /api/fields/{id}             # 地块详情
PUT    /api/fields/{id}             # 修改地块
DELETE /api/fields/{id}             # 删除地块；存在绑定设备时需先解绑/迁移

GET    /api/fields/{id}/events?page=0&size=20      # 农事记录分页，按农事日期倒序
POST   /api/fields/{id}/events      # 新增农事记录

GET    /api/fields/stats            # 汇总统计（总地块数、总面积、各品种占比）
```

### 2.2 请求/响应示例

```jsonc
// POST /api/fields  请求体
{
  "name": "东区3号田",
  "geometry": "{\"type\":\"Polygon\",\"coordinates\":[[[115.456,36.123],[115.460,36.123],[115.460,36.126],[115.456,36.126],[115.456,36.123]]]}",
  "soilType": "壤土",
  "variety": "花育25号",
  "sowDate": "2024-05-10",
  "ownerName": "张三"
}

// 响应 data 字段
{
  "id": 101,
  "name": "东区3号田",
  "areaMu": 12.35,          // 后端自动由 geometry 计算
  "soilType": "壤土",
  "variety": "花育25号",
  "sowDate": "2024-05-10",
  "currentGrowthStage": "花针期",   // 根据 sowDate 自动推算
  "daysAfterSow": 42
}
```

创建或修改地块时，`geometry` 使用序列化后的 GeoJSON 字符串。地块响应中的 `geometry` 始终是 GeoJSON 对象。统计接口的 `data` 为 `{totalCount, totalAreaMu, varietyStats:[{variety,count,areaMu}]}`。

本地后端与数据库启动后，在 `frontend/` 执行 `npm ci` 和 `npm run dev`，浏览器访问 `http://localhost:3000`。在 `frontend/.env.local` 中配置 `VITE_TIANDITU_TOKEN` 并重启前端可切换到天地图影像。未配置时使用 Esri 卫星影像。绘图使用 MapLibre 官方示例采用的 npm 包 `@mapbox/mapbox-gl-draw`。

### 2.3 生育期推算算法

```java
// backend/.../field/FieldService.java
public String calcGrowthStage(LocalDate sowDate) {
    int days = (int) ChronoUnit.DAYS.between(sowDate, LocalDate.now());
    if (days < 0)   return "未播种";
    if (days < 25)  return "苗期";
    if (days < 55)  return "花针期";
    if (days < 85)  return "结荚期";
    if (days < 120) return "饱果期";
    return "收获期";
}
```

### 2.4 面积计算

```java
// 在 Service 层，接收到 GeoJSON 后用 PostGIS 计算面积
// SQL：SELECT ST_Area(ST_Transform(ST_GeomFromGeoJSON(:geojson), 32650)) / 666.67 AS area_mu
// 32650 = WGS84 UTM Zone 50N，单位米，666.67 m² = 1 亩
```

### 2.5 验收标准

- [x] `POST /api/fields` 传入 GeoJSON Polygon，响应 `areaMu` 精度 ±0.1 亩
- [x] `GET /api/fields` 支持 `keyword` 模糊匹配地块名、品种名
- [x] `currentGrowthStage` 随系统日期动态变化，无需手动更新
- [x] 删除地块时，级联删除关联的 farm_event、patrol_task（ON DELETE CASCADE 已在 DDL 配置）
- [x] 在本地浏览器加载天地图真实影像，并验证圈地保存、地块高亮与详情、农事记录、生育期更新和面积统计。

使用 Java 21 在 `backend/` 执行 `mvn test` 可运行生育期单元测试。验证 PostGIS 面积及 API 时，准备一个安装了 PostGIS 和 pgvector 的临时 PostgreSQL 16 数据库，设置 `SPRING_DATASOURCE_URL`、`DB_USER`、`DB_PASS` 和 `PEANUT_TEST_POSTGIS=true`，再执行同一命令。

---

## 3. 模块二：智能巡田与农业物联网监测中心

### 3.0 产品定位与边界

模块二升级为整个花生种植平台的**设备接入、设备管理、环境监测、数据分析、模拟验证与智能巡田中心**。它不是只展示无人机飞行记录的页面。采用“设备台账 → 统一遥测接入 → 时序数据 → 曲线/统计 → 阈值告警 → 巡田/NDVI”的完整链路。

本模块分为七个一级页面：

1. **监测总览**：全场设备与地块运行驾驶舱。
2. **设备管理**：通用设备新增、编辑、分组、导入导出和地块绑定。
3. **传感器监测**：实时指标卡、历史曲线、设备/地块对比与数据导出。
4. **设备详情**：单台设备的资料、状态、指标、告警、维护和事件。
5. **告警中心**：指标越限、离线、低电量、数据超时和故障处置。
6. **模拟设备实验室**：模板化模拟设备、实时模拟数据、异常注入和场景演示。
7. **智能巡田与 NDVI**：保留原有巡田任务、无人机/机库、飞行记录、影像和 NDVI 流程。

**实施优先级：** 先做模拟设备与通用设备添加，再做统一遥测与曲线，然后做告警、总览/详情/维护，最后与原无人机巡田及 NDVI 回归联调。所有新增页面都必须接真实后端 API；模拟数据也必须从后端生成并进入统一入库链路，不允许仅在浏览器用随机数画曲线。

**职责边界：** `patrol_device` 管理无人机、机库和农业监测设备；模块五 `irrigation_device` 仍独立管理水肥控制器及其开关控制。模块二可以只读展示已关联的灌溉设备摘要，但不能向灌溉控制器下发启停命令。模块四处方执行设备另按其业务边界确认，不能因为通用设备注册表存在就自动假设某设备支持处方执行。

### 3.1 通用设备类型与能力模型

#### 3.1.1 设备类型可扩展

设备类型由 `iot_device_type` 注册表驱动，不得在前端写死成仅 `drone/dock/sensor`。V6 预置类型如下，管理员后续可在“设备类型与指标字典”管理中增加自定义类型：

| 类型 code | 显示名称 | 类别 | 默认能力示例 | 是否参与传感器曲线 |
|---|---|---|---|---|
| `drone` | 无人机 | aircraft | battery_pct、GPS、飞行阶段、多光谱影像 | 只显示设备状态/电量，不当作土壤传感器 |
| `dock` | 无人机机库 | gateway | battery/charging/door/online（按型号） | 不显示不支持的环境指标 |
| `sensor` | 旧版巡田传感器 | soil_sensor | 旧 soil_temp/soil_moisture/soil_n/p/k | 是，兼容旧指标映射 |
| `soil_multi_sensor` | 土壤多参数传感器 | soil_sensor | 土壤温度、含水率、EC、pH、N/P/K | 是 |
| `soil_moisture_sensor` | 土壤水分温度传感器 | soil_sensor | 土壤温度、体积含水率 | 是 |
| `weather_station` | 农业气象站 | weather | 空气温湿度、光照、降雨、风速风向 | 是 |
| `insect_light` | 智能虫情灯 | pest_monitor | 虫情计数、设备状态、图像引用 | 仅展示其具备的指标 |
| `spore_trap` | 孢子捕捉器 | pest_monitor | 孢子计数、设备状态、图像引用 | 仅展示其具备的指标 |
| `leaf_sensor` | 叶面湿度/光照传感器 | leaf_sensor | 叶面湿度、光照 | 是 |
| `other_sensor` | 其他环境传感器 | other | 管理员配置的指标集合 | 按能力展示 |

设备类型注册项至少包含：code、名称、类别、说明、默认采样间隔、默认离线阈值、能力 schema、启用状态。系统内置类型不可物理删除，只能停用；已被设备使用的自定义类型也只能停用，不能删除。新类型可配置是否要求地块绑定、是否有电池、是否有位置、默认协议和可选指标。

#### 3.1.2 指标字典与设备能力

指标字典由 `iot_metric_definition` 维护，设备实际支持的指标由 `iot_device_metric_capability` 维护。设备添加/编辑时选择其真实支持的指标，后端以设备能力表为准；前端根据能力动态生成实时卡片、曲线选项和详情字段。**不支持的指标不显示；未上报的指标显示 `--/暂无数据`，不许用 0 代替。**

| 指标 code | 展示名称 | 统一单位 | 说明 |
|---|---|---|---|
| `soil_temp_c` | 土壤温度 | °C | 土壤温度 |
| `soil_moisture_vwc_pct` | 土壤体积含水率 | %VWC | 必须区分体积含水率与相对湿度；旧 `soil_moisture` 仅在确认语义一致时映射 |
| `soil_ec_us_cm` | 土壤电导率 EC | µS/cm | 按设备校准换算 |
| `soil_ph` | 土壤 pH | pH | 0–14 标度 |
| `soil_n_mg_kg` / `soil_p_mg_kg` / `soil_k_mg_kg` | 土壤氮/磷/钾 | mg/kg | 传感器估算值需显示设备/校准说明，不得等同实验室检测 |
| `air_temp_c` / `air_humidity_pct` | 空气温度/湿度 | °C / % | 气象站 |
| `light_lux` | 光照强度 | lx | 照度 |
| `rainfall_mm` | 降雨量 | mm | 明确是累计值还是区间值 |
| `wind_speed_m_s` / `wind_direction_deg` | 风速/风向 | m/s / ° | 气象站 |
| `leaf_wetness_pct` | 叶面湿度 | % | 设备定义的相对湿润度 |
| `battery_pct` / `signal_strength_pct` | 电量/信号强度 | % / % | 仅设备支持时显示 |
| `insect_count` / `spore_count` | 虫情/孢子计数 | count | 可按设备提供日累计或区间计数 |

单位转换必须在后端接入层完成，并保存原始单位/转换记录到事件或遥测 metadata；所有图表只使用统一单位。指标的 `recommended_min/max` 仅用于图表参考和初始阈值建议，不得直接当作适用于所有花生品种、土壤和生育期的农艺诊断阈值。生产阈值应由用户/农艺师按地块、设备和生育期确认。

#### 3.1.3 通用设备添加向导

设备管理页右上角提供“添加设备”主按钮，点击进入 5 步向导：

1. **选择设备类型**：无人机、机库、土壤多参数、土壤水分温度、气象站、虫情灯、孢子捕捉器、叶面传感器、其他/自定义类型。
2. **基本信息**：设备名称（必填）、唯一 SN（必填）、厂商、型号、固件版本、备注、标签。
3. **接入与归属**：真实设备/模拟设备；MQTT/HTTP/LoRaWAN 网关/厂商适配器；地块、安装位置、经纬度；无人机可选绑定机库；机库不可绑定父设备。对于需要田块归属的传感器必须选择地块。
4. **指标能力与采样**：从指标字典勾选设备真实支持的指标；设定采样间隔；可选阈值模板。设备类型预设能力只作为默认建议，用户可按实际型号调整。
5. **确认与连接测试**：展示摘要；真实设备显示接入说明/凭证配置状态；模拟设备可直接创建并跳转模拟设备实验室。创建后初始在线状态为“待连接/离线”，不能伪造在线。

新增功能：手动新增、编辑、启用/停用、逻辑退役、批量 CSV 导入/导出、设备分组、绑定/解绑地块、批量修改标签/分组、设备类型管理、指标能力管理。CSV 导入先预览并给出逐行错误，不允许静默跳过；序列号重复时明确提示冲突行。逻辑退役保留所有任务、告警、遥测和维护历史。真实设备凭证只显示一次或支持重置，数据库只保存安全哈希/密钥引用，日志不得打印明文凭证。

无人机/机库关系沿用 `parent_device_id`：仅无人机可以关联机库，且关联目标必须是启用机库；地块一致性规则保留。巡田任务仍只选择已登记、启用且满足在线策略的无人机。设备类型扩展不改变原有巡田任务、飞行记录和 NDVI 关系。

### 3.2 后端数据接入与遥测规范

#### 3.2.1 统一遥测数据结构

所有模拟数据和真实设备数据必须经过同一个 `TelemetryIngestionService`。统一内部消息结构：

```json
{
  "deviceSn": "SOIL-DEMO-001",
  "messageId": "SOIL-DEMO-001-20261128T120000-0001",
  "bootId": "boot-2026-11-28-a",
  "sequence": 1001,
  "timestamp": "2026-11-28T12:00:00+08:00",
  "source": "simulated",
  "metrics": {
    "soil_temp_c": 24.6,
    "soil_moisture_vwc_pct": 31.8,
    "soil_ec_us_cm": 820,
    "soil_ph": 6.4,
    "soil_n_mg_kg": 82,
    "soil_p_mg_kg": 28,
    "soil_k_mg_kg": 116,
    "battery_pct": 91
  },
  "quality": {
    "soil_temp_c": "good",
    "soil_moisture_vwc_pct": "good"
  },
  "units": {
    "soil_temp_c": "°C",
    "soil_moisture_vwc_pct": "%VWC"
  }
}
```

字段规则：
- `deviceSn` 必须匹配已登记且启用设备；未知 SN 不自动注册。
- `messageId` 必填且在设备范围内幂等；`sequence` 可选但若提供必须在同一 `bootId` 周期内单调递增。设备重启后 `bootId` 变化，避免序号重置造成误判。重复消息拒绝重复写入。
- `timestamp` 允许 ISO 8601，服务端统一转 UTC；缺失时可以用接收时间补齐并记录 `timestamp_inferred=true`。明显超出允许时钟偏差的消息标记 `suspect`，不参与告警判断，原始数据仍可按配置隔离保存。
- `source` 由接入通道判定，外部设备不能自行把真实数据伪装成模拟数据，或反向伪装；模拟器内部服务固定写 `simulated`，认证 MQTT 通道写 `real`。
- `metrics` 只能包含设备能力表允许的指标；值必须为有限数值（不得 NaN/Infinity）；超出物理/协议范围的值按策略拒绝或标记 `bad`，不能静默裁剪。
- `quality` 允许 `good/suspect/bad`；缺少 quality 时默认 `good`，但服务端校验后可降级。缺失指标不写入、不补 0。
- `units` 如有提供，必须与指标字典一致或存在显式换算器；无法转换时拒绝该指标并产生设备事件。

#### 3.2.2 接入通道与 MQTT topic

- 模拟器：由后端 `SimulatorService` 生成消息，直接调用 `TelemetryIngestionService.ingest()`；阶段 A 就必须完成最小可用链路（设备/能力校验、幂等、写入 InfluxDB、更新最新状态和 source 标记），使模拟设备创建后可以真实产生并持久化数据；阶段 B 再完善真实 MQTT 适配、历史聚合、对比查询和实时曲线。若需要演示外部 MQTT，可提供受控模拟发布器，但仍走同一个消费者和校验流程。
- 真实设备新标准 topic：`iot/{device_sn}/telemetry`、`iot/{device_sn}/status`。
- 旧巡田传感器 topic `sensor/{device_sn}/data` 保留兼容适配器，映射旧指标 key 到统一指标字典；无人机/机库仍使用 `drone/{sn}/status`、`dock/{sn}/status`，下发仍用原 `drone/{sn}/cmd/task`。
- MQTT QoS 1；消息按 messageId/sequence 幂等。设备凭证按设备单独配置，EMQX ACL 限制每个设备只能发布自己的 telemetry/status topic，不能订阅其他设备或向控制 topic 发布。生产真实设备使用 TLS；开发模拟器使用内部调用，不向浏览器暴露 Broker 管理凭证。
- 未登记、停用、退役设备的 MQTT 消息不得更新在线状态或写入时序库；写入拒绝事件并可触发安全告警。

#### 3.2.3 在线、数据新鲜度和离线判断

`online` 不是人工编辑字段。优先使用设备 status/heartbeat；对仅周期上报的传感器，以 `last_seen_at` 和采样间隔判断。离线阈值建议 `max(3 × sample_interval_seconds, device_type.default_offline_timeout_seconds)`，无人机可按业务阶段使用 90 秒默认值，机库按 180 秒默认值；设备类型和单设备可覆盖。总览中区分：在线、离线、从未连接、数据过期、维护中、已停用、已退役。数据新鲜度显示“刚刚/几分钟前/已过期”，不能只用在线布尔值掩盖数据停止更新。

#### 3.2.4 InfluxDB 写入与历史查询

统一写入 `device_telemetry` measurement，tags/fields/保留期见第 1.3 节。由设备台账解析 `field_id` 和 `device_type`，不信任 payload 自带 fieldId。写入前验证能力、单位、质量码、时间戳和去重；写入失败进入可重试队列或日志，不能先更新 UI 假装成功。

旧 `GET /api/iot/sensors/{sn}/history?start=&end=&interval=` 保留原响应字段 `soil_temp/soil_moisture/soil_n/soil_p/soil_k`，在服务层映射新 measurement/metric code；新通用接口返回 metric code、单位、source、quality 和时间戳。兼容期内允许对旧 `sensor` 类型双写 `soil_sensor` measurement，但必须有明确迁移/下线计划，不能删除旧历史数据。

### 3.3 模拟设备实验室（第一优先级）

#### 3.3.1 设备模板与创建

实验室提供模板：土壤多参数传感器、土壤水分温度传感器、农业气象站、叶面湿度传感器、虫情灯、孢子捕捉器，以及无人机/机库状态模拟器。点击“创建模拟设备”后调用后端创建 `patrol_device.access_mode='simulated'`、生成唯一 demo SN、写入设备能力表和 `iot_simulator_profile`，而不是在前端临时创建假设备。

创建配置包括：设备名称、模板、绑定地块、安装位置、采样间隔（5 秒至 24 小时，默认 60 秒）、指标基线、波动幅度、趋势方向、昼夜周期、随机种子。设备卡片、列表、曲线、告警、导出数据都要有明显的“模拟数据”标识。

#### 3.3.2 模拟场景

内置场景：
- `normal` 正常运行：指标围绕基线合理波动。
- `soil_dry` 土壤缺水：土壤含水率逐步下降并触发可配置低湿度告警。
- `high_temperature` 高温：空气/土壤温度上升，验证阈值告警。
- `device_offline` 设备离线：停止心跳和遥测，验证离线检测与恢复。
- `low_battery` 低电量：电量缓慢下降，验证低电量告警。
- `sensor_stuck` 传感器卡死：某指标保持恒定，触发数据异常/卡死规则（若该规则启用）。
- `spike` 异常尖峰：注入单次异常值，验证质量码、阈值持续时间和防抖。

每个场景可配置持续时间、影响指标、幅度和恢复方式；支持开始、暂停、继续、停止、重置、单次注入、批量启动。可加载一组种子数据/历史回放，但回放数据也要通过 `TelemetryIngestionService`，并保留原始时间或显式标记 replay，不得混淆实时数据。

#### 3.3.3 模拟器运行机制

`SimulatorJob` 按 profile 采样周期生成 telemetry；使用固定 seed 的随机扰动，保证验收可重复；支持缓慢趋势与昼夜正弦变化。模拟器调用与 MQTT 一样的 `TelemetryIngestionService`，所以设备在线、last_seen、InfluxDB、WebSocket、告警引擎和事件日志全部使用相同链路。模拟数据 source 固定为 `simulated`，禁止客户端请求体覆盖 source。

模拟器停止时，设备可选择“保持最后状态”或“按离线阈值自动下线”；场景 device_offline 必须停止心跳。暂停与停止的含义在 UI 明确区分。模拟设备可退役和删除 profile，但设备历史告警、事件与遥测仍保留。

### 3.4 后端 API 定义

统一响应仍使用 `ApiResult<T>`；分页仍使用 `page/size/sort`。以下 API 为新增或扩展目标，实施前核对现有路由，避免覆盖已存在接口。

```text
# 设备类型与指标能力
GET    /api/patrol/device-types
POST   /api/patrol/device-types                  # 管理员新增自定义类型
PUT    /api/patrol/device-types/{code}
GET    /api/iot/metrics                          # 指标字典与单位
POST   /api/iot/metrics                          # 管理员新增自定义指标
PUT    /api/iot/metrics/{code}                    # 管理员修改指标元数据

# 通用设备台账
POST   /api/patrol/devices
GET    /api/patrol/devices?deviceType=&fieldId=&status=&online=&source=&keyword=&groupId=&page=&size=
GET    /api/patrol/devices/stats
GET    /api/iot/devices/map?fieldId=              # 地图设备标记与摘要
GET    /api/patrol/devices/{id}
PUT    /api/patrol/devices/{id}
POST   /api/patrol/devices/{id}/enable
POST   /api/patrol/devices/{id}/disable
POST   /api/patrol/devices/{id}/retire
POST   /api/patrol/devices/{id}/credentials/rotate # 管理员轮换设备凭证
POST   /api/patrol/devices/import/preview           # CSV multipart，校验并返回逐行预览，不写库
POST   /api/patrol/devices/import/execute           # 用户确认预览后执行导入
GET    /api/patrol/devices/import/{jobId}           # 导入结果
GET    /api/patrol/devices/export                   # CSV，受权限和筛选条件限制

# 设备分组
GET    /api/patrol/device-groups
POST   /api/patrol/device-groups
PUT    /api/patrol/device-groups/{id}
DELETE /api/patrol/device-groups/{id}               # 仅删分组，不删设备
PUT    /api/patrol/device-groups/{id}/members       # 设置设备成员

# 设备维护/工单
POST   /api/patrol/devices/{id}/maintenance
GET    /api/patrol/devices/{id}/maintenance?page=
PUT    /api/patrol/maintenance/{maintenanceId}

# 传感器监测/遥测
GET    /api/iot/overview?fieldId=&deviceType=
GET    /api/iot/telemetry/latest?deviceId=&fieldId=
GET    /api/iot/telemetry/history?deviceIds=&fieldId=&metrics=&start=&end=&interval=
GET    /api/iot/telemetry/summary?deviceId=&metric=&start=&end=
GET    /api/iot/telemetry/compare?deviceIds=&metric=&start=&end=&interval=
GET    /api/iot/devices/{id}/telemetry/table?start=&end=&page=

# 阈值规则与告警
GET    /api/iot/alert-rules?deviceType=&deviceId=
POST   /api/iot/alert-rules
PUT    /api/iot/alert-rules/{id}
POST   /api/iot/alert-rules/{id}/enable
POST   /api/iot/alert-rules/{id}/disable
GET    /api/iot/alerts?status=&severity=&fieldId=&deviceId=&start=&end=&page=
GET    /api/iot/alerts/{id}
POST   /api/iot/alerts/{id}/acknowledge
POST   /api/iot/alerts/{id}/resolve
POST   /api/iot/alerts/{id}/close

# 模拟设备实验室
GET    /api/iot/simulator/templates
POST   /api/iot/simulator/devices
GET    /api/iot/simulator/devices
POST   /api/iot/simulator/devices/{id}/start
POST   /api/iot/simulator/devices/{id}/pause
POST   /api/iot/simulator/devices/{id}/resume
POST   /api/iot/simulator/devices/{id}/stop
POST   /api/iot/simulator/devices/{id}/reset
POST   /api/iot/simulator/devices/{id}/inject
POST   /api/iot/simulator/scenarios/{code}/run
POST   /api/iot/simulator/replay

# 原接口保持兼容
GET    /api/iot/sensors?fieldId=
GET    /api/iot/sensors/{sn}/latest
GET    /api/iot/sensors/{sn}/history?start=&end=&interval=
```

设备新增请求示例：

```json
{
  "deviceType": "soil_multi_sensor",
  "deviceSn": "SOIL-DEMO-001",
  "deviceName": "东区 3 号田土壤多参数传感器",
  "brand": "Demo Vendor",
  "model": "Soil-Multi-V1",
  "accessMode": "simulated",
  "protocol": "simulator",
  "fieldId": 101,
  "installLocation": "东区 3 号田北侧",
  "latitude": 36.124,
  "longitude": 115.457,
  "sampleIntervalSeconds": 60,
  "metricCodes": ["soil_temp_c", "soil_moisture_vwc_pct", "soil_ec_us_cm", "soil_ph", "soil_n_mg_kg", "soil_p_mg_kg", "soil_k_mg_kg", "battery_pct"],
  "tags": ["演示区", "土壤监测"],
  "remark": "模拟设备，用于功能验收"
}
```

设备详情响应至少包含：`id/deviceSn/deviceName/deviceType/deviceTypeName/brand/model/accessMode/protocol/status/online/lastSeenAt/lastTelemetryAt/batteryPct/signalStrengthPct/field/parentDevice/capabilities/latestMetrics/activeAlerts/maintenanceSummary`。`latestMetrics` 只包含实际支持且有有效数据的指标，每个指标含 `value/unit/timestamp/quality/source`。

历史曲线接口返回：`[{timestamp, metricCode, value, unit, quality, source}]` 或按设备/指标分组的等价结构。聚合粒度支持 `1m/5m/15m/1h/6h/1d`，后端根据时间范围选择合理粒度，禁止无上限返回原始点。对比接口最多允许 5 台设备同指标对比，避免前端请求过大。

### 3.5 告警规则与告警处置

告警类型：设备离线、低电量、低信号、指标越上/下限、数据超时、设备故障、未知设备接入、遥测校验失败。规则可作用于全局、设备类型或单台设备；设备级规则优先级最高。规则支持指标、比较操作符、阈值、持续时间、防抖冷却时间、恢复阈值（滞回）和严重级别 info/warning/critical。

- 指标越限需满足持续时间后才触发，避免单个尖峰造成大量告警；恢复阈值与触发阈值分开配置，避免临界值附近频繁开关。
- 离线规则根据采样间隔/设备类型超时判定；设备恢复上报后记录恢复事件并把活动告警转为 resolved，不删除历史告警。
- 低电量默认 20% 为演示建议值，不是所有设备统一强制值；用户可按型号修改。
- 告警中心支持确认（acknowledge）、解决（resolve）、关闭（close）和备注；保留操作人、时间、处置说明。关闭不等于恢复，仍需保存恢复状态。
- 第一阶段告警在 Web 告警中心展示；邮件/短信/微信推送列为后续扩展，不得假称已配置外部通知。

### 3.6 前端页面、布局与视觉规范

#### 3.6.1 整体设计方向

采用专业、清爽、现代的农业 SaaS 控制台风格。整体以浅色背景、白色内容卡片、深灰文字、农业绿作为主色；在线/正常使用绿色，告警使用琥珀色/橙色，严重故障使用红色，离线/未知使用中性灰，信息提示使用蓝色。颜色只作为状态辅助，必须同时显示文字/图标，不能只靠颜色表达。

桌面端采用左侧固定导航 + 顶部全局栏 + 内容区；内容区统一标题、面包屑、筛选工具条、主操作按钮。卡片适度使用，不要堆满 KPI；重要内容优先，留白和对齐统一。地图与列表联动。页面宽度在 1440/1280/1024 下保持可读；平板将双栏变上下布局；手机端表格转换为卡片列表、筛选器收纳为抽屉，主要操作保持可用。

所有页面必须实现：首次加载 skeleton、空数据引导、接口错误重试、无权限提示、离线/数据过期状态、模拟数据标识、操作成功/失败反馈、危险操作二次确认。不能用静态 mock 列表冒充后端结果。开发 mock 只能在明确的前端 Storybook/组件测试环境使用，不能混入验收运行环境。

#### 3.6.2 页面一：监测总览驾驶舱 `IotOverview.vue`

顶部：标题“农业物联网监测中心”、当前农场/地块筛选、时间范围和“添加设备”快捷入口。第一行显示 5 个核心摘要：设备总数、在线、离线、活动告警、维护中；在线率和数据新鲜度以辅助信息呈现。第二行左侧为地块设备分布地图（MapLibre 复用主地图，按设备类型图标和状态标记），右侧为关键指标最新值（仅展示当前场地确有设备支持的指标）。第三行展示最近告警、最近设备事件、设备类型分布/趋势。点击 KPI 可跳转设备列表并自动应用筛选；点击地图标记打开设备摘要抽屉；点击告警进入告警详情。

#### 3.6.3 页面二：通用设备管理 `DeviceManagement.vue`

顶部为页面标题、设备统计摘要和“添加设备”“批量导入”“导出”按钮。筛选条支持关键字/SN、设备类型、地块、在线状态、业务状态、真实/模拟、分组。列表列：设备名称/SN、类型、地块/安装点、在线状态、最近上报、电量/信号（设备支持时）、数据来源、业务状态、操作。支持分页、排序、列显隐和批量选择。新增/编辑用 5 步向导，不同类型动态显示字段；能力指标通过复选项选择。退役采用二次确认并说明影响，不允许物理删除历史。

#### 3.6.4 页面三：传感器监测中心 `SensorMonitoring.vue`

顶部筛选：地块、设备、多选指标、时间范围（1h/6h/24h/7d/30d/自定义）、采样聚合粒度、数据来源（全部/真实/模拟）。指标卡显示最新值、单位、较上一周期变化、最后上报时间和数据质量。主区域为可切换“曲线/数据表”视图：默认每个单位/量纲独立分图；用户可选择最多两个量纲使用双 Y 轴，不能把 °C、%、mg/kg 混在同一 Y 轴。图表必须有清楚时间轴、图例、单位、tooltip、阈值线、缩放/拖动、图例显隐；支持设备/地块最多 5 组对比。下方统计显示最新/均值/最小/最大/变化趋势/缺失率。支持 CSV 导出，导出含 timestamp、deviceSn、field、metric、value、unit、quality、source。

曲线缺失点保留断线，不做默认补零；聚合数据缺失返回 null。默认时间范围 24h，默认选中 1–3 个最重要指标，避免首次加载过载。时间范围、设备、指标切换时展示加载态并取消过期请求。

#### 3.6.5 页面四：设备详情 `DeviceDetail.vue`

页头显示设备名称、SN、类型、在线/离线、真实/模拟标识和快捷操作（编辑、启用/停用、维护登记）。内容使用 Tabs：概览、实时指标、历史曲线、告警、维护工单、遥测原始记录、事件/操作日志、连接配置。概览显示地块/位置地图、厂商型号、协议、采样间隔、固件、电量/信号、最近心跳、最近数据时间、能力列表。实时指标只渲染设备 capabilities。原始遥测提供时间、messageId、quality、source 和指标 JSON，凭证信息脱敏。

#### 3.6.6 页面五：告警中心 `AlertCenter.vue`

顶部显示活动告警数和严重告警数；筛选设备/地块/类型/严重级别/状态/时间。列表列：级别、标题、设备、地块、指标/当前值/阈值、触发时间、持续时间、状态、处理人。右侧抽屉显示规则、触发曲线片段、设备状态、事件上下文和处置历史；支持确认、解决、关闭、添加备注。规则管理独立抽屉/页签，支持新增、编辑、启停、试算规则。模拟告警须显示“模拟数据”来源。

#### 3.6.7 页面六：模拟设备实验室 `SimulatorLab.vue`

页面分三栏：左侧设备模板/场景预设，中间模拟设备列表和运行状态，右侧当前选中设备的指标曲线和控制台。支持创建模拟设备、选择地块和能力、设定采样间隔/基线/波动/趋势/昼夜周期、开始/暂停/继续/停止/重置、单次异常注入。预置场景一键启动，显示场景描述、预计触发指标和告警；不显示“真实在线”，统一标识“模拟运行中”。提供消息/遥测最近生成日志和告警联动链接。所有操作调用模拟器 API，实时图表读取 InfluxDB/实时推送接口。

#### 3.6.8 页面七：智能巡田与 NDVI（保留）

保留现有 `PatrolTask.vue`、`FlightList.vue`、`NdviMap.vue` 的已验收功能。巡田任务选择已登记且符合条件的无人机；无人机详情显示关联机库。飞行记录、影像上传和 NDVI 处理逻辑不重写。地图设备图层读取统一设备 API，传感器图层可点击跳转传感器详情。传感器趋势页升级为新的 SensorMonitoring，但原 SensorDashboard 旧路由可重定向或保留入口。

#### 3.6.9 组件、路由与图表依赖

建议组件：`IotOverview.vue`、`DeviceManagement.vue`、`DeviceWizard.vue`、`DeviceDetail.vue`、`SensorMonitoring.vue`、`AlertCenter.vue`、`AlertRuleForm.vue`、`SimulatorLab.vue`、`DeviceMapLayer.vue`、`MetricCard.vue`、`TelemetryChart.vue`、`TelemetryDataTable.vue`、`DeviceStatusTag.vue`、`SimulationBadge.vue`。

路由建议：`/patrol/overview`、`/patrol/devices`、`/patrol/devices/:id`、`/patrol/sensors`、`/patrol/alerts`、`/patrol/simulator`、`/patrol/tasks`、`/patrol/flights`、`/patrol/ndvi`。保留旧路由别名，避免书签失效。

图表库先检查 `frontend/package.json` 和现有组件。如果已有 ECharts/Chart.js 等库，优先复用现有依赖；如果没有，提交选型理由后优先考虑 ECharts（多轴、dataZoom、tooltip、markLine、dataZoom slider 和表格切换能力），经用户确认再安装。不得为了图表重写现有地图/组件栈。

### 3.7 实时推送与权限

实时推送优先复用现有 Spring WebSocket/STOMP 能力：`/topic/iot/field/{fieldId}/telemetry`、`/topic/iot/device/{deviceId}/status`、`/topic/iot/alerts`。服务端必须校验用户对地块/设备的访问权限后才允许订阅；不能让前端任意订阅其他用户地块。

权限沿用项目现有 JWT/角色体系，不凭空重建用户系统。至少区分：设备查看、设备新增/编辑、设备退役、设备类型管理、告警处置、模拟器控制、维护工单管理。模拟器控制与设备凭证管理仅对管理员/授权角色开放。若现有系统尚无细粒度权限，先实现服务端角色校验并在实施计划中列出最小可用权限矩阵，等待用户确认后再扩展。

### 3.8 验收标准

**原模块二基础能力（已验收，历史状态保持）：**
- [x] 模拟无人机 cron 与手动 MQTT 任务下发、飞行状态转换已验收。
- [x] 飞行结束进入影像处理，NDVI 成功后状态变为 `done`。
- [x] NDVI 三色占比之和为 100%（±0.1）。
- [x] 原 InfluxDB 传感器写入、1m/5m/1h 聚合接口和土壤趋势页已验收。

**本次模块二增强项（按阶段实际测试结果勾选）：**
- [x] V6 迁移成功；V1–V5 历史迁移文件不变；原设备、任务、飞行、地块和历史数据行数一致。
- [x] 设备类型注册表支持内置类型和管理员自定义类型；设备类型被使用时不能物理删除。
- [x] 通用添加设备向导支持不同类型动态字段、指标能力、地块/机库关系和真实/模拟接入方式。
- [x] 设备列表支持搜索、筛选、分页、编辑、启停、逻辑退役、分组、CSV 预览导入和导出。
- [x] 设备指标能力动态渲染；未上报/不支持的指标不显示或显示“暂无数据”，绝不补 0。
- [x] 模拟设备模板创建成功，模拟设备进入统一台账并明确标记“模拟数据”。
- [x] 模拟设备 start/pause/resume/stop/reset 正常；场景 normal/soil_dry/high_temperature/device_offline/low_battery/sensor_stuck/spike 可重复验收。
- [x] 阶段 A 模拟遥测由后端生成，经过统一入口校验、幂等、InfluxDB 写入及最新状态更新。
- [ ] 真实 MQTT 设备进入统一入口，并接入 WebSocket 和告警链路。
- [ ] 传感器监测页支持 1h/6h/24h/7d/30d/自定义范围、多指标曲线、单位/图例/tooltip/阈值线/缩放、表格和 CSV 导出。
- [ ] 设备/地块对比最多 5 组；不同量纲分图或明确双 Y 轴，不混用单位。
- [ ] 告警规则支持持续时间、防抖、恢复阈值；告警可确认/解决/关闭并保留操作历史。
- [ ] 总览驾驶舱统计与数据库一致，地图标记、设备列表和告警详情联动。
- [ ] 设备详情展示能力、实时/历史指标、告警、维护、遥测原始记录和事件日志；敏感凭证脱敏。
- [ ] 设备离线、低电量、低信号、数据超时、指标越限、传感器卡死等场景触发预期告警并能恢复。
- [ ] 页面有加载、空数据、错误、无权限、离线、数据过期和模拟数据状态；桌面/平板/手机布局可用。
- [ ] 原 `/api/iot/sensors/{sn}/history`、巡田任务、MQTT 飞行下发、NDVI 地图回归测试全部通过。
- [ ] 新建巡田任务只能选择已登记且可用的无人机；机库关系正确；模块二不控制模块五灌溉设备。

### 3.9 分阶段实施顺序

| 阶段 | 范围 | 后端交付 | 前端交付 | 阶段验收后 |
|---|---|---|---|---|
| A | 设备类型/指标字典、通用设备 CRUD、模拟设备实验室 | V6 迁移、设备类型/能力 API、模拟器配置与控制、统一模拟数据生成入口 | 设备管理、通用添加向导、模拟设备实验室 | 停止并等待用户确认 |
| B | 统一遥测链路与曲线监测 | 遥测接入、校验/幂等、InfluxDB、历史/聚合/对比 API、实时推送 | 传感器监测中心、指标卡、曲线/表格/导出 | 停止并等待用户确认 |
| C | 阈值规则与告警 | 规则引擎、离线扫描、告警状态流转、告警 API | 告警中心、规则编辑、告警详情/处置 | 停止并等待用户确认 |
| D | 总览、详情、维护、分组和批量操作 | 统计 API、维护工单、设备事件/审计、CSV 导入导出 | 总览驾驶舱、设备详情、维护/分组/导入导出 | 停止并等待用户确认 |
| E | 与原巡田/NDVI 联调 | 无人机/机库兼容、旧传感器 API 映射、任务设备校验 | 原巡田/飞行/NDVI 页面整合与地图联动 | 停止并等待用户确认 |

---

## 4. 模块三：病虫害识别与预警

### 4.1 API 定义

```
POST   /api/pest/detect/upload       # 手动上传图片检测
GET    /api/pest/detections          # 检测记录列表（?fieldId&start&end&page）
GET    /api/pest/detections/{id}     # 检测详情
POST   /api/pest/detections/{id}/expert   # 提交专家复核
```

### 4.2 AI 检测服务接口（FastAPI）

> 文件路径：`ai-service/pest_detection/main.py`

```python
from fastapi import FastAPI, UploadFile
from ultralytics import YOLO
import cv2, numpy as np

app = FastAPI()
model = YOLO("weights/peanut_pest_v8.pt")  # 训练好的权重

LABEL_MAP = {
    0: "叶斑病", 1: "锈病", 2: "茎腐病", 3: "白绢病",
    4: "蚜虫",   5: "红蜘蛛", 6: "蛴螬", 7: "棉铃虫"
}

@app.post("/detect")
async def detect(file: UploadFile, flight_id: int = None):
    img_bytes = await file.read()
    nparr = np.frombuffer(img_bytes, np.uint8)
    img = cv2.imdecode(nparr, cv2.IMREAD_COLOR)

    results = model(img, conf=0.45, iou=0.5)[0]

    detections = []
    for box in results.boxes:
        cls_id = int(box.cls.item())
        detections.append({
            "type": "disease" if cls_id < 4 else "insect",
            "name": LABEL_MAP[cls_id],
            "confidence": round(float(box.conf.item()), 3),
            "bbox": [int(x) for x in box.xyxy[0].tolist()]
        })

    return {"detections": detections, "count": len(detections)}
```

### 4.3 预警推送逻辑（后端 Java）

```java
// PestAlertService.java
// 规则：同一地块同一病害，24小时内不重复推送
public void processAlert(PestDetection detection) {
    List<Detection> items = detection.getDetections();
    if (items == null || items.isEmpty()) return;

    boolean alreadySent = pestDetectionRepo.existsAlertWithin24h(
        detection.getFieldId(),
        items.stream().map(Detection::getName).toList()
    );
    if (alreadySent) return;

    // 构造推送消息
    String msg = buildAlertMsg(detection);
    wechatNotifyService.send(detection.getFieldId(), msg);  // 推送至绑定手机
    detection.setAlertSent(true);
    pestDetectionRepo.save(detection);
}

private String buildAlertMsg(PestDetection d) {
    return String.format(
        "【病虫害预警】%s 检测到 %s，建议尽快查看。位置：%s",
        d.getField().getName(),
        d.getDetections().stream()
            .map(Detection::getName)
            .distinct()
            .collect(Collectors.joining("、")),
        d.getImageUrl()
    );
}
```

### 4.4 检测结果 JSON 结构（存入 `pest_detection.detections`）

```json
[
  {
    "type": "disease",
    "name": "叶斑病",
    "confidence": 0.91,
    "bbox": [1024, 768, 1280, 960],
    "geoLocation": {"lat": 36.123, "lng": 115.456},
    "areaMu": 0.08
  }
]
```

### 4.5 实现约束

- YOLO 检测需要真实花生病虫害模型权重 `weights/peanut_pest_v8.pt`。权重缺失时应返回明确的 `MODEL_NOT_CONFIGURED`/503，或进入有明显“演示数据”标识的模拟模式；不得用通用 YOLOv8n 权重冒充花生病虫害识别结果。
- 巡田影像若为多光谱 GeoTIFF，需先提取/转换为模型要求的 RGB 图像再检测；不能把 TIFF 文件未经验证直接交给仅支持普通图片的检测流程。
- 只有飞行影像带有可靠地理参考、或人工上传图片提供可信 GPS EXIF/人工定位时，才写入 `geoLocation`；无坐标的检测结果仍可在列表和详情中查看，但不应出现在地图定位层。
- 24 小时去重应按“地块 + 单个病害类型”分别判断，不能因为某条记录中一种病害已推送，就抑制同一批次内其他新病害的首次预警。

### 4.6 验收标准

- [ ] 上传图片 → 2 秒内返回检测结果
- [ ] 同一地块同一病害 24 小时内只推送一次预警
- [ ] 检测结果在前端地图上以红色圆点标注（含 bbox 预览弹窗）
- [ ] 支持 `source=flight` 的自动检测记录与 `source=manual_upload` 的人工上传记录统一管理；普通图片无地理坐标时不伪造 geoLocation

---

## 5. 模块四：智能分析与处方图生成

### 5.1 API 定义

```
POST   /api/prescriptions                   # 基于 flightId 自动生成处方图
GET    /api/prescriptions?fieldId=&page=    # 处方图列表
GET    /api/prescriptions/{id}              # 处方图详情（含 zones GeoJSON）
PUT    /api/prescriptions/{id}/zones        # 编辑分区作业量
POST   /api/prescriptions/{id}/confirm      # 确认并下发执行
```

### 5.2 处方图生成算法（Python）

> 文件路径：`ai-service/prescription/generator.py`

```python
import numpy as np
import rasterio
from scipy.interpolate import griddata
from shapely.geometry import shape, mapping
import geopandas as gpd

# ---- 施肥推荐量查表（kg/亩） ----
FERT_TABLE = {
    # (ndvi_grade, n_grade) -> (N, P, K) kg/亩
    ("low",    "low"):    (8.0, 5.0, 6.0),
    ("low",    "medium"): (6.0, 4.0, 5.0),
    ("low",    "high"):   (4.0, 3.0, 4.0),
    ("medium", "low"):    (5.0, 4.0, 5.0),
    ("medium", "medium"): (4.0, 3.0, 4.0),
    ("medium", "high"):   (3.0, 2.0, 3.0),
    ("high",   "low"):    (3.0, 3.0, 4.0),
    ("high",   "medium"): (2.0, 2.0, 3.0),
    ("high",   "high"):   (1.0, 1.0, 2.0),
}

def ndvi_grade(val: float) -> str:
    if val < 0.3: return "low"
    if val < 0.6: return "medium"
    return "high"

def n_grade(val: float) -> str:
    """土壤速效氮 mg/kg"""
    if val < 60: return "low"
    if val < 100: return "medium"
    return "high"

def generate_prescription(
    ndvi_tif: str,          # NDVI GeoTIFF 路径
    soil_points: list,      # [{"lat":, "lng":, "n_mgkg":}, ...]
    field_geojson: dict,    # 地块 GeoJSON Polygon
    map_type: str = "fertilize"   # fertilize / spray
) -> dict:
    """
    返回 GeoJSON FeatureCollection，每个 Feature 为一个管理分区
    Feature.properties 含 amount_n, amount_p, amount_k（单位 kg/亩）
    """
    with rasterio.open(ndvi_tif) as src:
        ndvi_arr = src.read(1)
        transform = src.transform
        crs = src.crs

    # 1. 将地块分为 N×N 网格
    field_poly = shape(field_geojson)
    bounds = field_poly.bounds  # (minx, miny, maxx, maxy)
    grid_n = 10  # 10×10 网格
    xs = np.linspace(bounds[0], bounds[2], grid_n)
    ys = np.linspace(bounds[1], bounds[3], grid_n)

    # 2. 在每个网格中心采样 NDVI 均值
    zones = []
    for i in range(grid_n - 1):
        for j in range(grid_n - 1):
            cell = {
                "type": "Polygon",
                "coordinates": [[
                    [xs[i], ys[j]], [xs[i+1], ys[j]],
                    [xs[i+1], ys[j+1]], [xs[i], ys[j+1]],
                    [xs[i], ys[j]]
                ]]
            }
            cell_poly = shape(cell)
            if not cell_poly.intersects(field_poly):
                continue

            # 采样该格内 NDVI 均值（通过 rasterio window）
            row_off, col_off = rasterio.transform.rowcol(
                transform, xs[i], ys[j]
            )
            cell_ndvi = float(np.nanmean(ndvi_arr[
                max(0, row_off-2):row_off+2,
                max(0, col_off-2):col_off+2
            ]))

            # 插值土壤氮值
            cell_cx = (xs[i] + xs[i+1]) / 2
            cell_cy = (ys[j] + ys[j+1]) / 2
            pts = np.array([[p["lng"], p["lat"]] for p in soil_points])
            vals = np.array([p["n_mgkg"] for p in soil_points])
            cell_n = float(griddata(pts, vals, (cell_cx, cell_cy), method="linear"))

            # 查表得施肥量
            ng = ndvi_grade(cell_ndvi)
            nn = n_grade(cell_n if not np.isnan(cell_n) else 80)
            n_kg, p_kg, k_kg = FERT_TABLE[(ng, nn)]

            zones.append({
                "type": "Feature",
                "geometry": mapping(cell_poly.intersection(field_poly)),
                "properties": {
                    "ndvi_mean": round(cell_ndvi, 3),
                    "ndvi_grade": ng,
                    "n_grade": nn,
                    "amount_n": n_kg,
                    "amount_p": p_kg,
                    "amount_k": k_kg
                }
            })

    return {"type": "FeatureCollection", "features": zones}
```

### 5.3 处方图下发（MQTT）

```
// 当前为协议占位符：device/{device_sn}/cmd/prescription 尚无对应通用设备台账。
// 实现 confirm 前必须确认执行设备类型、能力和最终 topic；未确认时只允许生成/编辑草稿，不得真实下发。
{
  "prescriptionId": 88,
  "mapType": "fertilize",
  "zones": [
    {"zoneId": 1, "amount_n": 4.0, "amount_p": 3.0, "amount_k": 4.0}
  ]
}
```

### 5.4 验收标准

- [ ] 传入 flightId → 30 秒内生成处方图（含 zones GeoJSON）
- [ ] 处方图前端可视化：每个分区按施肥量深浅着色，hover 显示具体用量
- [ ] 编辑分区量后调用 `PUT /prescriptions/{id}/zones`，状态保持 `draft`
- [ ] `confirm` 后状态先变为 `confirmed`；设备确认后进入 `executing`，执行完成后变为 `executed`，失败为 `failed`；已确认的处方图不可再编辑。执行设备类型、topic 和喷洒设备能力须在开发前确认。

---

## 6. 模块五：水肥一体化远程管控

### 6.1 API 定义

```
POST   /api/irrigation/devices                  # 登记水肥控制器
GET    /api/irrigation/devices?fieldId=         # 设备列表（含在线状态）
GET    /api/irrigation/devices/{id}             # 设备详情
PUT    /api/irrigation/devices/{id}             # 修改名称、地块、分区数、业务状态
DELETE /api/irrigation/devices/{id}             # 逻辑退役，不物理删除历史设备
GET    /api/irrigation/devices/{id}/status      # 设备实时状态（阀门开关、流量）

POST   /api/irrigation/tasks                    # 创建灌溉任务
GET    /api/irrigation/tasks?fieldId=&page=     # 任务记录
POST   /api/irrigation/tasks/{id}/cancel        # 取消待执行任务

POST   /api/irrigation/devices/{id}/control     # 手动控制（开/关指定分区阀门）
```

### 6.2 设备指令下发（MQTT）

```
// 下发 topic：irrigation/{device_sn}/cmd
{
  "taskId": 302,
  "action": "start",          // start / stop / pause
  "zones": [1, 2],
  "durationMin": 30,
  "fertilizerConfig": {
    "nKg": 2.5,
    "pKg": 1.0,
    "kKg": 2.0,
    "concentrationPct": 0.3
  }
}

// 设备状态上报 topic：irrigation/{device_sn}/status
{
  "taskId": 302,
  "phase": "running",          // idle/running/done/fault
  "zones": [
    {"id": 1, "valve": true, "flowLmin": 12.5},
    {"id": 2, "valve": true, "flowLmin": 11.8}
  ],
  "totalWaterM3": 0.42,
  "timestamp": 1720001234000
}
```

### 6.3 传感器自动触发逻辑（后端调度）

本节依赖 V5 增加的 `field.status`、`field.irrigation_threshold_pct`、`field.default_irrigation_duration_min`、`field.default_irrigation_device_id`、`irrigation_task.actual_water_m3` 和 `irrigation_device.status`。默认灌溉设备必须属于当前地块且 `status='active'`、`online=true`；最新土壤湿度数据缺失或超过配置的新鲜度窗口（默认 30 分钟）时跳过自动灌溉并记录原因。

```java
// IrrigationAutoTriggerJob.java（每 10 分钟执行一次）
@Scheduled(fixedDelay = 600_000)
public void checkAndTrigger() {
    List<Field> fields = fieldRepo.findAllActive();
    for (Field field : fields) {
        // 查最新土壤含水量（InfluxDB 查询）
        double moisture = iotService.getLatestMoisture(field.getId());
        if (Double.isNaN(moisture) || !iotService.hasFreshMoisture(field.getId(), Duration.ofMinutes(30))) continue;
        if (field.getDefaultIrrigationDeviceId() == null) continue;
        IrrigationDevice device = irrigationDeviceRepo.findById(field.getDefaultIrrigationDeviceId()).orElse(null);
        if (device == null || !device.isOnline() || !"active".equals(device.getStatus())) continue;
        double threshold = field.getIrrigationThresholdPct();  // 默认 60%

        if (moisture >= threshold) continue;

        // 24 小时内已触发过则跳过
        boolean recentTrigger = irrigationTaskRepo
            .existsRecentSensorTask(field.getId(), LocalDateTime.now().minusHours(24));
        if (recentTrigger) continue;

        // 创建自动灌溉任务
        IrrigationTask task = IrrigationTask.builder()
            .fieldId(field.getId())
            .deviceId(field.getDefaultIrrigationDeviceId())
            .zoneIds(new int[]{1})
            .triggerType("sensor")
            .durationMin(field.getDefaultIrrigationDurationMin())
            .status("pending")
            .build();
        irrigationTaskRepo.save(task);
        mqttService.sendIrrigationCmd(task);
    }
}
```

### 6.4 验收标准

- [ ] 水肥控制器按约定心跳上报；超过 90 秒未上报则 `online = false`（此规则仅适用于模块五灌溉设备，不替代模块二设备各自的超时配置）
- [ ] 手动控制指令下发后 3 秒内设备状态刷新至前端（WebSocket 推送）
- [ ] 自动触发任务在 24 小时内同一地块只执行一次
- [ ] 任务历史记录含实际用水量（从设备状态上报累计）

---

## 7. 模块六：知识库与智能问答

### 7.1 API 定义

```
GET    /api/knowledge/chunks?page=      # 管理员分页查看知识片段
POST   /api/knowledge/chunks            # 录入一条或多条知识片段（管理员）
POST   /api/knowledge/chunks/import     # 导入 UTF-8 CSV（管理员）
POST   /api/knowledge/chunks/import/file # 导入 PDF、DOCX、TXT、MD、CSV 或 ZIP（管理员）
POST   /api/knowledge/qa                # 智能问答
GET    /api/knowledge/qa/history        # 问答历史（?fieldId&page）

GET    /api/reports/growth/{fieldId}    # 生成生长报告（返回 PDF URL）
GET    /api/reports/growth/{fieldId}/pdf?date=YYYY-MM-DD # 查看已生成的 PDF
```

### 7.2 RAG 问答服务（Python FastAPI）

> 文件路径：`ai-service/rag/main.py`

```python
from fastapi import FastAPI
from pydantic import BaseModel
from langchain_community.vectorstores.pgvector import PGVector
from langchain_huggingface import HuggingFaceEmbeddings
from langchain_community.llms import Ollama
from langchain.chains import RetrievalQA
from langchain.prompts import PromptTemplate

app = FastAPI()

# 初始化 Embedding 模型（bge-m3，本地加载）
embeddings = HuggingFaceEmbeddings(model_name="BAAI/bge-m3")

# 连接 pgvector 向量库
vectorstore = PGVector(
    connection_string="postgresql://user:pass@localhost/peanut",
    embedding_function=embeddings,
    collection_name="knowledge_chunk"
)

# 本地 LLM（Qwen2-7B via Ollama）
llm = Ollama(model="qwen2:7b", temperature=0.3)

PROMPT_TEMPLATE = """你是一位花生种植领域的农业专家助手。
请根据以下参考资料回答问题，只使用参考资料中的信息，如果资料不足请说明。

参考资料：
{context}

问题：{question}

请用中文给出专业、简洁的回答："""

prompt = PromptTemplate(
    input_variables=["context", "question"],
    template=PROMPT_TEMPLATE
)

qa_chain = RetrievalQA.from_chain_type(
    llm=llm,
    retriever=vectorstore.as_retriever(search_kwargs={"k": 5}),
    chain_type_kwargs={"prompt": prompt},
    return_source_documents=True
)

class QARequest(BaseModel):
    question: str
    field_id: int | None = None

@app.post("/qa")
def answer(req: QARequest):
    result = qa_chain.invoke({"query": req.question})
    source_ids = [
        doc.metadata.get("chunk_id")
        for doc in result["source_documents"]
    ]
    return {
        "answer": result["result"],
        "sources": source_ids
    }
```

### 7.3 知识片段录入格式

```json
// POST /api/knowledge/chunks
{
  "sourceTitle": "花生育25号栽培技术手册",
  "content": "花育25号适宜在山东、河南、河北地区种植，全生育期约125天。花针期需保证土壤含水量在70%田间持水量以上，该阶段是产量形成的关键期，应避免干旱胁迫。",
  "metadata": {
    "category": "variety",
    "variety": "花育25号",
    "growthStage": "花针期"
  }
}
```

### 7.4 自动生长报告生成（每周一凌晨 2 点触发）

```java
// GrowthReportJob.java
@Scheduled(cron = "0 0 2 * * MON")
public void generateWeeklyReports() {
    List<Field> activeFields = fieldRepo.findByStatusAndSowDateNotNull("active");
    for (Field field : activeFields) {
        GrowthReportData data = GrowthReportData.builder()
            .fieldName(field.getName())
            .variety(field.getVariety())
            .growthStage(fieldService.calcGrowthStage(field.getSowDate()))
            .daysAfterSow(fieldService.calcDaysAfterSow(field.getSowDate()))
            .weeklyAvgMoisture(iotService.getWeeklyAvg(field.getId(), "soil_moisture"))
            .weeklyAvgTemp(iotService.getWeeklyAvg(field.getId(), "soil_temp"))
            .recentFarmEvents(farmEventRepo.findRecent7Days(field.getId()))
            .pestAlertCount(pestDetectionRepo.countRecent7Days(field.getId()))
            .build();

        byte[] pdf = reportRenderService.renderPdf(data);  // 使用 Thymeleaf + Flying Saucer 渲染
        String url = ossService.upload("reports/" + field.getId() + "_" + LocalDate.now() + ".pdf", pdf);
        wechatNotifyService.sendReport(field.getId(), url);
    }
}
```

### 7.5 验收标准

- [x] 问答接口平均响应时间 < 5 秒（本地 7B 模型，服务预热后）
- [x] 答案末尾显示引用来源（知识片段标题）
- [x] 每周一自动推送 PDF 生长报告至绑定手机（本机单接收人配置）
- [x] 知识库支持批量导入（CSV 格式：sourceTitle, content, category, variety, growthStage）

本机真实 BGE-M3、PostgreSQL/pgvector 和 GPU 加速的 Qwen2 7B 已验证资料录入、问答、引用标题、地块关联和历史。旧配置含冷加载的首问为 37.60 秒，预热后五个问题平均 5.51 秒。启用服务启动时预热，并将本机回答预算设为 48 token 后，经 Spring Boot 问答接口测了八个不同问题（含一条带上下文的追问），平均 2.81 秒，服务就绪后的首问 3.99 秒，最慢 4.62 秒。测试同时检查了答案要点、引用来源和历史记录。模型在空闲卸载或服务重启时仍需加载。2026-11-27 通过定时任务验证真实 OSS 上传和 PushPlus 微信一对一推送，接收人在手机确认收到通知并打开了测试 PDF。常规预览启用了每周一 02:00（北京时间）的任务，运行该任务时后端须保持启动。

### 模块六本地运行与配置

在 `ai-service/` 使用 Python 3.11 执行 `python -m pip install -r requirements.txt`，运行 `ollama pull qwen2:7b`，再执行 `python -m uvicorn rag.main:app --host 127.0.0.1 --port 8001`。BGE-M3 默认从 BAAI 官方仓库加载，也可通过 `BGE_MODEL_PATH` 指向已下载的权重目录。独立运行时 PostgreSQL 连接可配置 `DB_HOST`、`DB_PORT`、`DB_NAME`、`DB_USER`、`DB_PASS`。若在其他主机运行 Ollama，可设置 `OLLAMA_HOST`。AI 服务的 `OLLAMA_CONTEXT_TOKENS` 和 `OLLAMA_ANSWER_TOKENS` 默认为 4096 和 240。本机 4 GiB 显存预览采用 `OLLAMA_CONTEXT_TOKENS=1024`、`OLLAMA_ANSWER_TOKENS=128`、`RAG_WARM_UP=true` 和 `OLLAMA_KEEP_ALIVE=30m`。预热在 AI 服务开始接受请求前加载向量模型和 Qwen，配置的空闲保留时间到期后再次提问可能出现冷加载。

本机预览原先以 48 token 完成短答测速。为避免播种日期等内容在句中被截断，预览的回答预算提高至 128 token。若模型仍达到输出上限，系统只展示已完整生成的句子。没有完整句子时会提示缩小问题范围。

FastAPI 的 `/embed` 一次性写入知识片段、元数据和向量，Spring Boot 接收请求、校验、转发和统计导入结果。检索直接查询 `knowledge_chunk`，写入与检索使用相同的维度补零规则。保留 ivfflat 索引，当前小知识库使用精确余弦检索。问答页显示引用标题，历史按地块和时间查询。配置 `KNOWLEDGE_ADMIN_TOKEN` 时，管理页会要求输入密钥，只存于当前页面内存。本地预览可留空。

「知识库管理 → 导入文件」可选择单个 PDF、DOCX、TXT、Markdown、CSV 或 ZIP，单次上传最多 20 MiB。ZIP 内可以放支持的资料文件与子目录，支持最多 100 个条目，解压后的总量最多 40 MiB。系统提取正文、保留来源文件名并按段生成向量，单次最多 100 个知识片段。扫描件需要先做 OCR，密码 PDF 需先解锁。入库结果展示每个文件的写入数量或失败原因。

定时任务默认按上海时区每周一 02:00 为生长中的地块生成近七天报告。未配置 OSS 时，PDF 写入本地目录，可通过知识问答页选择地块获取。Linux 部署需安装可嵌入的中文字体或设置 `GROWTH_REPORT_FONT`。

1. 在阿里云 OSS 控制台创建私有 Bucket，记录 Bucket 名称、地域 ID 和外网 Endpoint。给专用 RAM 身份授予对该 Bucket 下 `reports/*` 的 `oss:PutObject` 和 `oss:GetObject` 权限。[阿里云 OSS 控制台入门](https://help.aliyun.com/zh/oss/user-guide/get-started-with-oss/)
2. 在启动 **后端** 的进程环境中配置 `GROWTH_REPORT_OSS_BUCKET`、`GROWTH_REPORT_OSS_REGION`、`GROWTH_REPORT_OSS_ENDPOINT`、`GROWTH_REPORT_OSS_ACCESS_KEY_ID`、`GROWTH_REPORT_OSS_ACCESS_KEY_SECRET`。配置成功后使用 V4 签名上传，通知中的 OSS 下载链接有效期为 7 天。[阿里云预签名下载文档](https://help.aliyun.com/zh/oss/developer-reference/download-using-a-presigned-url)
3. 将支持报告通知的服务地址设置为 `GROWTH_REPORT_WEBHOOK_URL`。这个地址接收 JSON `POST`，字段是 `fieldId`、ISO 日期 `reportDate` 和 `pdfUrl`。通知服务根据 `fieldId` 找到绑定手机，确认接收后返回 2xx，并可用 `Idempotency-Key` 对重复请求去重。已有可从手机访问的后端域名时，也可配置 `GROWTH_REPORT_PUBLIC_BASE_URL`，使通知使用平台报告链接。

单人预览可使用 PushPlus 微信一对一通知，配置 `GROWTH_REPORT_PUSHPLUS_TOKEN`（本机属性名 `growth-report.pushplus-token`）。这个配置将该实例周报发给令牌关联的微信，适用于单一接收人。PushPlus 会接收到可下载报告的临时链接。真实验收以手机收到通知并能打开测试周报为准。[PushPlus 一对一消息](https://dev.pushplus.plus/doc/function/one.html)

执行 `python -m unittest discover -s tests -v` 运行向量测试，设置 `PEANUT_TEST_RAG=true` 并连接临时数据库会额外验证真实 pgvector 写入和排序。Java 集成测试与模块一共用 `PEANUT_TEST_POSTGIS=true` 开关。

---

## 8. 前端关键页面规范

### 8.1 "一张图"主地图页

```
组件：MapView.vue
地图库：MapLibre GL JS 4.x
图层叠加顺序（从下到上）：
  1. 底图（天地图影像）
  2. 地块填充层（field-fill）：点击选中地块，高亮显示
  3. 地块描边层（field-line）
  4. NDVI 切片层（ndvi-raster）：透明度 0.7，可切换显示/隐藏
  5. 病虫害标注层（pest-markers）：红色圆点，点击弹窗
  6. 传感器站点层（sensor-markers）：蓝色图标
  7. 农业物联网设备层（iot-device-markers）：按设备类型注册表图标与状态展示无人机、机库、土壤传感器、气象站、虫情灯等，点击可打开设备摘要

交互：
  - 右键地块 → 弹出菜单（查看详情/新建巡田任务/新建灌溉任务）
  - 左侧面板：地块列表，点击定位并高亮
  - 右上角：图层开关（NDVI/病虫害/巡田设备/传感器）
```

### 8.2 WebSocket 实时推送

```javascript
// frontend/src/composables/useRealtimeStatus.js
// 后端推送 topic 格式：/topic/field/{fieldId}/status
import { useWebSocket } from '@vueuse/core'

export function useRealtimeStatus(fieldId) {
  const { data } = useWebSocket(`ws://${API_HOST}/ws`, {
    onConnected(ws) {
      ws.send(JSON.stringify({ type: 'subscribe', fieldId }))
    }
  })
  return { data }
}
// 收到消息后更新 Pinia store，地图图层自动响应
```


### 8.3 模块导航结构

- 农田管理
- 智能巡田与农业物联网监测中心
  - 监测总览
  - 设备管理（通用设备类型、设备分组、添加/导入/导出）
  - 传感器监测（实时指标、历史曲线、数据对比）
  - 告警中心
  - 模拟设备实验室
  - 巡田任务
  - 飞行记录
  - NDVI 监测
- 病虫害识别与预警
- 智能分析与处方图
- 水肥一体化（仅管理灌溉控制器，与模块二巡田设备分开）
- 知识库与智能问答

---

## 9. 部署配置

### 9.1 docker-compose.yml（开发环境）

仓库的 `docker-compose.yml` 在以下约定基础上增加了 Ollama、模型数据卷和健康检查。数据库镜像基于 `postgis/postgis:16-3.4`，加入与现有向量库一致的 pgvector 0.7.4。首次安装、环境变量以及各服务地址见根目录 `README.md`。

2026-11-28 本机切换验收通过。PostgreSQL、InfluxDB、EMQX、RabbitMQ、Ollama、后端、AI 服务和前端均达到健康状态，切换前后地块、知识片段和问答记录数量一致。已验证后端健康检查、FastAPI 文档及浏览器的农田管理和知识库入口。本次启动复用了已验证的本机构建镜像和模型缓存。

```yaml
version: '3.9'
services:
  postgres:
    image: postgis/postgis:16-3.4
    environment:
      POSTGRES_DB: peanut
      POSTGRES_USER: peanut
      POSTGRES_PASSWORD: peanut123
    ports: ["5432:5432"]
    volumes: ["pgdata:/var/lib/postgresql/data"]

  influxdb:
    image: influxdb:2.7
    environment:
      DOCKER_INFLUXDB_INIT_MODE: setup
      DOCKER_INFLUXDB_INIT_ORG: peanut
      DOCKER_INFLUXDB_INIT_BUCKET: sensors
      DOCKER_INFLUXDB_INIT_ADMIN_TOKEN: peanut-token-dev
    ports: ["8086:8086"]

  emqx:
    image: emqx:5.6
    ports: ["1883:1883", "18083:18083"]

  rabbitmq:
    image: rabbitmq:3.13-management
    ports: ["5672:5672", "15672:15672"]

  ai-service:
    build: ./ai-service
    ports: ["8001:8001"]
    volumes: ["./ai-service/weights:/app/weights"]
    depends_on: [postgres]

  backend:
    build: ./backend
    ports: ["8080:8080"]
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/peanut
      INFLUXDB_URL: http://influxdb:8086
      MQTT_BROKER_URL: tcp://emqx:1883
    depends_on: [postgres, influxdb, emqx, rabbitmq]

  frontend:
    build: ./frontend
    ports: ["3000:80"]
    depends_on: [backend]

volumes:
  pgdata:
```

### 9.2 application.yml 关键配置项

```yaml
spring:
  datasource:
    url: ${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/peanut}
    username: ${DB_USER:peanut}
    password: ${DB_PASS:peanut123}
  flyway:
    enabled: true
    locations: classpath:db/migration

influxdb:
  url: ${INFLUXDB_URL:http://localhost:8086}
  token: ${INFLUXDB_TOKEN:peanut-token-dev}
  org: peanut
  bucket: sensors

mqtt:
  broker-url: ${MQTT_BROKER_URL:tcp://localhost:1883}
  client-id: peanut-backend-${random.uuid}
  qos: 1

ai-service:
  base-url: ${AI_SERVICE_URL:http://localhost:8001}

jwt:
  secret: ${JWT_SECRET:change-me-in-production}
  access-expire-ms: 7200000    # 2h
  refresh-expire-ms: 604800000 # 7d
```

---

## 10. 跨模块一致性与实施风险

1. **Flyway 迁移不可回写：** V1–V5 已执行，阶段 A 使用 V6。不得修改已执行的历史迁移、删除迁移历史或清库。
2. **设备类型可扩展：** 原 `patrol_device.device_type` 仅允许 drone/dock/sensor 的限制需要在 V6 中安全移除，并以 `iot_device_type` 注册表约束。新设备类型和指标能力必须数据驱动，不得在前端硬编码为三种类型。
3. **巡田设备与灌溉设备分离：** 模块二 `patrol_device` 管无人机、机库、农业传感器、气象站、虫情灯等监测设备；模块五 `irrigation_device` 管水肥控制器。模块二不得调用灌溉控制接口。
4. **巡田任务与设备绑定：** 新任务使用 `drone_device_id` 外键；旧 `drone_sn` 保留兼容，历史序列号需人工核对设备类型，不能默认当作无人机。
5. **遥测地块归属：** InfluxDB `field_id` 由设备台账绑定关系确定，不信任 MQTT payload 自带 fieldId。设备退役不删除时序历史；模拟/真实数据必须用 `source` 区分。
6. **模拟与真实链路一致：** 模拟器生成数据必须进入 `TelemetryIngestionService`，使用同一校验、幂等、InfluxDB、实时推送和告警链路。前端随机数、固定 JSON 或静态数组只能用于组件开发预览，不可用于验收。
7. **指标语义与单位：** `soil_moisture` 旧字段不能未经确认直接映射为 `%VWC`；N/P/K 传感器估算值不等同实验室检测。单位转换、设备校准和质量码必须有记录；缺失数据不补 0。
8. **告警阈值：** 设备建议量程不等于农艺诊断阈值。土壤水分、温度等阈值需由用户/农艺师按土壤、品种、生育期和设备校准确认；模拟阈值只用于演示。
9. **MQTT 安全：** 未登记设备不得自动注册；每台真实设备独立凭证和 topic ACL；不能把 EMQX 管理凭证放在前端；生产真实设备使用 TLS。模拟设备通过内部受控服务发数据。
10. **图表依赖：** 新增图表前检查 `package.json`，优先复用已有图表库；如果要引入 ECharts 等新依赖，先说明理由并等待用户确认，不得擅自替换地图或 UI 技术栈。
11. **模块四处方图下发目标未定义：** 当前处方图没有明确的肥料/农药执行设备和 MQTT topic；不能因为设备注册表可扩展就默认任何设备可执行处方。未确认设备能力前只做草稿生成、编辑和地图展示。
12. **模块五算法字段缺失已纳入 V5：** `field.status`、灌溉阈值、默认灌溉设备/时长以及任务实际用水量按 V5 增量迁移补齐。无新鲜土壤数据或无在线默认灌溉设备时禁止自动下发。
13. **模块三模型权重：** 通用 YOLOv8n 不是花生病虫害模型。没有训练权重时必须明确报未配置或使用标记清楚的演示模式。
14. **模块六向量维度：** 当前表为 `vector(1536)`，BGE-M3 原始输出为 1024 维。现有实现采用补零时，写入、查询和历史向量必须使用完全一致的维度处理；未来改维度需另做迁移。
15. **验收勾选规则：** `[x]` 只代表已实际执行并通过测试的能力；新增监测中心功能默认 `[ ]`。模拟设备验收不等于真实硬件、厂商协议、生产网络安全已验收。

---

## 11. 完整实现 Checklist

按顺序完成每项，标注 `[x]` 表示已实际实现并通过对应测试。历史已验收项目保持 `[x]`；本次模块二增强项默认 `[ ]`，不得提前勾选。

```
基础设施
[x] Docker Compose 服务启动与健康检查（2026-11-28 本机验收）
[x] V1__init.sql 已在现有环境执行
[x] 后端 Spring Boot /actuator/health 返回 UP
[x] V5__patrol_device_management_and_schema_alignment.sql 按实际数据库状态安全升级
[x] V6__iot_monitoring_center.sql 在不修改 V1–V5 的前提下安全升级

模块一：农田管理
[x] POST /api/fields 创建地块，面积自动计算
[x] GET /api/fields 分页+关键词搜索
[x] 生育期推算随日期变化正确
[x] 前端地块圈画（MapLibre draw 插件）+ 提交

模块二：智能巡田与农业物联网监测中心（原能力已验收）
[x] 模拟无人机 cron 与手动 MQTT 任务下发、飞行状态转换
[x] NDVI 处理流水线基础端到端测试
[x] InfluxDB 原传感器数据写入 + 查询历史接口及趋势页

模块二增强阶段 A：通用设备与模拟设备实验室
[x] 设备类型注册表、指标字典和设备能力表
[x] 通用设备添加向导、设备 CRUD、启停/退役、地块/机库绑定
[x] 设备分组、CSV 导入预览/导入、CSV 导出
[x] 模拟设备模板创建、模拟数据标识和模拟器配置
[x] 模拟器启动/暂停/继续/停止/重置/异常注入/场景预设
[x] 模拟器生成数据走统一 TelemetryIngestionService，不使用前端假数据

模块二增强阶段 B：遥测与曲线
[ ] 统一遥测 API/MQTT 适配、设备能力/单位/质量校验、幂等去重
[ ] InfluxDB device_telemetry measurement 与 source=simulated/real
[ ] 实时指标卡、1h/6h/24h/7d/30d/自定义曲线
[ ] 多指标/设备/地块对比、阈值线、tooltip、缩放、数据表和 CSV 导出
[ ] 原 `/api/iot/sensors/{sn}/history` 兼容回归测试

模块二增强阶段 C：告警中心
[ ] 指标阈值、离线、低电量、低信号、数据超时、设备故障规则
[ ] 持续时长、防抖、恢复阈值和告警级别
[ ] 告警确认/解决/关闭/备注/历史事件
[ ] 模拟缺水、高温、离线、低电量、卡死和尖峰告警场景验收

模块二增强阶段 D：总览/详情/维护
[ ] 监测总览 KPI、数据新鲜度、地块地图、最近告警和事件
[ ] 设备详情实时/历史指标、告警、维护、原始遥测和事件日志
[ ] 维护工单、设备分组和设备类型/指标字典管理
[ ] 桌面/平板/手机响应式与加载/空/错误/权限状态验收

模块二增强阶段 E：巡田联调
[ ] 新巡田任务从设备台账选择可用无人机，机库关系正确
[ ] 原飞行记录、影像上传、NDVI 地图回归测试通过
[ ] 设备地图图层与监测总览/详情联动
[ ] 模块二不越权控制模块五灌溉设备

模块三：病虫害
[ ] 花生病虫害模型权重配置检查及 /detect 接口测试
[ ] 检测结果写入 pest_detection 表
[ ] 按地块+病害类型执行 24h 去重
[ ] flight 影像转换/RGB 输入及无地理坐标场景处理正确

模块四：处方图
[ ] 处方图生成函数单测（NDVI + soil_points，CRS 一致）
[ ] 前端分区着色可视化
[ ] 明确施肥/喷洒执行设备、能力与 MQTT 协议
[ ] confirm → executing → executed/failed 状态及设备回执联调

模块五：水肥控制
[ ] 水肥控制器登记/编辑/退役及在线状态
[ ] 设备心跳超时自动下线
[ ] 手动控制指令下发 + 状态实时刷新
[ ] 任务历史记录 actual_water_m3 正确
[ ] 传感器自动触发灌溉（有新鲜数据、设备在线时联调）

模块六：知识库
[x] pgvector 索引创建 + embedding 写入测试
[x] RAG 问答接口返回答案 + 引用来源
[x] 周报定时任务触发 + PDF 生成及手机通知
[ ] 写入/查询向量维度一致性自动测试

集成测试
[ ] 完整业务流：创建地块 → 模拟/真实设备遥测 → 告警/监测 → 触发巡田 → NDVI 生成 → 病虫害检测 → 处方图生成 → 灌溉执行
[ ] 前端端到端（E2E）：地图圈地、设备管理、模拟器、传感器曲线、告警处置、问答、处方图编辑
```

---

## 12. 市场产品设计参考与本项目取舍

本模块的交互设计参考成熟 IoT 平台常见模式，而非照搬某个产品：

- ThingsBoard 的设备资料/设备详情、设备 profile、latest telemetry、alarm rules、alarms、events、relations 和审计日志分区方式，可作为设备详情信息架构参考：[ThingsBoard Devices](https://thingsboard.io/docs/user-guide/devices/)；[Device Profiles](https://thingsboard.io/docs/user-guide/device-profiles/)。
- ThingsBoard 的 dashboard widget、设备列表点击后 drill-down 到设备详情、图表和告警列表联动，可作为总览到详情的交互参考：[Data Visualization](https://thingsboard.io/docs/user-guide/data-visualization/)。
- Milesight IoT Cloud 的仪表盘、地图、折线/饼图/柱状图、触发器和事件中心，可作为农业/环境传感器监测页面的参考：[Milesight IoT Cloud](https://www.milesight.com/iot/product/milesight-iot-cloud/)；其设备离线、阈值和低电量告警配置也可作告警交互参考：[Milesight Alarm Guide](https://support.milesight-iot.com/support/solutions/articles/73000514878-how-to-set-alarms-on-milesight-iot-cloud)。
- CropX 农业平台资料展示了田块健康、土壤水分状态、传感器在线/低电量/离线等农场级概览，可作为农业场景驾驶舱的信息组织参考：[CropX Product Brochure](https://cropx.com/wp-content/uploads/2026/08/CropX-Product-Brochure-EN.pdf)。

**本项目取舍：** 第一阶段不做复杂的拖拽式任意仪表盘编辑器，也不做设备远程固件升级/通用 RPC。先做好统一设备类型/能力、可靠的模拟数据链路、可读曲线、告警、地图和设备详情；待真实硬件协议明确后再扩展厂商适配和设备控制。市场参考只用于产品交互启发，不代表本项目已接入上述平台或硬件。
