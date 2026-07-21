-- AirBox V2 — installations schema (H2 test variant).
-- Identical to the production migration; all types are already H2-compatible.
CREATE TABLE IF NOT EXISTS AIRBOX_INSTALLATIONS (
    device_id       VARCHAR(100)  PRIMARY KEY,
    apikey          VARCHAR(100)  NOT NULL,
    owner_email     VARCHAR(100)  NOT NULL,
    co_owner1_email VARCHAR(100),
    co_owner2_email VARCHAR(100),
    installation    VARCHAR(100) NOT NULL,
    notes           TEXT,
    created_at      TIMESTAMP WITH TIME ZONE DEFAULT NOW()
);
