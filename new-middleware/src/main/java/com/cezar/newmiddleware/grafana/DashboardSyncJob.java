package com.cezar.newmiddleware.grafana;

import com.cezar.newmiddleware.configs.GrafanaProperties;
import com.cezar.newmiddleware.dto.GrafanaArtifactDTO;
import com.cezar.newmiddleware.entity.Installation;
import com.cezar.newmiddleware.exception.GrafanaApiException;
import com.cezar.newmiddleware.repository.GrafanaArtifactRepository;
import com.cezar.newmiddleware.repository.InstallationsRepository;
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
 * Reconciles desired state against Grafana for the four public views:
 * the provisioned map (share enabled as-is), the generated overview twin
 * (all-devices filter baked in) and, per installation, the two station views
 * (st1- gauges, st2- gauges + plots). Runs hourly and once at startup.
 * NOT a Spring bean by annotation; constructed by GrafanaSyncConfig, so it
 * only exists when mdw.grafana.sync.enabled=true.
 */
public class DashboardSyncJob {

    private static final Logger log = LogManager.getLogger(DashboardSyncJob.class);

    static final String GLOBAL_FOLDER_UID = "airbox-public";
    static final String GLOBAL_FOLDER_TITLE = "AirBox Public";
    static final String OVERVIEW_UID = "airbox-public-overview";
    static final String OVERVIEW_TITLE = "AirBox Overview (public)";

    static final String VIEW_STATION_V1 = "statie_v1";
    static final String VIEW_STATION_V2 = "statie_v2";
    static final String VIEW_OVERVIEW = "overview";
    static final String VIEW_MAP = "harta";

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
        // Loads templates + hashes. If it fails it shall retry next tick
        DashboardTemplateService.LoadedTemplate stationV1, stationV2, overview, nav;
        try {
            stationV1 = templateService.load(DashboardTemplateService.TEMPLATE_STATION_V1);
            stationV2 = templateService.load(DashboardTemplateService.TEMPLATE_STATION_V2);
            overview = templateService.load(DashboardTemplateService.TEMPLATE_OVERVIEW);
            nav = templateService.load(DashboardTemplateService.NAV_FRAGMENT);
        } catch (IOException | RuntimeException e) {
            log.error("Grafana sync aborted: cannot load dashboard templates", e);
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

        // 4a. Global views.
        String overviewHash = DashboardTemplateService.combinedHash(overview, nav);
        if (needsSync(artifacts.get(OVERVIEW_UID), overviewHash, OVERVIEW_UID, existingUids)) {
            try {
                syncGenerated(overview, nav, OVERVIEW_UID, OVERVIEW_TITLE, null,
                        GLOBAL_FOLDER_UID, GLOBAL_FOLDER_TITLE, VIEW_OVERVIEW, ensuredFolders);
                synced++;
            } catch (Exception e) {
                log.error("Grafana sync failed for view '{}'", VIEW_OVERVIEW, e);
                failed++;
            }
        }
        // Provisioned map: no template, no folder — just ensure the public share once.
        if (!artifacts.containsKey(properties.mapUid())) {
            try {
                syncMap();
                synced++;
            } catch (Exception e) {
                log.error("Grafana sync failed for view '{}'", VIEW_MAP, e);
                failed++;
            }
        }

        // 4b. Per-installation station views, isolated failures.
        for (Installation installation : installations) {
            String deviceId = installation.getDeviceId();

            if (deviceId == null || !DashboardTemplateService.DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
                log.error("Grafana sync: device_id rejected by allowlist, skipping: '{}'", deviceId);
                skippedInvalid++;
                continue;
            }

            record StationView(DashboardTemplateService.LoadedTemplate template,
                               String uid, String title, String view) {}
            List<StationView> views = List.of(
                    new StationView(stationV1, "st1-" + deviceId, "AirBox – " + deviceId, VIEW_STATION_V1),
                    new StationView(stationV2, "st2-" + deviceId, "AirBox – " + deviceId + " – detalii", VIEW_STATION_V2));

            for (StationView view : views) {
                String viewHash = DashboardTemplateService.combinedHash(view.template(), nav);
                if (!needsSync(artifacts.get(view.uid()), viewHash, view.uid(), existingUids)) {
                    continue;
                }
                try {
                    syncGenerated(view.template(), nav, view.uid(), view.title(), deviceId,
                            deviceId, deviceId, view.view(), ensuredFolders);
                    synced++;
                } catch (Exception e) {
                    log.error("Grafana sync failed for device '{}' view '{}'", deviceId, view.view(), e);
                    failed++;
                }
            }
        }

        // 5. Summary.
        log.info("Grafana sync: {} installations, {} views synced, {} failed, {} skipped-invalid",
                installations.size(), synced, failed, skippedInvalid);
    }

    private boolean needsSync(GrafanaArtifactDTO artifact, String templateHash,
                              String dashboardUid, Set<String> existingUids) {
        return artifact == null
                || !templateHash.equals(artifact.templateHash())
                || !existingUids.contains(dashboardUid);
    }

    /** Render + folder + upsert + share + artifact row — every step idempotent. */
    private void syncGenerated(DashboardTemplateService.LoadedTemplate template,
                               DashboardTemplateService.LoadedTemplate nav,
                               String uid, String title, String deviceId,
                               String folderUid, String folderTitle, String view,
                               Set<String> ensuredFolders) {
        String rendered = templateService.render(template, nav, uid, title, deviceId);

        if (!ensuredFolders.contains(folderUid)) {
            if (!grafanaClient.folderExists(folderUid)) {
                grafanaClient.createFolder(folderUid, folderTitle);
            }
            ensuredFolders.add(folderUid);
        }

        grafanaClient.upsertDashboard(rendered, folderUid,
                "airbox-sync tpl-" + template.hash() + " nav-" + nav.hash());
        GrafanaClient.PublicDashboard share = ensureShare(uid);

        // Only after every step succeeded: record the artifact (partial failures self-retry next run).
        artifactRepository.upsert(new GrafanaArtifactDTO(uid, deviceId, view, folderUid,
                share.accessToken(), publicUrl(share),
                DashboardTemplateService.combinedHash(template, nav), OffsetDateTime.now()));
    }

    private void syncMap() {
        String uid = properties.mapUid();
        GrafanaClient.PublicDashboard share = ensureShare(uid);
        artifactRepository.upsert(new GrafanaArtifactDTO(uid, null, VIEW_MAP, null,
                share.accessToken(), publicUrl(share), null, OffsetDateTime.now()));
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

    private String publicUrl(GrafanaClient.PublicDashboard share) {
        return properties.publicUrl() + "/public-dashboards/" + share.accessToken();
    }
}
