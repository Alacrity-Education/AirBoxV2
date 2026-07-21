-- H2 twin of V6: add the human-readable public-dashboard slug column, finish the
-- Romanian view rename, and backfill slugs. All statements are H2-compatible in
-- PostgreSQL mode (|| string concatenation included). No UNIQUE on slug — see the
-- production migration for the rationale (transient duplicate slugs during the
-- uid-rename deploy window).
ALTER TABLE airbox_grafana_artifacts ADD COLUMN slug VARCHAR(150);

UPDATE airbox_grafana_artifacts SET view = 'vedere'  WHERE view = 'statie_v1';
UPDATE airbox_grafana_artifacts SET view = 'detalii' WHERE view = 'statie_v2';

UPDATE airbox_grafana_artifacts SET slug = 'overview'                   WHERE view = 'overview';
UPDATE airbox_grafana_artifacts SET slug = 'geomap'                     WHERE view = 'harta';
UPDATE airbox_grafana_artifacts SET slug = 'abx-overview-' || device_id WHERE view = 'vedere';
UPDATE airbox_grafana_artifacts SET slug = 'abx-details-'  || device_id WHERE view = 'detalii';
