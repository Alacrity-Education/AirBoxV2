package ro.alacrity.airbox.middleware.grafana;

import ro.alacrity.airbox.middleware.configs.GrafanaProperties;
import ro.alacrity.airbox.middleware.dto.GrafanaArtifactDTO;
import ro.alacrity.airbox.middleware.repository.GrafanaArtifactRepository;
import ro.alacrity.airbox.middleware.repository.InstallationsRepository;
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
 * Reconciliation pipeline against H2 (V1, V2, V4–V8 twins) and a MockRestServiceServer
 * standing in for Grafana. The sync flag stays off (test profile), so no real Grafana beans
 * exist — the pipeline is wired manually, exactly as GrafanaSyncConfig would.
 *
 * ALL twins follow the geomap's live-source flow: the overview and the single per-device
 * station view are generated from LIVE source dashboards fetched from Grafana
 * (GET /api/dashboards/uid/{uid}) and transformed, exactly like the map twin. Expectations
 * are ordered; the job's call order is deterministic: search, overview source (+twin), map
 * source (+twin), then — only when there are installations — the ONE station source fetched
 * once, then the per-device station view, installations ordered by device_id.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Grafana dashboard sync job")
class GrafanaSyncJobTest {

    private static final String BASE = "http://grafana.test";
    private static final String PUBLIC = "http://public.test";
    private static final String SECRET = "test-secret";
    private static final String MAP_UID = "map-uid-test";
    private static final String OVERVIEW_SRC_UID = "src-overview";
    private static final String STATION_SRC_UID = "src-station";
    private static final String MAP_TWIN_UID = DashboardSyncJob.MAP_TWIN_UID;
    private static final String DEVICE = "airbox-t-001";

    // Live source dashboards returned by GET /api/dashboards/uid/{uid} (the .dashboard node).
    // Each carries a device template variable and a ${device:sqlstring} filter, so transformTwin
    // exercises variable removal + substitution. transformTwin re-keys/normalizes them and the
    // same bytes drive the expected twin hashes.
    private static final String MAP_SOURCE_JSON =
            "{\"uid\":\"" + MAP_UID + "\",\"id\":42,\"version\":7,"
            + "\"title\":\"AirBox Geomap View\",\"tags\":[\"airbox\"],\"panels\":[]}";

    private static final String OVERVIEW_SOURCE_JSON = """
            {"uid":"src-overview","id":1,"version":5,"title":"AirBox Overview","tags":["airbox"],
             "templating":{"list":[{"name":"device","type":"query"}]},"links":[],
             "panels":[{"id":1,"type":"stat","gridPos":{"h":4,"w":6,"x":0,"y":0},
               "targets":[{"refId":"A","rawSql":"SELECT count(*) FROM airbox_readings WHERE device IN (${device:sqlstring})"}]}]}""";

    private static final String STATION_SOURCE_JSON = """
            {"uid":"src-station","id":2,"version":3,"title":"AirBox Station","tags":["airbox"],
             "templating":{"list":[{"name":"device","type":"query"}]},"links":[],
             "panels":[{"id":1,"type":"stat","gridPos":{"h":4,"w":6,"x":0,"y":0},
               "targets":[{"refId":"A","rawSql":"SELECT avg(pm25) FROM airbox_readings WHERE device = ${device:sqlstring}"}]}]}""";

    // uid == slug for the generated per-device view.
    private static final String STATION_UID = "abx-details-" + DEVICE;

    private static final String SEARCH_URL =
            BASE + "/api/search?type=dash-db&tag=airbox-generated&limit=5000";

    @Autowired private JdbcTemplate jdbc;
    @Autowired private InstallationsRepository installationsRepository;
    @Autowired private GrafanaArtifactRepository artifactRepository;

    private MockRestServiceServer server;
    private DashboardSyncJob job;
    private String stationHash;
    private String overviewHash;
    private String mapTwinHash;

