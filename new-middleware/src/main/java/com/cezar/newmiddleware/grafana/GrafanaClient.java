package com.cezar.newmiddleware.grafana;

import com.cezar.newmiddleware.exception.GrafanaApiException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;

public class GrafanaClient {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // GrafanaClient is caller, HttpClient
    private final RestClient restClient;

    public GrafanaClient(RestClient restClient) {
        this.restClient = restClient;
    }

    public record PublicDashboard(String uid, String accessToken, boolean isEnabled) {}

    public Set<String> searchDashboardUidsByTag(String tag) {
        try {
            String body = restClient.get()
                    .uri(b -> b.path("/api/search")
                            .queryParam("type", "dash-db")
                            .queryParam("tag", tag)
                            .queryParam("limit", 5000)
                            .build())
                    .retrieve().body(String.class);
            Set<String> uids = new HashSet<>();
            for (JsonNode hit : MAPPER.readTree(body)) {
                uids.add(hit.path("uid").stringValue(""));
            }
            return uids;
        } catch (RestClientException | JacksonException e) {
            throw new GrafanaApiException("Grafana search by tag '" + tag + "' failed", e);
        }
    }

    /** Fetch the LIVE dashboard model for a uid (GET /api/dashboards/uid/{uid} → .dashboard).
     *  Used to clone the provisioned map into its public twin so the twin always tracks the
     *  single source of truth (the provisioned airbox-geomap.json). */
    public String getDashboard(String uid) {
        try {
            String body = restClient.get()
                    .uri("/api/dashboards/uid/{uid}", uid)
                    .retrieve().body(String.class);
            JsonNode dashboard = MAPPER.readTree(body).path("dashboard");
            if (dashboard.isMissingNode() || dashboard.isNull()) {
                throw new GrafanaApiException("Grafana get dashboard '" + uid + "' returned no 'dashboard' node");
            }
            return MAPPER.writeValueAsString(dashboard);
        } catch (RestClientException | JacksonException e) {
            throw new GrafanaApiException("Grafana get dashboard '" + uid + "' failed", e);
        }
    }

    public boolean folderExists(String uid) {
        try {
            restClient.get().uri("/api/folders/{uid}", uid).retrieve().toBodilessEntity();
            return true;
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return false;
            throw new GrafanaApiException("Grafana get folder '" + uid + "' failed", e);
        } catch (RestClientException e) {
            throw new GrafanaApiException("Grafana get folder '" + uid + "' failed", e);
        }
    }

    // Create folder for each specific dashboard
    public void createFolder(String uid, String title) {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("uid", uid);
        body.put("title", title);
        postJson("/api/folders", MAPPER.writeValueAsString(body), "create folder '" + uid + "'");
    }

    // Post completed dashboard
    public void upsertDashboard(String dashboardJson, String folderUid, String message) {
        try {
            ObjectNode body = MAPPER.createObjectNode();
            body.set("dashboard", MAPPER.readTree(dashboardJson));
            body.put("folderUid", folderUid);
            body.put("overwrite", true);
            body.put("message", message);
            postJson("/api/dashboards/db", MAPPER.writeValueAsString(body),
                    "upsert dashboard into folder '" + folderUid + "'");
        } catch (JacksonException e) {
            throw new GrafanaApiException("Rendered dashboard JSON invalid", e);
        }
    }

    public Optional<PublicDashboard> getPublicDashboard(String dashboardUid) {
        try {
            String body = restClient.get()
                    .uri("/api/dashboards/uid/{uid}/public-dashboards", dashboardUid)
                    .retrieve().body(String.class);
            return Optional.of(parsePublicDashboard(body));
        } catch (RestClientResponseException e) {
            if (e.getStatusCode().value() == HttpStatus.NOT_FOUND.value()) return Optional.empty();
            throw new GrafanaApiException("Grafana get public dashboard for '" + dashboardUid + "' failed", e);
        } catch (RestClientException | JacksonException e) {
            throw new GrafanaApiException("Grafana get public dashboard for '" + dashboardUid + "' failed", e);
        }
    }

    /** POST always carries the derived (HMAC) token; a 400 is a hard invariant violation, never a
     *  silent random-token retry. Any failure propagates as GrafanaApiException so the sync fails
     *  loudly and self-retries next run instead of persisting a non-derived token. The echo
     *  assertion guards a future Grafana that 2xx's but substitutes its own token. */
    public PublicDashboard createPublicDashboard(String dashboardUid, String accessToken) {
        ObjectNode body = settingsBody();
        body.put("accessToken", accessToken);
        String responseJson = postJson(publicPath(dashboardUid),
                MAPPER.writeValueAsString(body),
                "create public dashboard for '" + dashboardUid + "'");
        PublicDashboard share;
        try {
            share = parsePublicDashboard(responseJson);
        } catch (JacksonException e) {
            throw new GrafanaApiException(
                    "Malformed public-dashboard response for '" + dashboardUid + "'", e);
        }
        if (!accessToken.equals(share.accessToken()) || !share.isEnabled()) {
            throw new GrafanaApiException(
                    "Grafana did not honor the supplied public token for '" + dashboardUid
                            + "' (sent=" + accessToken + " got=" + share.accessToken()
                            + " enabled=" + share.isEnabled() + ")");
        }
        return share;
    }

    /** publicDashboardUid comes from getPublicDashboard().uid() — NOT the access token. */
    public PublicDashboard enablePublicDashboard(String dashboardUid, String publicDashboardUid) {
        try {
            String response = restClient.patch()
                    .uri(publicPath(dashboardUid) + "/{puid}", publicDashboardUid)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(MAPPER.writeValueAsString(settingsBody()))
                    .retrieve().body(String.class);
            return parsePublicDashboard(response);
        } catch (RestClientException | JacksonException e) {
            throw new GrafanaApiException("Grafana enable public dashboard for '" + dashboardUid + "' failed", e);
        }
    }

    private static String publicPath(String dashboardUid) {
        return "/api/dashboards/uid/" + dashboardUid + "/public-dashboards";
    }

    private static ObjectNode settingsBody() {
        ObjectNode body = MAPPER.createObjectNode();
        body.put("isEnabled", true);
        body.put("share", "public");
        body.put("timeSelectionEnabled", true);
        return body;
    }

    private PublicDashboard parsePublicDashboard(String json) {
        JsonNode node = MAPPER.readTree(json);
        return new PublicDashboard(
                node.path("uid").stringValue(""),
                node.path("accessToken").stringValue(""),
                node.path("isEnabled").asBoolean(false));
    }

    private String postJson(String path, String json, String what) {
        try {
            return restClient.post()
                    .uri(path)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(json)
                    .retrieve().body(String.class);
        } catch (RestClientException e) {
            throw new GrafanaApiException("Grafana " + what + " failed", e);
        }
    }
}
