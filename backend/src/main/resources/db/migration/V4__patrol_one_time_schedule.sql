ALTER TABLE patrol_task
    ADD COLUMN scheduled_at TIMESTAMPTZ,
    ADD CONSTRAINT chk_patrol_task_schedule CHECK (scheduled_at IS NULL OR schedule_cron IS NULL);
