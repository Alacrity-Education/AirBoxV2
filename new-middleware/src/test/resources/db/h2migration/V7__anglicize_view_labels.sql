-- H2 twin of V7: anglicize the internal Grafana-artifact view identifiers.
-- Renames the three stored view labels to English in place. 'overview' stays.
UPDATE airbox_grafana_artifacts SET view = 'map'              WHERE view = 'harta';
UPDATE airbox_grafana_artifacts SET view = 'station_overview' WHERE view = 'vedere';
UPDATE airbox_grafana_artifacts SET view = 'station_details'  WHERE view = 'detalii';
