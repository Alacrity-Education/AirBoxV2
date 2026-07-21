package com.cezar.newmiddleware.grafana;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Unit tests for the live-source transformation pipeline. The overview/station twins are no
 * longer rendered from jar-baked templates; transformTwin takes the LIVE source dashboard JSON
 * (as fetched from Grafana) and produces the public twin. Synthetic source fixtures exercise
 * each transformation rule; a final pair of tests replays the REAL provisioned sources under
 * grafana/dashboards/ (resolved relative to the module dir — the canonical container test run)
 * to guard the rules against the actual dashboards, skipping if the files are not reachable.
 */
@DisplayName("DashboardTemplateService")
class DashboardTemplateServiceTest {

    private static final String DEVICE = "airbox-mock-001";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    // A synthetic per-device STATION source: a device template variable, links, and two rawSql
    // targets carrying ${device:sqlstring} — one of which exposes owner_email (labelled column).
    private static final String STATION_SOURCE = """
            {
              "id": 42,
              "uid": "airbox-station",
              "version": 7,
              "title": "AirBox Station \\u2013 overview",
              "tags": ["airbox"],
              "templating": { "list": [ { "name": "device", "type": "query", "multi": true } ] },
              "links": [ { "type": "link", "title": "Map", "url": "/d/9f08" } ],
              "time": { "from": "now-6h", "to": "now" },
              "refresh": "30s",
              "panels": [
                { "id": 1, "type": "stat", "gridPos": { "h": 4, "w": 6, "x": 0, "y": 0 },
                  "targets": [ { "refId": "A", "rawSql":
                    "SELECT avg(pm25) FROM airbox_readings WHERE device IN (${device:sqlstring})" } ] },
                { "id": 2, "type": "table", "gridPos": { "h": 8, "w": 24, "x": 0, "y": 4 },
                  "targets": [ { "refId": "A", "rawSql":
                    "SELECT i.device_id AS \\"Station ID\\", i.installation AS \\"Installation\\", i.owner_email AS \\"Owner\\", r.geohash AS \\"Loc\\" FROM airbox_installations i WHERE i.device_id = ${device:sqlstring}" } ] }
              ]
            }""";

    // A synthetic global OVERVIEW source: device variable, and rawSql filtering with
    // device IN (${device:sqlstring}) plus a bare i.owner_email column (as the real overview has).
    private static final String OVERVIEW_SOURCE = """
            {
              "id": 7,
              "uid": "airbox-overview",
              "version": 3,
              "title": "AirBox Overview",
              "tags": ["airbox"],
              "templating": { "list": [ { "name": "device", "type": "query", "includeAll": true } ] },
              "links": [ { "type": "link", "title": "Map", "url": "/d/9f08" } ],
              "time": { "from": "now-1h", "to": "now" },
              "refresh": "30s",
              "panels": [
                { "id": 1, "type": "stat", "gridPos": { "h": 4, "w": 6, "x": 0, "y": 0 },
                  "targets": [ { "refId": "A", "rawSql":
                    "SELECT avg(pm25) AS \\"pm2.5\\" FROM airbox_readings WHERE device IN (${device:sqlstring})" } ] },
                { "id": 2, "type": "table", "gridPos": { "h": 8, "w": 24, "x": 0, "y": 4 },
                  "targets": [ { "refId": "A", "rawSql":
                    "SELECT DISTINCT ON (r.device) r.device, r.installation, i.owner_email, r.geohash FROM airbox_readings r LEFT JOIN airbox_installations i ON i.device_id = r.device WHERE r.device IN (${device:sqlstring}) ORDER BY r.device" } ] }
              ]
            }""";

    private DashboardTemplateService service;
    private DashboardTemplateService.LoadedTemplate nav;

    @BeforeEach
    void setUp() throws IOException {
        service = new DashboardTemplateService(null);
        nav = service.load(DashboardTemplateService.NAV_FRAGMENT);
    }

    private static int count(String haystack, String needle) {
        return haystack.split(Pattern.quote(needle), -1).length - 1;
    }

    @Nested
    @DisplayName("load()")
    class Load {

