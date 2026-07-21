package ro.alacrity.airbox.middleware.grafana;

import ro.alacrity.airbox.middleware.configs.GrafanaProperties;
import ro.alacrity.airbox.middleware.dto.GrafanaArtifactDTO;
import ro.alacrity.airbox.middleware.entity.Installation;
import ro.alacrity.airbox.middleware.exception.GrafanaApiException;
import ro.alacrity.airbox.middleware.repository.GrafanaArtifactRepository;
import ro.alacrity.airbox.middleware.repository.InstallationsRepository;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.IOException;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Reconciles desired state against Grafana for the public views: the generated map twin
 * (cloned from the live provisioned source into the global folder), the generated overview
 * twin (all-devices filter baked in) and, per installation, the single station view
 * (abx-details-&lt;device&gt;, the combined AirBox Station dashboard: gauges, stats and plots).
 * Runs hourly and once at startup. NOT a Spring bean by annotation; constructed by
 * GrafanaSyncConfig, so it only exists when mdw.grafana.sync.enabled=true.
 *
 * Every shared dashboard now carries a human-readable slug (uid == slug for the
 * generated station views; the fixed "overview"/"geomap" slugs for the globals).
 * The slug is the only public URL contract — see PublicDashboardController.
 */
public class DashboardSyncJob {

    private static final Logger log = LogManager.getLogger(DashboardSyncJob.class);

    static final String GLOBAL_FOLDER_UID = "airbox-public";
    static final String GLOBAL_FOLDER_TITLE = "AirBox Public";
    static final String OVERVIEW_UID = "airbox-public-overview";
    static final String OVERVIEW_TITLE = "AirBox Overview (public)";
    static final String OVERVIEW_SLUG = "overview";
    static final String MAP_TWIN_UID = "airbox-public-geomap";
    static final String MAP_SLUG = "geomap";

    // uid/slug prefix for the per-device generated station view. Kept as "abx-details-"
    // (NOT "abx-station-") so the public detail URLs distributed before the overview/details
    // merge stay alive — the combined dashboard simply takes over the former details slug.
    static final String STATION_PREFIX = "abx-details-";

    static final String VIEW_STATION = "station";
    static final String VIEW_OVERVIEW = "overview";
    static final String VIEW_MAP = "map";

    private final GrafanaClient grafanaClient;
    private final DashboardTemplateService templateService;
    private final InstallationsRepository installationsRepository;
    private final GrafanaArtifactRepository artifactRepository;
    private final GrafanaProperties properties;
    private final AtomicBoolean running = new AtomicBoolean(false);

    public DashboardSyncJob(GrafanaClient grafanaClient,
                            DashboardTemplateService templateService,
                            InstallationsRepository installationsRepository,
                            GrafanaArtifactRepository artifactRepository,
                            GrafanaProperties properties) {
        this.grafanaClient = grafanaClient;
        this.templateService = templateService;
        this.installationsRepository = installationsRepository;
        this.artifactRepository = artifactRepository;
        this.properties = properties;
    }

