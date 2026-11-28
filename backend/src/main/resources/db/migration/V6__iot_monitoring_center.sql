CREATE TABLE iot_device_type (
    code VARCHAR(64) PRIMARY KEY, name VARCHAR(80) NOT NULL, category VARCHAR(40) NOT NULL,
    description TEXT, default_sample_interval_seconds INT CHECK (default_sample_interval_seconds BETWEEN 5 AND 86400),
    default_offline_timeout_seconds INT NOT NULL DEFAULT 180 CHECK (default_offline_timeout_seconds > 0),
    capability_schema JSONB NOT NULL DEFAULT '{}', is_system BOOLEAN NOT NULL DEFAULT false,
    enabled BOOLEAN NOT NULL DEFAULT true, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO iot_device_type(code,name,category,default_sample_interval_seconds,default_offline_timeout_seconds,capability_schema,is_system) VALUES
('drone','无人机','aircraft',NULL,90,'{"metrics":["battery_pct","signal_strength_pct"]}',true),
('dock','无人机机库','gateway',NULL,180,'{"metrics":["battery_pct","signal_strength_pct"]}',true),
('sensor','巡田传感器','soil',300,180,'{"fieldRequired":true,"metrics":["soil_temp_c","soil_n_mg_kg","soil_p_mg_kg","soil_k_mg_kg","battery_pct"]}',true),
('soil_multi_sensor','土壤多参数传感器','soil',300,900,'{"fieldRequired":true,"metrics":["soil_temp_c","soil_moisture_vwc_pct","soil_ec_us_cm","soil_ph","soil_n_mg_kg","soil_p_mg_kg","soil_k_mg_kg","battery_pct"]}',true),
('soil_moisture_sensor','土壤水分温度传感器','soil',300,900,'{"fieldRequired":true,"metrics":["soil_temp_c","soil_moisture_vwc_pct","battery_pct"]}',true),
('weather_station','农业气象站','weather',300,900,'{"fieldRequired":true,"metrics":["air_temp_c","air_humidity_pct","light_lux","rainfall_mm","wind_speed_m_s","wind_direction_deg","battery_pct"]}',true),
('insect_light','智能虫情灯','pest_monitor',900,2700,'{"metrics":["insect_count","battery_pct"]}',true),
('spore_trap','孢子捕捉器','pest_monitor',900,2700,'{"metrics":["spore_count","battery_pct"]}',true),
('leaf_sensor','叶面湿度传感器','leaf',300,900,'{"fieldRequired":true,"metrics":["leaf_wetness_pct","light_lux","battery_pct"]}',true),
('other_sensor','其他环境传感器','other',300,900,'{"metrics":["battery_pct","signal_strength_pct"]}',true);

-- Preserve the existing sensor-field, drone-parent and relationship constraints.
ALTER TABLE patrol_device DROP CONSTRAINT patrol_device_device_type_check;
ALTER TABLE patrol_device ALTER COLUMN device_type TYPE VARCHAR(64);
ALTER TABLE patrol_device ADD CONSTRAINT fk_patrol_device_type_code FOREIGN KEY (device_type) REFERENCES iot_device_type(code);
ALTER TABLE patrol_device ADD COLUMN protocol VARCHAR(24) NOT NULL DEFAULT 'mqtt'
    CHECK (protocol IN ('mqtt','http','lorawan_gateway','simulator','vendor_adapter','other')),
    ADD COLUMN access_mode VARCHAR(24) NOT NULL DEFAULT 'real' CHECK (access_mode IN ('real','simulated')),
    ADD COLUMN install_location VARCHAR(200), ADD COLUMN sample_interval_seconds INT CHECK (sample_interval_seconds BETWEEN 5 AND 86400),
    ADD COLUMN signal_strength_pct DECIMAL(5,2) CHECK (signal_strength_pct BETWEEN 0 AND 100),
    ADD COLUMN last_telemetry_at TIMESTAMPTZ, ADD COLUMN device_tags TEXT[] NOT NULL DEFAULT '{}',
    ADD COLUMN device_metadata JSONB NOT NULL DEFAULT '{}', ADD COLUMN latest_metrics JSONB NOT NULL DEFAULT '{}',
    ADD COLUMN created_by BIGINT, ADD COLUMN retired_at TIMESTAMPTZ, ADD COLUMN retired_reason TEXT,
    ADD CONSTRAINT chk_patrol_device_simulator_protocol CHECK ((access_mode='simulated')=(protocol='simulator'));
CREATE INDEX idx_patrol_device_last_seen ON patrol_device(status,last_seen_at);
CREATE INDEX idx_patrol_device_type_field ON patrol_device(device_type,field_id,status);

CREATE TABLE iot_metric_definition (
    code VARCHAR(64) PRIMARY KEY, display_name VARCHAR(100) NOT NULL, category VARCHAR(40) NOT NULL,
    canonical_unit VARCHAR(32) NOT NULL, data_type VARCHAR(20) NOT NULL DEFAULT 'number' CHECK(data_type='number'),
    precision_digits INT NOT NULL DEFAULT 1 CHECK(precision_digits BETWEEN 0 AND 8),
    recommended_min DOUBLE PRECISION, recommended_max DOUBLE PRECISION,
    description TEXT, display_order INT NOT NULL DEFAULT 100, enabled BOOLEAN NOT NULL DEFAULT true,
    CHECK (recommended_min IS NULL OR recommended_max IS NULL OR recommended_min <= recommended_max)
);
INSERT INTO iot_metric_definition(code,display_name,category,canonical_unit,precision_digits,recommended_min,recommended_max,display_order,description) VALUES
('soil_temp_c','土壤温度','soil','°C',1,-10,60,10,NULL),
('soil_moisture_vwc_pct','土壤体积含水率','soil','%VWC',1,0,100,20,'体积含水率'),
('soil_ec_us_cm','土壤 EC','soil','µS/cm',0,0,20000,30,NULL),
('soil_ph','土壤 pH','soil','pH',2,0,14,40,NULL),
('soil_n_mg_kg','土壤氮','soil','mg/kg',1,0,10000,50,'设备估算值'),
('soil_p_mg_kg','土壤磷','soil','mg/kg',1,0,10000,60,'设备估算值'),
('soil_k_mg_kg','土壤钾','soil','mg/kg',1,0,10000,70,'设备估算值'),
('air_temp_c','空气温度','weather','°C',1,-40,70,80,NULL),
('air_humidity_pct','空气相对湿度','weather','%',1,0,100,90,NULL),
('light_lux','光照','weather','lx',0,0,200000,100,NULL),
('rainfall_mm','降雨','weather','mm',1,0,1000,110,'模拟模板采用采样区间降雨量'),
('wind_speed_m_s','风速','weather','m/s',1,0,100,120,NULL),
('wind_direction_deg','风向','weather','°',0,0,360,130,NULL),
('leaf_wetness_pct','叶面湿度','leaf','%',1,0,100,140,NULL),
('battery_pct','电量','device','%',0,0,100,150,NULL),
('signal_strength_pct','信号强度','device','%',0,0,100,160,NULL),
('insect_count','虫情计数','pest','count',0,0,NULL,170,NULL),
('spore_count','孢子计数','pest','count',0,0,NULL,180,NULL);

CREATE TABLE iot_device_metric_capability (
    id BIGSERIAL PRIMARY KEY, device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    metric_code VARCHAR(64) NOT NULL REFERENCES iot_metric_definition(code), enabled BOOLEAN NOT NULL DEFAULT true,
    unit_override VARCHAR(32), display_name_override VARCHAR(100), calibration_offset DOUBLE PRECISION NOT NULL DEFAULT 0,
    calibration_scale DOUBLE PRECISION NOT NULL DEFAULT 1, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE(device_id,metric_code)
);
INSERT INTO iot_device_metric_capability(device_id,metric_code)
SELECT d.id, m.code FROM patrol_device d JOIN iot_device_type t ON t.code=d.device_type
CROSS JOIN LATERAL jsonb_array_elements_text(t.capability_schema->'metrics') m(code);
CREATE INDEX idx_iot_device_metric_device ON iot_device_metric_capability(device_id,enabled);

CREATE TABLE iot_device_group (
    id BIGSERIAL PRIMARY KEY, group_name VARCHAR(100) NOT NULL UNIQUE, description TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE iot_device_group_member (
    group_id BIGINT REFERENCES iot_device_group(id) ON DELETE CASCADE,
    device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY(group_id,device_id)
);
CREATE INDEX idx_iot_group_device ON iot_device_group_member(device_id);
CREATE TABLE iot_device_import (
    id UUID PRIMARY KEY, actor VARCHAR(100) NOT NULL, rows JSONB NOT NULL, errors JSONB NOT NULL,
    imported_count INT, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE iot_device_event (
    id BIGSERIAL PRIMARY KEY, device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT,
    field_id BIGINT REFERENCES field(id) ON DELETE SET NULL, event_type VARCHAR(40) NOT NULL,
    level VARCHAR(16) NOT NULL DEFAULT 'info', summary VARCHAR(240) NOT NULL, payload JSONB NOT NULL DEFAULT '{}',
    actor VARCHAR(100), created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_iot_device_event_device_time ON iot_device_event(device_id,created_at DESC);

CREATE TABLE iot_simulator_profile (
    id BIGSERIAL PRIMARY KEY, device_id BIGINT NOT NULL UNIQUE REFERENCES patrol_device(id) ON DELETE CASCADE,
    scenario_code VARCHAR(40) NOT NULL DEFAULT 'normal', sample_interval_seconds INT NOT NULL DEFAULT 60 CHECK(sample_interval_seconds BETWEEN 5 AND 86400),
    random_seed BIGINT NOT NULL DEFAULT 20261128, config JSONB NOT NULL DEFAULT '{}',
    simulator_status VARCHAR(20) NOT NULL DEFAULT 'stopped' CHECK(simulator_status IN ('running','paused','stopped')),
    started_at TIMESTAMPTZ, paused_at TIMESTAMPTZ, last_generated_at TIMESTAMPTZ, next_sample_at TIMESTAMPTZ,
    run_id UUID NOT NULL, sequence_no BIGINT NOT NULL DEFAULT 0, scenario_step BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX idx_iot_simulator_due ON iot_simulator_profile(next_sample_at) WHERE simulator_status='running';
CREATE TABLE iot_telemetry_dedupe (
    id BIGSERIAL PRIMARY KEY, device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE CASCADE,
    message_id VARCHAR(100) NOT NULL, boot_id VARCHAR(80), sequence_no BIGINT,
    received_at TIMESTAMPTZ NOT NULL DEFAULT now(), UNIQUE(device_id,message_id)
);
CREATE UNIQUE INDEX uq_iot_telemetry_device_boot_sequence ON iot_telemetry_dedupe(device_id,boot_id,sequence_no)
    WHERE boot_id IS NOT NULL AND sequence_no IS NOT NULL;
CREATE INDEX idx_iot_telemetry_dedupe_received ON iot_telemetry_dedupe(received_at);

CREATE TABLE iot_device_credential (
    id BIGSERIAL PRIMARY KEY, device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE CASCADE,
    credential_name VARCHAR(80) NOT NULL, credential_type VARCHAR(24) NOT NULL DEFAULT 'mqtt_password_hash',
    secret_hash VARCHAR(255) NOT NULL, enabled BOOLEAN NOT NULL DEFAULT true, expires_at TIMESTAMPTZ,
    last_used_at TIMESTAMPTZ, created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_iot_device_credential_device ON iot_device_credential(device_id,enabled);

CREATE TABLE iot_threshold_rule (
    id BIGSERIAL PRIMARY KEY, rule_name VARCHAR(120) NOT NULL, scope_type VARCHAR(20) NOT NULL DEFAULT 'device_type'
        CHECK(scope_type IN ('global','device_type','device')),
    device_id BIGINT REFERENCES patrol_device(id) ON DELETE CASCADE, device_type_code VARCHAR(64) REFERENCES iot_device_type(code),
    rule_type VARCHAR(32) NOT NULL CHECK(rule_type IN ('metric_threshold','offline','low_battery','low_signal','data_timeout','fault','sensor_stuck')),
    metric_code VARCHAR(64) REFERENCES iot_metric_definition(code), operator VARCHAR(8)
        CHECK(operator IS NULL OR operator IN ('gt','gte','lt','lte','eq')),
    threshold_value DOUBLE PRECISION, recovery_value DOUBLE PRECISION,
    duration_seconds INT NOT NULL DEFAULT 0 CHECK(duration_seconds >= 0),
    cooldown_seconds INT NOT NULL DEFAULT 900 CHECK(cooldown_seconds >= 0),
    severity VARCHAR(16) NOT NULL DEFAULT 'warning' CHECK(severity IN ('info','warning','critical')),
    enabled BOOLEAN NOT NULL DEFAULT true, config JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(), updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_iot_threshold_rule_scope ON iot_threshold_rule(enabled,scope_type,device_type_code,device_id);
CREATE TABLE iot_alert (
    id BIGSERIAL PRIMARY KEY, device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    rule_id BIGINT REFERENCES iot_threshold_rule(id) ON DELETE SET NULL, alert_type VARCHAR(32) NOT NULL,
    source VARCHAR(12) NOT NULL CHECK(source IN ('simulated','real')),
    severity VARCHAR(16) NOT NULL CHECK(severity IN ('info','warning','critical')),
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK(status IN ('active','acknowledged','resolved','closed')),
    dedupe_key VARCHAR(180) NOT NULL, title VARCHAR(160) NOT NULL, message TEXT NOT NULL,
    metric_code VARCHAR(64) REFERENCES iot_metric_definition(code), observed_value DOUBLE PRECISION, threshold_value DOUBLE PRECISION,
    triggered_at TIMESTAMPTZ NOT NULL DEFAULT now(), last_triggered_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    acknowledged_at TIMESTAMPTZ, acknowledged_by VARCHAR(100), resolved_at TIMESTAMPTZ, resolved_by VARCHAR(100),
    resolution_note TEXT, details JSONB NOT NULL DEFAULT '{}'
);
CREATE INDEX idx_iot_alert_status_time ON iot_alert(status,severity,triggered_at DESC);
CREATE INDEX idx_iot_alert_device_time ON iot_alert(device_id,triggered_at DESC);

ALTER TABLE patrol_device_maintenance ADD COLUMN priority VARCHAR(16) NOT NULL DEFAULT 'normal',
    ADD COLUMN assigned_to VARCHAR(100), ADD COLUMN due_at TIMESTAMPTZ, ADD COLUMN resolution TEXT,
    ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