    @BeforeEach
    void setUp() throws Exception {
        jdbc.update("DELETE FROM airbox_grafana_artifacts");
        jdbc.update("DELETE FROM airbox_installations");

        RestClient.Builder builder = RestClient.builder().baseUrl(BASE);
        server = MockRestServiceServer.bindTo(builder).build();   // bind BEFORE builder.build()
        GrafanaClient client = new GrafanaClient(builder.build());

        GrafanaProperties props = new GrafanaProperties(BASE, PUBLIC, "", "admin", "admin",
                "", SECRET, MAP_UID, OVERVIEW_SRC_UID, STATION_SRC_UID,
                new GrafanaProperties.Sync(true, "-"));
        DashboardTemplateService templateService = new DashboardTemplateService(props.templatesDir());
        DashboardTemplateService.LoadedTemplate nav =
                templateService.load(DashboardTemplateService.NAV_FRAGMENT);

        // The expected twin hashes are the hashes of the fully transformed JSON (as the map twin
        // does) — computed with the exact uid/title/device the job uses.
        overviewHash = templateService.transformTwin(OVERVIEW_SOURCE_JSON, nav,
                DashboardSyncJob.OVERVIEW_UID, DashboardSyncJob.OVERVIEW_TITLE, null).hash();
        mapTwinHash = templateService.transformMapTwin(MAP_SOURCE_JSON, MAP_TWIN_UID).hash();
        stationHash = templateService.transformTwin(STATION_SOURCE_JSON, nav,
                STATION_UID, "AirBox – " + DEVICE, DEVICE).hash();

        job = new DashboardSyncJob(client, templateService,
                installationsRepository, artifactRepository, props);
    }

