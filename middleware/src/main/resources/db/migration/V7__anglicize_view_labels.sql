-- Anglicize the internal Grafana-artifact view identifiers.
-- V5/V6 left the view labels in Romanian ('harta'/'vedere'/'detalii'); this
-- migration renames the three stored values to English in place. 'overview'
-- already English and stays. Slugs, uids, tokens and display texts are final
-- and untouched — only airbox_grafana_artifacts.view changes.
UPDATE airbox_grafana_artifacts SET view = 'map'              WHERE view = 'harta';
UPDATE airbox_grafana_artifacts SET view = 'station_overview' WHERE view = 'vedere';
UPDATE airbox_grafana_artifacts SET view = 'station_details'  WHERE view = 'detalii';
