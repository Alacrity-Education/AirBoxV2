-- Collapse the two per-device station views into one, after the overview/details
-- source dashboards were merged into a single "AirBox Station" dashboard.
-- The abx-overview-<device> twins are retired: their artifact rows are deleted here
-- (their public shares are dropped by the deploy's Grafana dashboard-delete). The former
-- 'station_details' rows become the single 'station' view; their uid/slug
-- ('abx-details-<device>') is intentionally kept so the public detail URLs distributed
-- before the merge keep resolving.
DELETE FROM airbox_grafana_artifacts WHERE view = 'station_overview';
UPDATE airbox_grafana_artifacts SET view = 'station' WHERE view = 'station_details';