    // Not sure about the safeness of this.
    // I would suggest someone look into this just to make sure no weird races happen
    // Although it should be self-healing.
    // Read the docs of Virtual Threads
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        Thread.ofVirtual().name("grafana-sync-startup").start(this::runOnce);
    }

    @Scheduled(cron = "${mdw.grafana.sync.cron}")
    public void onSchedule() {
        runOnce();
    }

    // Just another safety precaution, overlap and singular entry point.
    // (Claude insisted on adding this, I am quite skeptical of its functionality, but I guess paza buna trece primejdia rea)
    public void runOnce() {
        if (!running.compareAndSet(false, true)) {
            log.warn("Grafana sync already running; skipping this trigger");
            return;
        }
        try {
            sync();
        } finally {
            running.set(false);
        }
    }

    private void sync() {
        // Only the nav chrome fragment is a local resource now; the twins themselves are
        // generated from the LIVE source dashboards fetched from Grafana below. If it fails
        // (missing/malformed fragment) the whole run is aborted and retries next tick.
        DashboardTemplateService.LoadedTemplate nav;
        try {
            nav = templateService.load(DashboardTemplateService.NAV_FRAGMENT);
        } catch (IOException | RuntimeException e) {
            log.error("Grafana sync aborted: cannot load nav fragment", e);
            return;
        }

        // Retrieving existing uids from grafana
        Set<String> existingUids;
        try {
            existingUids = grafanaClient.searchDashboardUidsByTag(DashboardTemplateService.GENERATED_TAG);
        } catch (GrafanaApiException e) {
            log.warn("Grafana sync aborted: Grafana unreachable or search failed", e);
            return;
        }

        // Retrieve installations and artifacts (accounts and already installed dashboards)
        List<Installation> installations = installationsRepository.findAll();
        Map<String, GrafanaArtifactDTO> artifacts = artifactRepository.findAllByDashboardUid();

        int synced = 0, failed = 0, skippedInvalid = 0;
        Set<String> ensuredFolders = new HashSet<>();

        // 4a. Global overview twin: fetch its LIVE source (properties.overviewUid()), transform
        // (device variable -> all-devices filter, nav injected, PII stripped), refresh on change.
        try {
            String source = grafanaClient.getDashboard(properties.overviewUid());
            DashboardTemplateService.LoadedTemplate twin =
                    templateService.transformTwin(source, nav, OVERVIEW_UID, OVERVIEW_TITLE, null);
            if (needsSync(artifacts.get(OVERVIEW_UID), twin.hash(), OVERVIEW_UID, existingUids)) {
                syncGeneratedTwin(twin, OVERVIEW_UID, null, OVERVIEW_SLUG,
                        GLOBAL_FOLDER_UID, GLOBAL_FOLDER_TITLE, VIEW_OVERVIEW, ensuredFolders);
                synced++;
            }
        } catch (Exception e) {
            log.error("Grafana sync failed for view '{}'", VIEW_OVERVIEW, e);
            failed++;
        }
        // Map twin: clone the LIVE provisioned source into the global folder, share the twin,
        // refresh whenever the source (hence the transformed hash) changes or the twin is missing.
        try {
            if (syncMapTwin(existingUids, artifacts, ensuredFolders)) {
                synced++;
            }
        } catch (Exception e) {
            log.error("Grafana sync failed for view '{}'", VIEW_MAP, e);
            failed++;
        }

        // 4b. Per-installation station view. The single LIVE station source is fetched ONCE per
        // run (not per device) and transformed per device; if the source fetch fails, every
        // device's station view fails in isolation, never aborting the whole run.
        if (!installations.isEmpty()) {
            String stationSource = fetchSourceOrNull(properties.stationUid(), VIEW_STATION);

            for (Installation installation : installations) {
                String deviceId = installation.getDeviceId();

                if (deviceId == null || !DashboardTemplateService.DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
                    log.error("Grafana sync: device_id rejected by allowlist, skipping: '{}'", deviceId);
                    skippedInvalid++;
                    continue;
                }

                if (stationSource == null) {   // source fetch failed earlier — fail this view
                    failed++;
                    continue;
                }

                // uid doubles as the public slug for the generated station view.
                String uid = STATION_PREFIX + deviceId;
                try {
                    DashboardTemplateService.LoadedTemplate twin = templateService.transformTwin(
                            stationSource, nav, uid, "AirBox – " + deviceId, deviceId);
                    if (!needsSync(artifacts.get(uid), twin.hash(), uid, existingUids)) {
                        continue;
                    }
                    syncGeneratedTwin(twin, uid, deviceId, uid,
                            deviceId, deviceId, VIEW_STATION, ensuredFolders);
                    synced++;
                } catch (Exception e) {
                    log.error("Grafana sync failed for device '{}' view '{}'", deviceId, VIEW_STATION, e);
                    failed++;
                }
            }
        }

        // 5. Summary.
        log.info("Grafana sync: {} installations, {} views synced, {} failed, {} skipped-invalid",
                installations.size(), synced, failed, skippedInvalid);
    }

    /** Fetch a LIVE source dashboard once; on any Grafana error log loudly and return null so the
     *  dependent views fail in isolation (per-view), never aborting the whole run. */
    private String fetchSourceOrNull(String uid, String view) {
        try {
            return grafanaClient.getDashboard(uid);
        } catch (GrafanaApiException e) {
            log.error("Grafana sync: cannot fetch live source '{}' for view '{}'; its views will fail",
                    uid, view, e);
            return null;
        }
    }

    private boolean needsSync(GrafanaArtifactDTO artifact, String templateHash,
                              String dashboardUid, Set<String> existingUids) {
        return artifact == null
                || !templateHash.equals(artifact.templateHash())
                || !existingUids.contains(dashboardUid);
    }

    /** Folder + upsert + share + artifact row for an already-transformed twin — every step
     *  idempotent. template_hash is the hash of the transformed JSON (folds in source, nav and
     *  substitution changes), matching the map twin's refresh-on-change semantics. */
    private void syncGeneratedTwin(DashboardTemplateService.LoadedTemplate twin,
                                   String uid, String deviceId, String slug,
                                   String folderUid, String folderTitle, String view,
                                   Set<String> ensuredFolders) {
        ensureFolder(folderUid, folderTitle, ensuredFolders);

        grafanaClient.upsertDashboard(twin.json(), folderUid, "airbox-sync " + view + " " + twin.hash());
        GrafanaClient.PublicDashboard share = ensureShare(uid);

        // Only after every step succeeded: record the artifact (partial failures self-retry next run).
        artifactRepository.upsert(new GrafanaArtifactDTO(uid, deviceId, view, slug, folderUid,
                share.accessToken(), publicUrl(slug), twin.hash(), OffsetDateTime.now()));
    }

    /** Ensure a folder exists exactly once per run (create-if-missing, cached in ensuredFolders). */
    private void ensureFolder(String folderUid, String folderTitle, Set<String> ensuredFolders) {
        if (!ensuredFolders.contains(folderUid)) {
            if (!grafanaClient.folderExists(folderUid)) {
                grafanaClient.createFolder(folderUid, folderTitle);
            }
            ensuredFolders.add(folderUid);
        }
    }

    /**
     * Sync the map TWIN. Fetches the LIVE provisioned source (properties.mapUid()) from Grafana,
     * transforms it into a public twin (uid={@link #MAP_TWIN_UID}, id null, version 0, generated
     * tag), and re-upserts into the global folder only when the transformed hash differs or the
     * twin uid is missing from Grafana — same refresh-on-change semantics as the overview twin.
     * The public share is created on the TWIN (never on the source). Returns true iff work was
     * done, so callers count a real sync (an unchanged twin is a logged skip, not a re-upsert).
     */
    private boolean syncMapTwin(Set<String> existingUids,
                                Map<String, GrafanaArtifactDTO> artifacts,
                                Set<String> ensuredFolders) {
        String sourceJson = grafanaClient.getDashboard(properties.mapUid());
        DashboardTemplateService.LoadedTemplate twin =
                templateService.transformMapTwin(sourceJson, MAP_TWIN_UID);

        if (!needsSync(artifacts.get(MAP_TWIN_UID), twin.hash(), MAP_TWIN_UID, existingUids)) {
            log.info("Grafana sync: map twin '{}' unchanged; skipping", MAP_TWIN_UID);
            return false;
        }

        ensureFolder(GLOBAL_FOLDER_UID, GLOBAL_FOLDER_TITLE, ensuredFolders);
        grafanaClient.upsertDashboard(twin.json(), GLOBAL_FOLDER_UID, "airbox-sync map-twin " + twin.hash());
        GrafanaClient.PublicDashboard share = ensureShare(MAP_TWIN_UID);

        artifactRepository.upsert(new GrafanaArtifactDTO(MAP_TWIN_UID, null, VIEW_MAP, MAP_SLUG,
                GLOBAL_FOLDER_UID, share.accessToken(), publicUrl(MAP_SLUG),
                twin.hash(), OffsetDateTime.now()));
        return true;
    }

    /** Adopt an existing token (never break distributed links); re-enable if disabled; else create. */
    private GrafanaClient.PublicDashboard ensureShare(String dashboardUid) {
        Optional<GrafanaClient.PublicDashboard> existing = grafanaClient.getPublicDashboard(dashboardUid);
        if (existing.isPresent()) {
            GrafanaClient.PublicDashboard share = existing.get();
            return share.isEnabled() ? share : grafanaClient.enablePublicDashboard(dashboardUid, share.uid());
        }
        String token = PublicTokenDeriver.derive(properties.publicTokenSecret(), dashboardUid);
        return grafanaClient.createPublicDashboard(dashboardUid, token);
    }

    /** The stored public_url is the PRETTY slug URL; the redirect controller reconstructs
     *  the raw access-token URL from the artifact's accessToken at request time. */
    private String publicUrl(String slug) {
        return properties.publicUrl() + "/public-dashboards/" + slug;
    }
}
