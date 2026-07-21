-- AirBox V2 — AQI enrichment columns.
-- The middleware computes an EPA-style Air Quality Index per reading at ingest
-- (see com.cezar.newmiddleware.aqi.AqiCalculator) and stores the result here.
--   aqi           : final AQI value (max of the pollutant sub-indices, rounded to
--                   the nearest integer). NULL when the reading is not eligible
--                   (fewer than 3 computable pollutant sub-indices, or none of them
--                   is PM2.5 / PM10).
--   aqi_pollutant : the pollutant whose sub-index produced the final AQI
--                   ('pm25' | 'pm10' | 'no2'). Populated only alongside a non-NULL aqi.
-- Both are nullable; existing rows stay NULL (no backfill — enrichment is ingest-time only).
ALTER TABLE airbox_readings ADD COLUMN IF NOT EXISTS aqi           INTEGER;
ALTER TABLE airbox_readings ADD COLUMN IF NOT EXISTS aqi_pollutant VARCHAR(10);
