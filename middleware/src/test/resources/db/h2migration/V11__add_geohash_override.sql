-- AirBox V2 — geohash override (H2 test variant).
-- Identical to the production migration; VARCHAR is already H2-compatible.
ALTER TABLE airbox_installations ADD COLUMN geohash_override VARCHAR(100);