    // ---------------------------------------------------------------------
    // Scenarios
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("first run with no installations syncs the two global twins (overview + map)")
    void globalViewsSynced() {
        expectSearch("[]");
        // Overview twin: fetch the LIVE source, ensure the global folder, upsert + share.
        expectOverviewSourceGet();
        expectFolderCreated(DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectDashboardUpsert(DashboardSyncJob.OVERVIEW_UID, DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectShareCreated(DashboardSyncJob.OVERVIEW_UID);
        // Map twin: fetch the LIVE source, upsert into the global folder (already ensured) and share.
        expectMapSourceGet();
        expectDashboardUpsert(MAP_TWIN_UID, DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectShareCreated(MAP_TWIN_UID);
        // No installations -> the station source is NOT fetched.

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(2);

        GrafanaArtifactDTO overview = rows.get(DashboardSyncJob.OVERVIEW_UID);
        assertThat(overview.deviceId()).isNull();
        assertThat(overview.view()).isEqualTo(DashboardSyncJob.VIEW_OVERVIEW);
        assertThat(overview.slug()).isEqualTo(DashboardSyncJob.OVERVIEW_SLUG);
        assertThat(overview.folderUid()).isEqualTo(DashboardSyncJob.GLOBAL_FOLDER_UID);
        assertThat(overview.accessToken()).isEqualTo(token(DashboardSyncJob.OVERVIEW_UID));
        assertThat(overview.publicUrl()).isEqualTo(publicUrl(DashboardSyncJob.OVERVIEW_SLUG));
        assertThat(overview.templateHash()).isEqualTo(overviewHash);

        assertThat(rows).doesNotContainKey(MAP_UID);
        GrafanaArtifactDTO map = rows.get(MAP_TWIN_UID);
        assertThat(map.deviceId()).isNull();
        assertThat(map.view()).isEqualTo(DashboardSyncJob.VIEW_MAP);
        assertThat(map.slug()).isEqualTo(DashboardSyncJob.MAP_SLUG);
        assertThat(map.folderUid()).isEqualTo(DashboardSyncJob.GLOBAL_FOLDER_UID);
        assertThat(map.templateHash()).isEqualTo(mapTwinHash);
        assertThat(map.accessToken()).isEqualTo(token(MAP_TWIN_UID));
        assertThat(map.publicUrl()).isEqualTo(publicUrl(DashboardSyncJob.MAP_SLUG));
    }

    @Test
    @DisplayName("overview twin refreshes when its live source changes (hash differs)")
    void overviewTwinRefreshesOnSourceChange() {
        seedGlobalArtifacts();
        String changedOverview = OVERVIEW_SOURCE_JSON.replace("count(*)", "count(1)");

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},"
                + "{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        // Overview source now differs -> transformed hash differs -> re-upsert (folder ensured here).
        server.expect(requestTo(BASE + "/api/dashboards/uid/" + OVERVIEW_SRC_UID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"dashboard\":" + changedOverview + "}", MediaType.APPLICATION_JSON));
        expectFolderExists(DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectDashboardUpsert(DashboardSyncJob.OVERVIEW_UID, DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectShareAdopted(DashboardSyncJob.OVERVIEW_UID);
        // Map twin unchanged -> fetch only, no upsert.
        expectMapSourceGet();

        job.runOnce();
        server.verify();

        GrafanaArtifactDTO overview = artifactRepository.findAllByDashboardUid().get(DashboardSyncJob.OVERVIEW_UID);
        assertThat(overview.templateHash()).isNotEqualTo(overviewHash);
    }

    @Test
    @DisplayName("map twin refreshes when the live source changes (hash differs)")
    void mapTwinRefreshesOnSourceChange() {
        seedGlobalArtifacts();
        String changedSource = "{\"uid\":\"" + MAP_UID + "\",\"id\":42,\"version\":9,"
                + "\"title\":\"AirBox Geomap View\",\"tags\":[\"airbox\"],"
                + "\"panels\":[{\"id\":1,\"type\":\"text\"}]}";

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},"
                + "{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        expectOverviewSourceGet();   // overview unchanged -> fetch only
        server.expect(requestTo(BASE + "/api/dashboards/uid/" + MAP_UID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"dashboard\":" + changedSource + "}", MediaType.APPLICATION_JSON));
        // Overview skipped its upsert, so the map twin ensures the global folder itself (it exists).
        expectFolderExists(DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectDashboardUpsert(MAP_TWIN_UID, DashboardSyncJob.GLOBAL_FOLDER_UID);
        expectShareAdopted(MAP_TWIN_UID);

        job.runOnce();
        server.verify();

        GrafanaArtifactDTO map = artifactRepository.findAllByDashboardUid().get(MAP_TWIN_UID);
        assertThat(map.templateHash())
                .isNotEqualTo(mapTwinHash)
                .isEqualTo(new DashboardTemplateService(null).transformMapTwin(changedSource, MAP_TWIN_UID).hash());
    }

    @Test
    @DisplayName("fresh installation gets its single station view in its folder, with its own token")
    void freshDeviceStationView() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},"
                + "{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        expectOverviewSourceGet();   // unchanged -> fetch only
        expectMapSourceGet();        // unchanged -> fetch only
        // Installations present -> the station source is fetched once.
        expectStationSourceGet();
        expectFolderCreated(DEVICE);
        expectDashboardUpsert(STATION_UID, DEVICE);
        expectShareCreated(STATION_UID);

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(3);   // 2 globals + 1 station view

        GrafanaArtifactDTO station = rows.get(STATION_UID);
        assertThat(station.deviceId()).isEqualTo(DEVICE);
        assertThat(station.view()).isEqualTo(DashboardSyncJob.VIEW_STATION);
        assertThat(station.slug()).isEqualTo(STATION_UID);   // uid == slug
        assertThat(station.folderUid()).isEqualTo(DEVICE);
        assertThat(station.templateHash()).isEqualTo(stationHash);
        assertThat(station.accessToken()).isEqualTo(token(STATION_UID));
        assertThat(station.publicUrl()).isEqualTo(publicUrl(STATION_UID));
    }

    @Test
    @DisplayName("second run is a no-op: search + the three source fetches, no upserts")
    void secondRunNoOp() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();
        seedStationArtifact(STATION_UID, DashboardSyncJob.VIEW_STATION, stationHash);

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},{\"uid\":\"" + MAP_TWIN_UID + "\"},"
                + "{\"uid\":\"" + STATION_UID + "\"}]");
        // Everything unchanged: sources are still fetched (once each), nothing re-upserts.
        expectOverviewSourceGet();
        expectMapSourceGet();
        expectStationSourceGet();

        job.runOnce();
        server.verify();

        assertThat(artifactRepository.findAllByDashboardUid()).hasSize(3);
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
    @DisplayName("a missing station source fails the device station view; the global twins proceed")
    void missingStationSourceIsolated() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},"
                + "{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        expectOverviewSourceGet();   // unchanged
        expectMapSourceGet();        // unchanged
        // The station source 404s -> the device station view fails; globals already synced.
        server.expect(requestTo(BASE + "/api/dashboards/uid/" + STATION_SRC_UID))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(2);   // globals only
        assertThat(rows).doesNotContainKey(STATION_UID);
    }

    @Test
    @DisplayName("the station upsert failing does not stop the already-synced global twins")
    void stationUpsertFailsGlobalsProceed() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},"
                + "{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        expectOverviewSourceGet();
        expectMapSourceGet();
        expectStationSourceGet();
        expectFolderCreated(DEVICE);
        // the station view dies at the dashboard upsert -> its share calls never happen
        server.expect(requestTo(BASE + "/api/dashboards/db"))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withServerError());

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(2);   // globals only
        assertThat(rows).doesNotContainKey(STATION_UID);
    }

    @Test
    @DisplayName("create public dashboard 400 fails hard: no random-token retry, no artifact row")
    void createPublicDashboard_onBadRequest_failsHardNoRandomToken() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        expectOverviewSourceGet();
        expectMapSourceGet();
        expectStationSourceGet();
        expectFolderCreated(DEVICE);
        expectDashboardUpsert(STATION_UID, DEVICE);
        // Create rejected with 400. The deleted fallback would have re-POSTed WITHOUT the token;
        // we expect NO such second POST — it would surface here as an unexpected request.
        expectShareCreateBadRequest(STATION_UID);

        job.runOnce();     // GrafanaApiException swallowed by the per-view catch
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(2);   // globals only; station never written
        assertThat(rows).doesNotContainKey(STATION_UID);
        rows.forEach((uid, row) -> assertThat(row.accessToken()).isEqualTo(token(uid)));
    }

    @Test
    @DisplayName("divergent echoed token fails hard: view failure recorded, divergent token not persisted")
    void createPublicDashboard_onDivergentEcho_failsHard() {
        seedInstallation(DEVICE);
        seedGlobalArtifacts();

        String divergentToken = "deadbeefdeadbeefdeadbeefdeadbeef";
        assertThat(divergentToken).isNotEqualTo(token(STATION_UID));

        expectSearch("[{\"uid\":\"" + DashboardSyncJob.OVERVIEW_UID + "\"},{\"uid\":\"" + MAP_TWIN_UID + "\"}]");
        expectOverviewSourceGet();
        expectMapSourceGet();
        expectStationSourceGet();
        expectFolderCreated(DEVICE);
        expectDashboardUpsert(STATION_UID, DEVICE);
        expectShareCreateDivergentEcho(STATION_UID, divergentToken);

        job.runOnce();
        server.verify();

        Map<String, GrafanaArtifactDTO> rows = artifactRepository.findAllByDashboardUid();
        assertThat(rows).hasSize(2);   // station failed hard, no row persisted
        assertThat(rows).doesNotContainKey(STATION_UID);
        assertThat(rows.values()).noneMatch(r -> divergentToken.equals(r.accessToken()));
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
                DashboardSyncJob.VIEW_OVERVIEW, DashboardSyncJob.OVERVIEW_SLUG,
                DashboardSyncJob.GLOBAL_FOLDER_UID,
                token(DashboardSyncJob.OVERVIEW_UID), publicUrl(DashboardSyncJob.OVERVIEW_SLUG),
                overviewHash, OffsetDateTime.now()));
        artifactRepository.upsert(new GrafanaArtifactDTO(MAP_TWIN_UID, null,
                DashboardSyncJob.VIEW_MAP, DashboardSyncJob.MAP_SLUG, DashboardSyncJob.GLOBAL_FOLDER_UID,
                token(MAP_TWIN_UID), publicUrl(DashboardSyncJob.MAP_SLUG), mapTwinHash, OffsetDateTime.now()));
    }

    /** Station uid doubles as its slug. */
    private void seedStationArtifact(String uid, String view, String hash) {
        artifactRepository.upsert(new GrafanaArtifactDTO(uid, DEVICE, view, uid, DEVICE,
                token(uid), publicUrl(uid), hash, OffsetDateTime.now()));
    }

    private static String token(String dashboardUid) {
        return PublicTokenDeriver.derive(SECRET, dashboardUid);
    }

    /** The stored public_url is the PRETTY slug URL. */
    private static String publicUrl(String slug) {
        return PUBLIC + "/public-dashboards/" + slug;
    }

    private void expectSourceGet(String sourceUid, String sourceJson) {
        server.expect(requestTo(BASE + "/api/dashboards/uid/" + sourceUid))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"dashboard\":" + sourceJson + "}", MediaType.APPLICATION_JSON));
    }

    private void expectMapSourceGet()      { expectSourceGet(MAP_UID, MAP_SOURCE_JSON); }
    private void expectOverviewSourceGet() { expectSourceGet(OVERVIEW_SRC_UID, OVERVIEW_SOURCE_JSON); }
    private void expectStationSourceGet()  { expectSourceGet(STATION_SRC_UID, STATION_SOURCE_JSON); }

    /** GET finds an already-enabled share; it is adopted as-is (no create/enable POST/PATCH). */
    private void expectShareAdopted(String dashboardUid) {
        server.expect(requestTo(BASE + "/api/dashboards/uid/" + dashboardUid + "/public-dashboards"))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"uid\":\"pub-" + dashboardUid + "\",\"accessToken\":\""
                        + token(dashboardUid) + "\",\"isEnabled\":true}", MediaType.APPLICATION_JSON));
    }

    private void expectSearch(String uidsJsonArray) {
        server.expect(requestTo(SEARCH_URL))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(uidsJsonArray, MediaType.APPLICATION_JSON));
    }

    private void expectFolderExists(String folderUid) {
        server.expect(requestTo(BASE + "/api/folders/" + folderUid))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess("{\"uid\":\"" + folderUid + "\"}", MediaType.APPLICATION_JSON));
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

    /** GET finds no share (404); the single create POST is rejected with 400 — the fail-hard path. */
    private void expectShareCreateBadRequest(String dashboardUid) {
        String path = BASE + "/api/dashboards/uid/" + dashboardUid + "/public-dashboards";
        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(bodyContains("\"accessToken\":\"" + token(dashboardUid) + "\""))
                .andRespond(withStatus(HttpStatus.BAD_REQUEST));
    }

    /** GET 404; create POST returns 2xx but echoes a token different from the one we sent. */
    private void expectShareCreateDivergentEcho(String dashboardUid, String divergentToken) {
        String path = BASE + "/api/dashboards/uid/" + dashboardUid + "/public-dashboards";
        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));
        server.expect(requestTo(path))
                .andExpect(method(HttpMethod.POST))
                .andExpect(bodyContains("\"accessToken\":\"" + token(dashboardUid) + "\""))
                .andRespond(withSuccess("{\"uid\":\"pub-" + dashboardUid + "\",\"accessToken\":\""
                        + divergentToken + "\",\"isEnabled\":true}", MediaType.APPLICATION_JSON));
    }

    /** Substring assertion on the raw request body — avoids a JsonPath/Hamcrest dependency. */
    private static RequestMatcher bodyContains(String expected) {
        return request -> assertThat(((MockClientHttpRequest) request).getBodyAsString()).contains(expected);
    }
}
