package ro.alacrity.airbox.middleware.controller;

import ro.alacrity.airbox.middleware.dto.GrafanaArtifactDTO;
import ro.alacrity.airbox.middleware.repository.GrafanaArtifactRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Human-readable public-dashboard redirector.
 *
 * GET /public-dashboards/{slug} -> 302 to
 *   &lt;mdw.grafana.public-url&gt;/public-dashboards/&lt;access_token&gt;
 * for a known slug; 404 for an unknown or malformed slug.
 *
 * The slug -> access_token mapping lives in airbox_grafana_artifacts, populated by
 * DashboardSyncJob. This controller depends only on the (always-present) repository
 * and the public-url property, NOT on the sync-gated GrafanaSyncConfig beans — so it
 * is registered whether or not mdw.grafana.sync.enabled is true. When sync is disabled
 * the artifacts table is never populated, so every slug resolves to 404 (no 503).
 */
@RestController
public class PublicDashboardController {

    /** Mirrors Grafana's own slug/token charset and caps at the slug column width. */
    private static final Pattern SLUG_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,150}$");

    private final GrafanaArtifactRepository artifactRepository;
    private final String publicUrl;

    public PublicDashboardController(GrafanaArtifactRepository artifactRepository,
                                     @Value("${mdw.grafana.public-url:}") String publicUrl) {
        this.artifactRepository = artifactRepository;
        this.publicUrl = publicUrl == null ? "" : stripTrailingSlash(publicUrl);
    }

    @GetMapping("/public-dashboards/{slug}")
    public ResponseEntity<Void> redirect(@PathVariable String slug) {
        Optional<String> token = resolveToken(slug);
        if (token.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        URI target = URI.create(publicUrl + "/public-dashboards/" + token.get());
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, target.toString())
                .build();
    }

    /**
     * Token-resolution endpoint for the Caddy reverse-proxy (forward_auth). Unlike the
     * {@link #redirect} endpoint this returns NO redirect and NO body: on a known slug it
     * answers 200 and carries the raw Grafana access token in the {@code X-Abx-Token}
     * response header (Caddy copies that header onto the original request, then rewrites
     * the pretty slug path to the real /g/&lt;kind&gt;/&lt;token&gt; path and proxies it to
     * Grafana — so the browser stays on the pretty URL). Unknown/malformed slugs answer 404,
     * which Caddy relays to the client verbatim.
     */
    @GetMapping("/internal/slug-token/{slug}")
    public ResponseEntity<Void> slugToken(@PathVariable String slug) {
        Optional<String> token = resolveToken(slug);
        return token
                .map(t -> ResponseEntity.ok().header("X-Abx-Token", t).<Void>build())
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /** Shared slug -> access-token lookup used by both the redirect and the proxy endpoints. */
    private Optional<String> resolveToken(String slug) {
        if (slug == null || !SLUG_PATTERN.matcher(slug).matches()) {
            return Optional.empty();
        }
        return artifactRepository.findBySlug(slug)
                .map(GrafanaArtifactDTO::accessToken)
                .filter(t -> t != null && !t.isBlank());
    }

    private static String stripTrailingSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
