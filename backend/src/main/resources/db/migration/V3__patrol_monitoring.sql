ALTER TABLE patrol_flight
    ADD COLUMN scheduled_for TIMESTAMPTZ,
    ADD COLUMN processing_started_at TIMESTAMPTZ,
    ADD COLUMN ndvi_image_path TEXT,
    ADD COLUMN error_message TEXT,
    ADD CONSTRAINT uq_patrol_flight_schedule UNIQUE (task_id, scheduled_for);

CREATE INDEX idx_patrol_task_field ON patrol_task(field_id, id DESC);
CREATE INDEX idx_patrol_flight_field ON patrol_flight(field_id, id DESC);
CREATE INDEX idx_patrol_flight_pending ON patrol_flight(id) WHERE status = 'processing';
