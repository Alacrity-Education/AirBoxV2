-- AirBox V2 — bookkeeping for middleware-generated Grafana dashboards and their public shares.
-- One row per shared dashboard: two per installation (station views st1-/st2-) plus the
-- global overview twin and the provisioned map. device_id is NULL for the global views;
-- folder_uid/template_hash are NULL for the map (provisioned dashboard, no template).
-- No FK to airbox_installations: rows must survive installation deletion for orphan cleanup.
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