        @Test
        @DisplayName("nav fragment loads with a 16-hex hash")
        void navLoads() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.NAV_FRAGMENT);
            assertThat(t.json()).isNotBlank();
            assertThat(t.hash()).matches("^[0-9a-f]{16}$");
        }
    }

    @Nested
    @DisplayName("transformTwin() — station views")
    class StationTransform {

        @Test
        @DisplayName("all ${device} placeholders become the quoted device literal; owner_email stripped")
        void stationRendered() {
            String rendered = service.transformTwin(
                    STATION_SOURCE, nav, "abx-details-" + DEVICE, "AirBox – " + DEVICE + " – details", DEVICE).json();

            assertThat(rendered).doesNotContain("${device");
            assertThat(count(rendered, "'" + DEVICE + "'")).isEqualTo(2);   // two rawSql placeholders
            assertThat(rendered).doesNotContain("owner_email");
        }

        @Test
        @DisplayName("identity fields, single generated tag, device variable and links removed")
        void identityFields() {
            JsonNode root = MAPPER.readTree(service.transformTwin(
                    STATION_SOURCE, nav, "abx-details-" + DEVICE, "AirBox – " + DEVICE + " – details", DEVICE).json());

            assertThat(root.get("id").isNull()).isTrue();
            assertThat(root.get("uid").stringValue("")).isEqualTo("abx-details-" + DEVICE);
            assertThat(root.get("title").stringValue("")).isEqualTo("AirBox – " + DEVICE + " – details");
            assertThat(root.get("version").asInt(-1)).isZero();

            assertThat(root.get("tags").size()).as("single membership tag, geomap-style").isEqualTo(1);
            assertThat(root.get("tags").get(0).stringValue("")).isEqualTo("airbox-generated");

            assertThat(root.path("templating").path("list").size())
                    .as("device variable removed (it was the only one)").isZero();
            assertThat(root.get("links").size()).isZero();
        }

        @Test
        @DisplayName("the transformed hash changes when the source changes (refresh-on-change)")
        void hashTracksSource() {
            String a = service.transformTwin(STATION_SOURCE, nav,
                    "abx-details-" + DEVICE, "t", DEVICE).hash();
            String changed = STATION_SOURCE.replace("avg(pm25)", "avg(pm10)");
            String b = service.transformTwin(changed, nav, "abx-details-" + DEVICE, "t", DEVICE).hash();
            assertThat(a).matches("^[0-9a-f]{16}$").isNotEqualTo(b);
        }
    }

    @Nested
    @DisplayName("transformTwin() — global overview twin")
    class OverviewTransform {

        @Test
        @DisplayName("null device: all-devices filter substituted, no placeholders, no owner_email")
        void overviewRendered() {
            String rendered = service.transformTwin(
                    OVERVIEW_SOURCE, nav, "airbox-public-overview", "AirBox Overview (public)", null).json();

            assertThat(rendered).doesNotContain("${device");
            assertThat(rendered).doesNotContain("owner_email");
            assertThat(rendered).contains("IN (SELECT DISTINCT device FROM airbox_readings)");

            JsonNode root = MAPPER.readTree(rendered);
            assertThat(root.get("uid").stringValue("")).isEqualTo("airbox-public-overview");
            assertThat(root.path("templating").path("list").size()).isZero();
        }
    }

    @Nested
    @DisplayName("transformTwin() — nav panel injection")
    class NavInjection {

        @Test
        @DisplayName("nav panels prepended, originals shifted down by the fragment height")
        void navPanelsInjected() {
            JsonNode panels = MAPPER.readTree(service.transformTwin(
                            STATION_SOURCE, nav, "abx-overview-" + DEVICE, "t", DEVICE).json())
                    .get("panels");

            assertThat(panels.size()).as("2 original + 2 nav").isEqualTo(4);
            assertThat(panels.get(0).get("id").asInt(-1)).isEqualTo(9001);
            assertThat(panels.get(1).get("id").asInt(-1)).isEqualTo(9002);
            assertThat(panels.get(0).path("gridPos").path("y").asInt(-1)).isZero();
            assertThat(panels.get(1).path("gridPos").path("y").asInt(-1)).isZero();

            int minShiftedY = Integer.MAX_VALUE;
            for (int i = 2; i < panels.size(); i++) {
                minShiftedY = Math.min(minShiftedY, panels.get(i).path("gridPos").path("y").asInt(-1));
            }
            assertThat(minShiftedY).as("originals start right below the nav strip").isEqualTo(5);
        }

        @Test
        @DisplayName("overview gets nav too, with relative public links in the SQL")
        void overviewNavInjected() {
            String rendered = service.transformTwin(
                    OVERVIEW_SOURCE, nav, "airbox-public-overview", "AirBox Overview (public)", null).json();

            JsonNode panels = MAPPER.readTree(rendered).get("panels");
            assertThat(panels.size()).as("2 original + 2 nav").isEqualTo(4);
            assertThat(rendered).contains("${__data.fields.url_v1}");
            assertThat(rendered).contains("'/public-dashboards/' || v1.slug");
        }
    }

    @Nested
    @DisplayName("device_id allowlist")
    class Allowlist {

        @Test
        @DisplayName("ids with quotes, semicolons, dots, over-length or blank are rejected")
        void invalidIdsRejected() {
            for (String bad : new String[]{"bad'id", "bad;id", "bad.id", "a".repeat(28), ""}) {
                assertThatThrownBy(() -> service.transformTwin(STATION_SOURCE, nav, "abx-details-x", "x", bad))
                        .as("device_id '%s'", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("27-char id (the maximum) yields a 40-char uid, Grafana's cap")
        void maxLengthAccepted() {
            String max = "a".repeat(27);
            // "abx-overview-" (13) + 27 = 40, exactly Grafana's uid cap.
            String rendered = service.transformTwin(
                    STATION_SOURCE, nav, "abx-overview-" + max, "AirBox – " + max + " – overview", max).json();
            assertThat(MAPPER.readTree(rendered).get("uid").stringValue("")).hasSize(40);
        }
    }

    @Nested
    @DisplayName("real provisioned sources (grafana/dashboards/*.json)")
    class RealSources {

        private String read(String name) {
            Path p = Path.of("grafana/dashboards", name);
            Assumptions.assumeTrue(Files.isReadable(p), "source not reachable from working dir: " + p);
            try {
                // Feed the raw file (the "dashboard" node the sync fetches is this same model).
                return Files.readString(p, StandardCharsets.UTF_8);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        }

        @Test
        @DisplayName("airbox-station -> abx-overview twin: device baked, no vars, no PII, nav present")
        void stationOverviewSource() {
            String rendered = service.transformTwin(read("airbox-station.json"), nav,
                    "abx-overview-" + DEVICE, "AirBox – " + DEVICE + " – overview", DEVICE).json();
            JsonNode root = MAPPER.readTree(rendered);
            assertThat(rendered).doesNotContain("${device").doesNotContain("owner_email");
            assertThat(count(rendered, "'" + DEVICE + "'")).isEqualTo(15);   // 15 placeholders in the source
            assertThat(root.path("templating").path("list").size()).isZero();
            assertThat(root.get("tags").get(0).stringValue("")).isEqualTo("airbox-generated");
            assertThat(root.get("panels").get(0).get("id").asInt(-1)).isEqualTo(9001);
        }

        @Test
        @DisplayName("airbox-station-v2 -> abx-details twin: 23 placeholders baked, owner_email stripped")
        void stationDetailsSource() {
            String rendered = service.transformTwin(read("airbox-station-v2.json"), nav,
                    "abx-details-" + DEVICE, "AirBox – " + DEVICE + " – details", DEVICE).json();
            assertThat(rendered).doesNotContain("${device").doesNotContain("owner_email");
            assertThat(count(rendered, "'" + DEVICE + "'")).isEqualTo(23);
        }

        @Test
        @DisplayName("airbox-overview -> overview twin: all-devices filter, no PII, nav SQL present")
        void overviewSource() {
            String rendered = service.transformTwin(read("airbox-overview.json"), nav,
                    "airbox-public-overview", "AirBox Overview (public)", null).json();
            assertThat(rendered).doesNotContain("${device").doesNotContain("owner_email");
            assertThat(rendered).contains("IN (SELECT DISTINCT device FROM airbox_readings)");
            assertThat(rendered).contains("'/public-dashboards/' || v1.slug");
        }
    }

    @Test
    @DisplayName("sqlLiteral quotes and doubles embedded quotes")
    void sqlLiteral() {
        assertThat(DashboardTemplateService.sqlLiteral("a'b")).isEqualTo("'a''b'");
        assertThat(DashboardTemplateService.sqlLiteral(DEVICE)).isEqualTo("'" + DEVICE + "'");
    }
}
