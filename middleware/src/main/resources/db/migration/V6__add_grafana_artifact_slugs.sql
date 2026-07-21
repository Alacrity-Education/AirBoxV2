-- AirBox V2 — human-readable public-dashboard slugs.
-- The slug is now the ONLY public URL contract: the middleware maps
-- GET /public-dashboards/<slug> -> the internal Grafana access-token URL.
-- This migration also finishes the Romanian view rename V5 left half-done
-- (V5 renamed 'map'->'harta' but left the two station labels) and backfills a
-- slug for every existing row.
--
-- NO UNIQUE constraint on slug: on the uid-rename deploy the pre-rename st1-/st2-
-- rows are re-slugged to the very same 'abx-overview-'/'abx-details-' value the
-- freshly synced abx-* rows will use, so a transient duplicate slug exists until
-- the deploy agent purges the orphaned st1-/st2- rows. A UNIQUE constraint would
-- make the startup sync's INSERT fail hard in that window; instead the redirect
-- controller disambiguates by newest synced_at.
ALTER TABLE airbox_grafana_artifacts ADD COLUMN slug VARCHAR(150);

-- Finish the Romanian rename (V5 did 'map'->'harta' only).
UPDATE airbox_grafana_artifacts SET view = 'vedere'  WHERE view = 'statie_v1';
UPDATE airbox_grafana_artifacts SET view = 'detalii' WHERE view = 'statie_v2';

-- Backfill slugs from the (now-current) view labels.
UPDATE airbox_grafana_artifacts SET slug = 'overview'                   WHERE view = 'overview';
UPDATE airbox_grafana_artifacts SET slug = 'geomap'                     WHERE view = 'harta';
UPDATE airbox_grafana_artifacts SET slug = 'abx-overview-' || device_id WHERE view = 'vedere';
UPDATE airbox_grafana_artifacts SET slug = 'abx-details-'  || device_id WHERE view = 'detalii';
