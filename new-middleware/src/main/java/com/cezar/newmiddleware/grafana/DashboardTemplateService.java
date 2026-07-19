package com.cezar.newmiddleware.grafana;

import com.cezar.newmiddleware.tools.CheckSum;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

public class DashboardTemplateService {

    // 36 max: the longest uid prefix ("st1-"/"st2-", 4 chars) + device_id must fit Grafana's 40-char uid cap.
    public static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,36}$");
    static final String GENERATED_TAG = "airbox-generated";

    public static final String TEMPLATE_STATION_V1 = "station_view1.json";
    public static final String TEMPLATE_STATION_V2 = "station_view2.json";
    public static final String TEMPLATE_OVERVIEW = "overview.json";
    public static final String NAV_FRAGMENT = "nav_panels.json";

    private static final String CLASSPATH_DIR = "grafana-templates/";
    private static final String DEVICE_PLACEHOLDER = "${device:sqlstring}";
    private static final String TEMPLATE_VARIABLE_NAME = "device";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String templatesDir;   // blank/null => classpath

    public DashboardTemplateService(String templatesDir) { this.templatesDir = templatesDir; }

    public record LoadedTemplate(String json, String hash) {}

    /** Re-reads the template every call (per-run reload is the contract). */
    public LoadedTemplate load(String templateName) throws IOException {
        String json;
        if (templatesDir != null && !templatesDir.isBlank()) {
            json = Files.readString(Path.of(templatesDir, templateName), StandardCharsets.UTF_8);
        } else {
            try (InputStream in = new ClassPathResource(CLASSPATH_DIR + templateName).getInputStream()) {
                json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
        }
        return new LoadedTemplate(json, CheckSum.generateSHA256(json).substring(0, 16));
    }

    /** template_hash stored per dashboard: a fragment edit must invalidate too. */
    public static String combinedHash(LoadedTemplate template, LoadedTemplate navFragment) {
        return template.hash() + "-" + navFragment.hash();
    }

     // Renders a shared copy of the template. deviceId is null for global views
     // (overview) — no SQL substitution happens, the template must already be
     // device-free. For station views the device is baked into every rawSql.
     // navFragment panels are injected at the top; existing panels shift down.
    public String render(LoadedTemplate template, LoadedTemplate navFragment,
                         String uid, String title, String deviceId) {
        if (deviceId != null && !DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
            throw new IllegalArgumentException(
                    "device_id rejected by allowlist ^[A-Za-z0-9_-]{1,36}$: '" + deviceId + "'");
        }

        // Treat the entire template as an object then replace the fields
        ObjectNode root = (ObjectNode) MAPPER.readTree(template.json());
        root.putNull("id");                                   // must be null on create
        root.put("uid", uid);                                 // distinct from folder uid namespace
        root.put("title", title);
        root.put("version", 0);

        // The "tags" are treated as an array
        ArrayNode tags = root.putArray("tags");
        tags.add(GENERATED_TAG);
        tags.add("tpl-" + template.hash());
        tags.add("nav-" + navFragment.hash());

        // The dropdown
        if (root.path("templating").path("list") instanceof ArrayNode arr) {
            arr.removeIf(v -> TEMPLATE_VARIABLE_NAME.equals(v.path("name").stringValue("")));
        }
        root.putArray("links");                               // links carry var-device params; drop all

        injectNavPanels(root, navFragment, uid);

        String rendered = MAPPER.writeValueAsString(root);
        if (deviceId != null) {
            rendered = rendered.replace(DEVICE_PLACEHOLDER, sqlLiteral(deviceId));
        }
        if (rendered.contains("${device")) {                  // global template not device-free, or a missed form
            throw new IllegalStateException(
                    "rendered dashboard '" + uid + "' still contains a ${device...} reference");
        }
        MAPPER.readTree(rendered);                            // validity re-parse (throws JacksonException)
        return rendered;
    }

    // TODO: REWORK THIS INTO SOMETHING BETTER FROM UI/UX PERSPECTIVE
    // Prepends the nav panels and shifts every existing panel down by the
    // fragment's height, so navigation survives Grafana's public rendering
    // (which strips the variables row and the links row, but keeps panels).
    private static void injectNavPanels(ObjectNode root, LoadedTemplate navFragment, String uid) {
        if (!(MAPPER.readTree(navFragment.json()) instanceof ArrayNode navPanels)) {
            throw new IllegalStateException("nav fragment is not a JSON array of panels");
        }
        if (!(root.path("panels") instanceof ArrayNode existingPanels)) {
            throw new IllegalStateException("dashboard '" + uid + "' has no panels array");
        }

        int shift = 0;
        for (var panel : navPanels) {
            shift = Math.max(shift, panel.path("gridPos").path("y").asInt(0)
                    + panel.path("gridPos").path("h").asInt(0));
        }
        for (var panel : existingPanels) {
            if (panel.path("gridPos") instanceof ObjectNode gridPos) {
                gridPos.put("y", gridPos.path("y").asInt(0) + shift);
            }
        }

        ArrayNode combined = MAPPER.createArrayNode();
        combined.addAll(navPanels);
        combined.addAll(existingPanels);
        root.set("panels", combined);
    }

    static String sqlLiteral(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
