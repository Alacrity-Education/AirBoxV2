-- H2 twin of V5: rename pre-Romanian view identifiers.
UPDATE airbox_grafana_artifacts SET view = 'harta'     WHERE view = 'map';
UPDATE airbox_grafana_artifacts SET view = 'statie_v1' WHERE view = 'station_v1';
UPDATE airbox_grafana_artifacts SET view = 'statie_v2' WHERE view = 'station_v2';
