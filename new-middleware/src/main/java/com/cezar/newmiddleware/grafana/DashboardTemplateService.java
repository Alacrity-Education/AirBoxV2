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

    // 27 max: the uid prefix ("abx-details-", 12 chars) + device_id must fit Grafana's
    // 40-char uid cap (12 + 27 = 39, within cap), and the generated uid doubles as the
    // public slug (uid == slug). The 27 bound is retained from the earlier 13-char prefix.
    public static final Pattern DEVICE_ID_PATTERN = Pattern.compile("^[A-Za-z0-9_-]{1,27}$");
    static final String GENERATED_TAG = "airbox-generated";

    // The ONLY jar/templates-dir resource left: the twin-specific navigation chrome injected at
    // the top of every generated twin. It is NOT a copy of any source dashboard, so it stays a
    // resource; MDW_GRAFANA_TEMPLATES_DIR now overrides only this fragment.
    public static final String NAV_FRAGMENT = "nav_panels.json";

    private static final String CLASSPATH_DIR = "grafana-templates/";
    private static final String DEVICE_PLACEHOLDER = "${device:sqlstring}";
    private static final String TEMPLATE_VARIABLE_NAME = "device";

    // The global overview twin has no single device: its ${device:sqlstring} resolves to the
    // "all reporting devices" set, reproducing the all-devices filter the old jar overview.json
    // baked in by hand ("device IN (SELECT DISTINCT device FROM airbox_readings)").
    private static final String ALL_DEVICES_SUBQUERY = "SELECT DISTINCT device FROM airbox_readings";

    // owner_email is PII and must never reach a PUBLIC twin. The provisioned source dashboards
    // expose it as a table column; the old hand-baked jar templates had it removed, and these
    // two literal forms (labelled column in station-details, bare column in overview) reproduce
    // that removal on the serialized twin JSON. The escaped quotes match Jackson's output.
    private static final String OWNER_EMAIL_LABELLED = "i.owner_email AS \\\"Owner\\\", ";
    private static final String OWNER_EMAIL_BARE = "i.owner_email, ";

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private final String templatesDir;   // blank/null => classpath

    public DashboardTemplateService(String templatesDir) { this.templatesDir = templatesDir; }

    public record LoadedTemplate(String json, String hash) {}

    /** Re-reads the nav fragment every call (per-run reload is the contract). */
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

    /**
     * Transform a LIVE source dashboard (fetched from Grafana) into a public twin — the same
     * live-source flow the geomap twin uses, but for the variable-driven overview/station
     * dashboards. Produces structurally the twin the old jar templates rendered:
     *   - identity reset: id=null, new uid, new title, version=0, tags=[airbox-generated];
     *   - the "device" template variable is removed (public dashboards carry no variables);
     *   - links (which carried var-device params and /d/ nav) are dropped;
     *   - nav panels are injected at the top (existing panels shift down) ONLY for the global
     *     overview twin (deviceId == null); a per-device station twin gets no nav injection and
     *     opens directly on its own station panels;
     *   - ${device:sqlstring} is resolved: to the quoted device literal for a station twin
     *     (deviceId != null), or to the all-devices subquery for the global overview (deviceId
     *     == null) — reproducing the old overview's baked-in all-devices filter;
     *   - owner_email PII is stripped from every rawSql.
     * The returned hash is SHA-256 of the fully transformed JSON (like the map twin): it gates
     * refresh-on-change and folds in source, nav-fragment and substitution changes at once.
     */
    public LoadedTemplate transformTwin(String sourceJson, LoadedTemplate navFragment,
                                        String uid, String title, String deviceId) {
        if (deviceId != null && !DEVICE_ID_PATTERN.matcher(deviceId).matches()) {
            throw new IllegalArgumentException(
                    "device_id rejected by allowlist ^[A-Za-z0-9_-]{1,27}$: '" + deviceId + "'");
        }

        ObjectNode root = (ObjectNode) MAPPER.readTree(sourceJson);
        root.putNull("id");                                   // must be null on create
        root.put("uid", uid);                                 // twin identity, distinct from source
        root.put("title", title);
        root.put("version", 0);

        ArrayNode tags = root.putArray("tags");
        tags.add(GENERATED_TAG);                              // membership marker for needsSync search

        // The device dropdown: public dashboards cannot resolve template variables, so remove it.
        if (root.path("templating").path("list") instanceof ArrayNode arr) {
            arr.removeIf(v -> TEMPLATE_VARIABLE_NAME.equals(v.path("name").stringValue("")));
        }
        root.putArray("links");                               // links carry var-device params; drop all

        // Nav chrome is a GLOBAL affordance: it belongs on the overview twin (deviceId == null),
        // whose job is to route to the map and to the individual stations. A per-device station
        // twin (deviceId != null) is a leaf — it opens directly on its own station panels with no
        // injected navigation strip, so the injection is skipped for it. The transform hash is
        // still the hash of the fully transformed JSON, so a station twin's hash naturally changes
        // when the nav strip disappears, triggering the re-upsert.
        if (deviceId == null) {
            injectNavPanels(root, navFragment, uid);
        }

        String rendered = MAPPER.writeValueAsString(root);
        rendered = rendered.replace(DEVICE_PLACEHOLDER,
                deviceId != null ? sqlLiteral(deviceId) : ALL_DEVICES_SUBQUERY);
        rendered = rendered.replace(OWNER_EMAIL_LABELLED, "").replace(OWNER_EMAIL_BARE, "");

        if (rendered.contains("${device")) {                  // any missed variable reference
            throw new IllegalStateException(
                    "rendered dashboard '" + uid + "' still contains a ${device...} reference");
        }
        if (rendered.contains("owner_email")) {               // PII must never reach a public twin
            throw new IllegalStateException(
                    "rendered dashboard '" + uid + "' still contains an owner_email reference");
        }
        MAPPER.readTree(rendered);                            // validity re-parse (throws JacksonException)
        return new LoadedTemplate(rendered, CheckSum.generateSHA256(rendered).substring(0, 16));
    }

    /** Clone the LIVE provisioned map dashboard into a public twin: identical content, new
     *  identity. Unlike {@link #transformTwin}, there is no nav injection or device substitution —
     *  the geomap already carries its own navigation and is device-free. id is nulled and
     *  version reset so Grafana's provisioning bookkeeping does not perturb the hash; the twin
     *  is tagged GENERATED_TAG so it shows up in the tag search (needsSync membership) and the
     *  source title is preserved. The returned hash is stored as the artifact's template_hash
     *  and drives refresh-on-change. */
    public LoadedTemplate transformMapTwin(String sourceJson, String uid) {
        ObjectNode root = (ObjectNode) MAPPER.readTree(sourceJson);
        root.putNull("id");                                   // must be null on create
        root.put("uid", uid);                                 // twin identity, distinct from source
        root.put("version", 0);
        ArrayNode tags = root.putArray("tags");
        tags.add(GENERATED_TAG);

        String rendered = MAPPER.writeValueAsString(root);
        MAPPER.readTree(rendered);                            // validity re-parse (throws JacksonException)
        return new LoadedTemplate(rendered, CheckSum.generateSHA256(rendered).substring(0, 16));
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
