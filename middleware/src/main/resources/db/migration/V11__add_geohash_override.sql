-- AirBox V2 — geohash override.
-- Optional per-installation geohash that PINS the station's public map location:
-- when set (NOT NULL and not blank), the ingest pipeline stamps every stored reading's
-- geohash with this value, ignoring whatever the device transmits. NULL/blank = disabled
-- (payload geohash is used, exactly as before).
ALTER TABLE airbox_installations ADD COLUMN geohash_override VARCHAR(100);
