package com.cezar.newmiddleware.repository;

import com.cezar.newmiddleware.dto.GrafanaArtifactDTO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Repository
public class GrafanaArtifactRepository {
    private static final String INSERT_QUERY  = """
            INSERT INTO airbox_grafana_artifacts(dashboard_uid, device_id, view, folder_uid,
                                    access_token, public_url, template_hash, synced_at)
            VALUES(?,?,?,?,?,?,?,?)
            """;

    private static final String READ_QUERY = """
            SELECT * FROM airbox_grafana_artifacts
            """;

    private static final String UPDATE_QUERY = """
            UPDATE airbox_grafana_artifacts
            SET device_id = ?, view = ?, folder_uid = ?, access_token = ?,
            public_url = ?, template_hash = ?, synced_at = ?
            WHERE dashboard_uid = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public static final RowMapper<GrafanaArtifactDTO> ARTIFACT_DTO_ROW_MAPPER = ((rs, rowNum) ->
            new GrafanaArtifactDTO(
                    rs.getString("dashboard_uid"),
                    rs.getString("device_id"),
                    rs.getString("view"),
                    rs.getString("folder_uid"),
                    rs.getString("access_token"),
                    rs.getString("public_url"),
                    rs.getString("template_hash"),
                    rs.getObject("synced_at", OffsetDateTime.class)
            ));

    public GrafanaArtifactRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void upsert(GrafanaArtifactDTO ga) {
        int rowsAffected = jdbcTemplate.update(UPDATE_QUERY, ga.deviceId(), ga.view(),
                                                ga.folderUid(), ga.accessToken(), ga.publicUrl(),
                                                ga.templateHash(), ga.syncedAt(), ga.dashboardUid());

        if(rowsAffected == 0) {
            jdbcTemplate.update(INSERT_QUERY, ga.dashboardUid(), ga.deviceId(),
                                ga.view(), ga.folderUid(), ga.accessToken(),
                                ga.publicUrl(), ga.templateHash(), ga.syncedAt());
        }
    }

    public Map<String, GrafanaArtifactDTO> findAllByDashboardUid() {
        return jdbcTemplate.query(READ_QUERY, ARTIFACT_DTO_ROW_MAPPER).stream()
                .collect(Collectors.toMap(GrafanaArtifactDTO::dashboardUid, Function.identity()));
    }
}
