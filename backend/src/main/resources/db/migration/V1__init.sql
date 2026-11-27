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
    embedding    VECTOR(1536),          -- bge-m3 输出维度
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
