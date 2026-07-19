package com.cezar.newmiddleware.grafana;

import com.cezar.newmiddleware.configs.GrafanaProperties;
import com.cezar.newmiddleware.dto.GrafanaArtifactDTO;
import com.cezar.newmiddleware.repository.GrafanaArtifactRepository;
import com.cezar.newmiddleware.repository.InstallationsRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.http.client.MockClientHttpRequest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.test.web.client.RequestMatcher;
import org.springframework.web.client.RestClient;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withServerError;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/**
 * Reconciliation pipeline against H2 (V1, V2, V4 twins) and a MockRestServiceServer
 * standing in for Grafana. The sync flag stays off (test profile), so no real
 * Grafana beans exist — the pipeline is wired manually, exactly as GrafanaSyncConfig
 * would. Expectations are ordered; the job's call order is deterministic
 * (globals first, then installations ordered by device_id, view 1 before view 2).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Grafana dashboard sync job")
class GrafanaSyncJobTest {

    private static final String BASE = "http://grafana.test";
    private static final String PUBLIC = "http://public.test";
    private static final String SECRET = "test-secret";
    private static final String MAP_UID = "map-uid-test";
    private static final String DEVICE = "airbox-t-001";

    private static final String SEARCH_URL =
            BASE + "/api/search?type=dash-db&tag=airbox-generated&limit=5000";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private InstallationsRepository installationsRepository;
    @Autowired private GrafanaArtifactRepository artifactRepository;

    private MockRestServiceServer server;
    private DashboardSyncJob job;
    private String v1Hash;
    private String v2Hash;
    private String overviewHash;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM airbox_grafana_artifacts");
        jdbc.update("DELETE FROM airbox_installations");

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();   // bind BEFORE builder.build()
        GrafanaClient client = new GrafanaClient(builder.build());

        GrafanaProperties props = new GrafanaProperties(BASE, PUBLIC, "", "admin", "admin",
                "", SECRET, MAP_UID, new GrafanaProperties.Sync(true, "-"));
        DashboardTemplateService templateService = new DashboardTemplateService(props.templatesDir());
        DashboardTemplateService.LoadedTemplate nav =
                templateService.load(DashboardTemplateService.NAV_FRAGMENT);
        v1Hash = DashboardTemplateService.combinedHash(
                templateService.load(DashboardTemplateService.TEMPLATE_STATION_V1), nav);
        v2Hash = DashboardTemplateService.combinedHash(
                templateService.load(DashboardTemplateService.TEMPLATE_STATION_V2), nav);
        overviewHash = DashboardTemplateService.combinedHash(
                templateService.load(DashboardTemplateService.TEMPLATE_OVERVIEW), nav);

        job = new DashboardSyncJob(client, templateService,
                installationsRepository, artifactRepository, props);
    }

    // ---------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("first run with no installations syncs the two global views")
    void globalViewsSynced() {
        expectSearch("[]");
        expectFolderCreated(DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectDashboardUpsert(DashboardSyncJob.OVERVIEW_UID, DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectShareCreated(DashboardSyncJob.OVERVIEW_UID);
        expectShareCreated(MAP_UID);   // provisioned map: share only, no folder/dashboard calls

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(2);

        GrafanaArtifactDTO overview = rows.get(DashboardSyncJob.OVERVIEW_UID);
        assertThat(overview.deviceId()).isNull();
        assertThat(overview.view()).isEqualTo(DashboardSyncJob.VIEW_OVERVIEW);
        assertThat(overview.folderUid()).isEqualTo(DashboardSyncJob.GLOBAL_FOLDER_UID);
        assertThat(overview.accessToken()).isEqualTo(token(DashboardSyncJob.OVERVIEW_UID));
        assertThat(overview.publicUrl()).isEqualTo(publicUrl(DashboardSyncJob.OVERVIEW_UID));
        assertThat(overview.templateHash()).isEqualTo(overviewHash);

        GrafanaArtifactDTO map = rows.get(MAP_UID);
        assertThat(map.deviceId()).isNull();
        assertThat(map.view()).isEqualTo(DashboardSyncJob.VIEW_MAP);
        assertThat(map.folderUid()).isNull();
        assertThat(map.templateHash()).isNull();
        assertThat(map.accessToken()).isEqualTo(token(MAP_UID));
    }

    @Test
    @DisplayName("fresh installation gets both station views in its folder, each with its own token")
    void freshDeviceBothViews() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"}]");
        expectFolderCreated(DEVICE);   // once — cached for the second view
        expectDashboardUpsert("st1-" + DEVICE, DEVICE);
        expectShareCreated("st1-" + DEVICE);
        expectDashboardUpsert("st2-" + DEVICE, DEVICE);
        expectShareCreated("st2-" + DEVICE);

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(4);   // 2 globals + 2 station views

        GrafanaArtifactDTO v1 = rows.get("st1-" + DEVICE);
        assertThat(v1.deviceId()).isEqualTo(DEVICE);
        assertThat(v1.view()).isEqualTo(DashboardSyncJob.VIEW_STATION_V1);
        assertThat(v1.folderUid()).isEqualTo(DEVICE);
        assertThat(v1.templateHash()).isEqualTo(v1Hash);
        assertThat(v1.accessToken()).isEqualTo(token("st1-" + DEVICE));
        assertThat(v1.publicUrl()).isEqualTo(publicUrl("st1-" + DEVICE));

        GrafanaArtifactDTO v2 = rows.get("st2-" + DEVICE);
        assertThat(v2.view()).isEqualTo(DashboardSyncJob.VIEW_STATION_V2);
        assertThat(v2.templateHash()).isEqualTo(v2Hash);
        assertThat(v2.accessToken()).isEqualTo(token("st2-" + DEVICE))
                .isNotEqualTo(v1.accessToken());
    }

    @Test
    @DisplayName("second run is a no-op: only the search call happens")
    void secondRunNoOp() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();
        seedStationArtifact("st1-" + DEVICE, DashboardSyncJob.VIEW_STATION_V1, v1Hash);
        seedStationArtifact("st2-" + DEVICE, DashboardSyncJob.VIEW_STATION_V2, v2Hash);

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},"
                + "{\"uid\":\"st1-" + DEVICE + "\"},{\"uid\":\"st2-" + DEVICE + "\"}]");

        job.runOnce();
        server.verify();

        assertThat(artifactRepository.findAllByDashboardUid()).hasSize(4);
    }

    @Test
    @DisplayName("Grafana unreachable: clean abort, nothing written, no exception")
    void grafanaDownAborts() {
        seedInstallation(DEVICE);

        server.expect(requestTo(SEARCH_URL)).andRespond(withServerError());

        job.runOnce();   // must not throw
        server.verify();

        assertThat(artifactRepository.findAllByDashboardUid()).isEmpty();
    }

    @Test
    @DisplayName("one view failing does not stop the other views")
    void oneViewFailsOthersProceed() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"}]");
        expectFolderCreated(DEVICE);
        // view 1 dies at the dashboard upsert -> its share calls never happen
        server.expect(requestTo(BASE + "/api/dashboards/db"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());
        // view 2 proceeds normally (folder already ensured this run)
        expectDashboardUpsert("st2-" + DEVICE, DEVICE);
        expectShareCreated("st2-" + DEVICE);

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(3);   // 2 globals + view 2 only
        assertThat(rows).doesNotContainKey("st1-" + DEVICE);
        assertThat(rows).containsKey("st2-" + DEVICE);
    }

    // ---------------------------------------------------------------------
    // Seed + expectation helpers
    // ---------------------------------------------------------------------

    private void seedInstallation(String deviceId) {
        jdbc.update("""
                INSERT INTO airbox_installations
                    (device_id, apikey, owner_email, installation, notes)
                VALUES (?, ?, ?, ?, ?)
                """, deviceId, "key-" + deviceId, deviceId + "@example.com", "outdoor", null);
    }

    private void seedGlobalArtifacts() {
        artifactRepository.upsert(new GrafanaArtifactDTO(DashboardSyncJob.OVERVIEW_UID, null,
                DashboardSyncJob.VIEW_OVERVIEW, DashboardSyncJob.GLOBAL_FOLDER_UID,
                token(DashboardSyncJob.OVERVIEW_UID), publicUrl(DashboardSyncJob.OVERVIEW_UID),
                overviewHash, OffsetDateTime.now()));
        artifactRepository.upsert(new GrafanaArtifactDTO(MAP_UID, null,
                DashboardSyncJob.VIEW_MAP, null,
                token(MAP_UID), publicUrl(MAP_UID), null, OffsetDateTime.now()));
    }

    private void seedStationArtifact(String uid, String view, String hash) {
        artifactRepository.upsert(new GrafanaArtifactDTO(uid, DEVICE, view, DEVICE,
                token(uid), publicUrl(uid), hash, OffsetDateTime.now()));
    }

    private static String token(String dashboardUid) {
        return PublicTokenDeriver.derive(SECRET, dashboardUid);
    }

    private static String publicUrl(String dashboardUid) {
        return PUBLIC + "/public-dashboards/" + token(dashboardUid);
    }

    private void expectSearch(String uidsJsonArray) {
        server.expect(requestTo(SEARCH_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(uidsJsonArray, MediaType.APPLICATION_JSON));
    }

    private void expectFolderCreated(String folderUid) {
        server.expect(requestTo(BASE + "/api/folders/" + folderUid))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(BASE + "/api/folders"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(bodyContains("\"uid\":\"" + folderUid + "\""))
                .andRespond(withSuccess("{\"uid\":\"" + folderUid + "\"}", MediaType.APPLICATION_JSON));
    }

    private void expectDashboardUpsert(String dashboardUid, String folderUid) {
        server.expect(requestTo(BASE + "/api/dashboards/db"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(bodyContains("\"uid\":\"" + dashboardUid + "\""))
                .andExpect(bodyContains("\"folderUid\":\"" + folderUid + "\""))
                .andExpect(bodyContains("\"overwrite\":true"))
                .andRespond(withSuccess("{\"uid\":\"" + dashboardUid + "\",\"status\":\"success\"}",
                        MediaType.APPLICATION_JSON));
    }

    /** GET finds no share (404), POST creates it with the deterministic HMAC token. */
    private void expectShareCreated(String dashboardUid) {
        String path = BASE + "/api/dashboards/uid/" + dashboardUid + "/public-dashboards";
        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(bodyContains("\"accessToken\":\"" + token(dashboardUid) + "\""))
                .andRespond(withSuccess("{\"uid\":\"pub-" + dashboardUid + "\",\"accessToken\":\""
                        + token(dashboardUid) + "\",\"isEnabled\":true}", MediaType.APPLICATION_JSON));
    }

    /** Substring assertion on the raw request body — avoids a JsonPath/Hamcrest dependency. */
    private static RequestMatcher bodyContains(String expected) {
        return request -> assertThat(((MockClientHttpRequest) request).getBodyAsString()).contains(expected);
    }
}
