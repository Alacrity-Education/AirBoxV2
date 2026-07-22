package ro.alacrity.airbox.middleware.controller;

import ro.alacrity.airbox.middleware.dto.GrafanaArtifactDTO;
import ro.alacrity.airbox.middleware.repository.GrafanaArtifactRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.OffsetDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The public-dashboard redirector maps human-readable slugs to the raw Grafana
 * access-token URL. The sync flag is off (test profile), so the artifacts table is
 * populated directly — proving the controller depends only on the repository and the
 * public-url property, not on the sync-gated Grafana beans.
 */
@SpringBootTest(properties = "mdw.grafana.public-url=http://public.test/g")
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Public dashboard redirect controller")
class PublicDashboardControllerTest {

    private static final String PUBLIC = "http://public.test/g";
    private static final String OVERVIEW_TOKEN = "abcdef0123456789abcdef0123456789";
    private static final String DEVICE_TOKEN = "0011223344556677889900aabbccddee";

    @Autowired private MockMvc mockMvc;
    @Autowired private GrafanaArtifactRepository artifactRepository;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        jdbc.update("DELETE FROM airbox_grafana_artifacts");

        artifactRepository.upsert(new GrafanaArtifactDTO(
                "airbox-public-overview", null, "overview", "overview", "airbox-public",
                OVERVIEW_TOKEN, PUBLIC + "/public-dashboards/overview", "hash-a", OffsetDateTime.now()));

        artifactRepository.upsert(new GrafanaArtifactDTO(
                "abx-details-airbox-t-001", "airbox-t-001", "station", "abx-details-airbox-t-001",
                "airbox-t-001", DEVICE_TOKEN,
                PUBLIC + "/public-dashboards/abx-details-airbox-t-001", "hash-b", OffsetDateTime.now()));
    }

    @Test
    @DisplayName("known global slug -> 302 to the raw access-token URL")
    void knownGlobalSlugRedirects() throws Exception {
        mockMvc.perform(get("/public-dashboards/{slug}", "overview"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(PUBLIC + "/public-dashboards/" + OVERVIEW_TOKEN));
    }

    @Test
    @DisplayName("known per-device slug -> 302 to its own access-token URL")
    void knownDeviceSlugRedirects() throws Exception {
        mockMvc.perform(get("/public-dashboards/{slug}", "abx-details-airbox-t-001"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", PUBLIC + "/public-dashboards/" + DEVICE_TOKEN));
    }

    @Test
    @DisplayName("unknown slug -> 404")
    void unknownSlug404() throws Exception {
        mockMvc.perform(get("/public-dashboards/{slug}", "no-such-slug"))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("malformed (over-length) slug -> 404, never touches the DB mapping")
    void malformedSlug404() throws Exception {
        mockMvc.perform(get("/public-dashboards/{slug}", "x".repeat(151)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("slug-token: known global slug -> 200, empty body, X-Abx-Token header carries the token")
    void slugTokenKnownGlobal() throws Exception {
        mockMvc.perform(get("/internal/slug-token/{slug}", "overview"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Abx-Token", OVERVIEW_TOKEN))
                .andExpect(content().string(""));
    }

    @Test
    @DisplayName("slug-token: known per-device slug -> 200 with its own token")
    void slugTokenKnownDevice() throws Exception {
        mockMvc.perform(get("/internal/slug-token/{slug}", "abx-details-airbox-t-001"))
                .andExpect(status().isOk())
                .andExpect(header().string("X-Abx-Token", DEVICE_TOKEN));
    }

    @Test
    @DisplayName("slug-token: unknown slug -> 404, no token header")
    void slugTokenUnknown404() throws Exception {
        mockMvc.perform(get("/internal/slug-token/{slug}", "no-such-slug"))
                .andExpect(status().isNotFound())
                .andExpect(header().doesNotExist("X-Abx-Token"));
    }

    @Test
    @DisplayName("slug-token: malformed (over-length) slug -> 404")
    void slugTokenMalformed404() throws Exception {
        mockMvc.perform(get("/internal/slug-token/{slug}", "x".repeat(151)))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("newest row wins when a slug is transiently duplicated (rename deploy window)")
    void duplicateSlugPrefersNewest() throws Exception {
        // An orphaned older row carrying the same slug but the OLD token, synced earlier.
        String oldToken = "ffffffffffffffffffffffffffffffff";
        jdbc.update("""
                INSERT INTO airbox_grafana_artifacts
                    (dashboard_uid, device_id, view, slug, folder_uid, access_token, public_url, template_hash, synced_at)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,
                "st1-airbox-t-001", "airbox-t-001", "station", "abx-details-airbox-t-001",
                "airbox-t-001", oldToken, PUBLIC + "/public-dashboards/abx-details-airbox-t-001",
                "hash-old", OffsetDateTime.now().minusHours(2));

        // The abx-details- row seeded in @BeforeEach is newer, so its token must win.
        mockMvc.perform(get("/public-dashboards/{slug}", "abx-details-airbox-t-001"))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl(PUBLIC + "/public-dashboards/" + DEVICE_TOKEN));
    }
}
