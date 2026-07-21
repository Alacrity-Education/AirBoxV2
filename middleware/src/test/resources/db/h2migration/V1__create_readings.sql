-- AirBox V2 — readings schema (H2 test variant).
-- Mirrors the production Postgres/TimescaleDB schema, minus the Timescale-only
-- create_hypertable() call which does not exist in H2. The column set,
-- nullability and indexes are kept identical so repository SQL is exercised faithfully.
-- Measurement columns are DOUBLE PRECISION (not REAL): the application maps every
-- one to a Java Double, so single-precision storage would lose bits on round-trip.
-- No primary key: mirrors the production hypertable, which has none (the PK
-- would need to include the `time` partitioning column). `id` stays a surrogate.
CREATE TABLE IF NOT EXISTS airbox_readings (
    id            BIGINT GENERATED ALWAYS AS IDENTITY,
    -- Server-assigned ingest time. Client timestamps are never trusted.
    time          TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT now(),
    device        VARCHAR(100)     NOT NULL,
    geohash       VARCHAR(100)     NOT NULL,
    installation  VARCHAR(100)     NOT NULL,
    charge        DOUBLE PRECISION,
    sun           BOOLEAN,
    pm1           DOUBLE PRECISION,
    pm25          DOUBLE PRECISION,
    pm4           DOUBLE PRECISION,
    pm10          DOUBLE PRECISION,
    temp          DOUBLE PRECISION,
    hum           DOUBLE PRECISION,
    voc_index     DOUBLE PRECISION,
    nox_index     DOUBLE PRECISION,
    voc           DOUBLE PRECISION,
    nox           DOUBLE PRECISION,
    co2           DOUBLE PRECISION
);

CREATE INDEX IF NOT EXISTS idx_readings_device       ON airbox_readings (device, time DESC);
CREATE INDEX IF NOT EXISTS idx_readings_installation ON airbox_readings (installation, time DESC);
CREATE INDEX IF NOT EXISTS idx_readings_geohash      ON airbox_readings (geohash, time DESC);
