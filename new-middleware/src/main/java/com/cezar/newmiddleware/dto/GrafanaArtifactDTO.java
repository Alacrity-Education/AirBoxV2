package com.cezar.newmiddleware.dto;

import java.time.OffsetDateTime;

/**
 * One row of airbox_grafana_artifacts: a shared dashboard and its public link.
 * deviceId is null for the global views (map, overview); folderUid and
 * templateHash are null for the provisioned map.
 */
public record GrafanaArtifactDTO(
        String dashboardUid,
        String deviceId,
        String view,
        String folderUid,
        String accessToken,
        String publicUrl,
        String templateHash,
        OffsetDateTime syncedAt
) {}
