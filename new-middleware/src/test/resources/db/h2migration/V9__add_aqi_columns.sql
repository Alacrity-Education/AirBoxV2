-- AirBox V2 — AQI enrichment columns (H2 test variant).
-- Mirrors db/migration/V9__add_aqi_columns.sql. The column types (INTEGER, VARCHAR(10))
-- and nullability match production so the repository INSERT and AQI read-back are
-- exercised faithfully against the mock in-memory H2 database.
ALTER TABLE airbox_readings ADD COLUMN IF NOT EXISTS aqi           INTEGER;
ALTER TABLE airbox_readings ADD COLUMN IF NOT EXISTS aqi_pollutant VARCHAR(10);
