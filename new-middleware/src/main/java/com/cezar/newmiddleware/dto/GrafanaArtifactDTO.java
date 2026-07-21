package com.cezar.newmiddleware.dto;

import java.time.OffsetDateTime;

/**
 * One row of airbox_grafana_artifacts: a shared dashboard and its public link.
 * deviceId is null for the global views (map, overview); folderUid and
 * templateHash are null for the provisioned map.
 *
 * slug is the human-readable public URL segment ("overview", "geomap",
 * "abx-overview-&lt;deviceId&gt;", "abx-details-&lt;deviceId&gt;"). publicUrl now stores the
 * PRETTY slug URL (&lt;public-url&gt;/public-dashboards/&lt;slug&gt;); the raw Grafana
 * access-token URL is reconstructed by the redirect controller from accessToken.
 */
public record GrafanaArtifactDTO(
        String dashboardUid,
        String deviceId,
        String view,
        String slug,
        String folderUid,
        String accessToken,
        String publicUrl,
        String templateHash,
        OffsetDateTime syncedAt
) {}
