CREATE TABLE patrol_device (
    id BIGSERIAL PRIMARY KEY,
    device_type VARCHAR(20) NOT NULL CHECK (device_type IN ('drone', 'dock', 'sensor')),
    device_sn VARCHAR(64) NOT NULL UNIQUE,
    device_name VARCHAR(100) NOT NULL,
    brand VARCHAR(80),
    model VARCHAR(100),
    field_id BIGINT REFERENCES field(id) ON DELETE RESTRICT,
    parent_device_id BIGINT REFERENCES patrol_device(id) ON DELETE SET NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'active' CHECK (status IN ('active', 'inactive', 'maintenance', 'retired')),
    online BOOLEAN NOT NULL DEFAULT FALSE,
    last_seen_at TIMESTAMPTZ,
    current_phase VARCHAR(30),
    last_status JSONB NOT NULL DEFAULT '{}'::jsonb,
    battery_pct DECIMAL(5,2) CHECK (battery_pct BETWEEN 0 AND 100),
    longitude DECIMAL(10,7) CHECK (longitude BETWEEN -180 AND 180),
    latitude DECIMAL(10,7) CHECK (latitude BETWEEN -90 AND 90),
    altitude_m DECIMAL(8,2),
    firmware_version VARCHAR(80),
    capabilities JSONB NOT NULL DEFAULT '{}'::jsonb,
    fault_code VARCHAR(80),
    fault_message TEXT,
    installed_at TIMESTAMPTZ,
    last_maintenance_at TIMESTAMPTZ,
    next_maintenance_at TIMESTAMPTZ,
    remark TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    CHECK (device_type <> 'sensor' OR field_id IS NOT NULL),
    CHECK (device_type = 'drone' OR parent_device_id IS NULL)
);
CREATE INDEX idx_patrol_device_field_type ON patrol_device(field_id, device_type);
CREATE INDEX idx_patrol_device_online ON patrol_device(device_type, online, status);
CREATE INDEX idx_patrol_device_parent ON patrol_device(parent_device_id);

CREATE TABLE patrol_device_maintenance (
    id BIGSERIAL PRIMARY KEY,
    device_id BIGINT NOT NULL REFERENCES patrol_device(id) ON DELETE RESTRICT,
    maintenance_type VARCHAR(30) NOT NULL CHECK (maintenance_type IN ('inspection','repair','firmware','battery','calibration','other')),
    status VARCHAR(20) NOT NULL DEFAULT 'completed' CHECK (status IN ('planned','in_progress','completed','cancelled')),
    description TEXT NOT NULL,
    operator_name VARCHAR(80),
    started_at TIMESTAMPTZ,
    completed_at TIMESTAMPTZ,
    next_maintenance_at TIMESTAMPTZ,
    created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX idx_patrol_maintenance_device ON patrol_device_maintenance(device_id, id DESC);

-- scheduled_at and ndvi_image_path were installed by V4 and V3.
ALTER TABLE patrol_task ADD COLUMN drone_device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT;
ALTER TABLE patrol_task ADD COLUMN requires_dock BOOLEAN NOT NULL DEFAULT false;
ALTER TABLE patrol_flight ADD COLUMN drone_device_id BIGINT REFERENCES patrol_device(id) ON DELETE RESTRICT;
CREATE INDEX idx_patrol_task_drone ON patrol_task(drone_device_id);
CREATE INDEX idx_patrol_flight_drone ON patrol_flight(drone_device_id);

ALTER TABLE field ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active';
ALTER TABLE field ADD COLUMN irrigation_threshold_pct DECIMAL(5,2) NOT NULL DEFAULT 60.00;
ALTER TABLE field ADD COLUMN default_irrigation_duration_min INT NOT NULL DEFAULT 30;
ALTER TABLE field ADD COLUMN default_irrigation_device_id BIGINT REFERENCES irrigation_device(id) ON DELETE SET NULL;
ALTER TABLE irrigation_task ADD COLUMN actual_water_m3 DECIMAL(8,2);
ALTER TABLE irrigation_task ADD COLUMN last_status_at TIMESTAMPTZ;
ALTER TABLE irrigation_device ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'active'
    CHECK (status IN ('active','inactive','maintenance','retired'));
