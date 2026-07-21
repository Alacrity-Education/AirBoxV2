-- AirBox V2 — Grafana artifacts schema (H2 test variant).
-- Identical to the production migration; all types are H2-compatible in PostgreSQL mode.
CREATE TABLE IF NOT EXISTS AIRBOX_GRAFANA_ARTIFACTS (
    dashboard_uid VARCHAR(40)  PRIMARY KEY,
    device_id     VARCHAR(100),
    view          VARCHAR(20)  NOT NULL,
    folder_uid    VARCHAR(40),
    access_token  VARCHAR(32)  NOT NULL,
    public_url    TEXT         NOT NULL,
    template_hash VARCHAR(64),
    synced_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW()
);
