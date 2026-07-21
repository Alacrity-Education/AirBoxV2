-- AirBox V2 — readings schema.
CREATE TABLE IF NOT EXISTS airbox_readings (
    id            bigint GENERATED ALWAYS AS IDENTITY,
    time          timestamptz      NOT NULL DEFAULT now(),
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

SELECT create_hypertable('airbox_readings', 'time', if_not_exists => TRUE);
CREATE INDEX IF NOT EXISTS idx_readings_device       ON AIRBOX_READINGS (device, time DESC);
CREATE INDEX IF NOT EXISTS idx_readings_installation ON AIRBOX_READINGS (installation, time DESC);
CREATE INDEX IF NOT EXISTS idx_readings_geohash      ON AIRBOX_READINGS (geohash, time DESC);