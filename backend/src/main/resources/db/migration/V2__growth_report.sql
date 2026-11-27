CREATE TABLE growth_report (
    field_id             BIGINT NOT NULL REFERENCES field(id) ON DELETE CASCADE,
    report_date          DATE NOT NULL,
    storage_key          TEXT NOT NULL,
    pdf_url              TEXT NOT NULL,
    created_at           TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    notification_sent_at TIMESTAMPTZ,
    PRIMARY KEY (field_id, report_date)
);
