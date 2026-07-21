-- Rename pre-Romanian view identifiers written by earlier sync builds.
-- Generated dashboards would self-heal on the next hash-change re-sync, but the
-- map row is only re-synced when missing, so 'map' would otherwise stay stale
-- and the nav panels (which filter on view = 'harta') would never list it.
UPDATE airbox_grafana_artifacts SET view = 'harta'     WHERE view = 'map';
UPDATE airbox_grafana_artifacts SET view = 'statie_v1' WHERE view = 'station_v1';
UPDATE airbox_grafana_artifacts SET view = 'statie_v2' WHERE view = 'station_v2';
