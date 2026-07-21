-- H2 twin of V8: collapse the two per-device station views into one.
-- Delete the retired 'station_overview' rows; the 'station_details' rows become 'station'
-- (uid/slug 'abx-details-<device>' preserved).
DELETE FROM airbox_grafana_artifacts WHERE view = 'station_overview';
UPDATE airbox_grafana_artifacts SET view = 'station' WHERE view = 'station_details';
