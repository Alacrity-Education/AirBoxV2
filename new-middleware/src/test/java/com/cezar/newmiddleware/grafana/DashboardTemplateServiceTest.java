package com.cezar.newmiddleware.grafana;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Plain JUnit — templates are loaded from the classpath (src/main/resources is
 * on the test classpath), no Spring context needed.
 */
@DisplayName("DashboardTemplateService")
class DashboardTemplateServiceTest {

    private static final String DEVICE = "airbox-mock-001";
    private static final JsonMapper MAPPER = JsonMapper.builder().build();

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
        @DisplayName("all three templates and the nav fragment load with a 16-hex hash")
        void templatesLoad() throws IOException {
            for (String name : new String[]{
                    DashboardTemplateService.TEMPLATE_STATION_V1,
                    DashboardTemplateService.TEMPLATE_STATION_V2,
                    DashboardTemplateService.TEMPLATE_OVERVIEW,
                    DashboardTemplateService.NAV_FRAGMENT}) {
                DashboardTemplateService.LoadedTemplate t = service.load(name);
                assertThat(t.json()).as("%s content", name).isNotBlank();
                assertThat(t.hash()).as("%s hash", name).matches("^[0-9a-f]{16}$");
            }
        }
    }

    @Nested
    @DisplayName("render() — station views")
    class StationRender {

        @Test
        @DisplayName("station v2: all 23 placeholders replaced with the quoted device literal")
        void stationV2Rendered() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_STATION_V2);
            String rendered = service.render(t, nav, "st2-" + DEVICE, "AirBox – " + DEVICE + " – detalii", DEVICE);

            assertThat(rendered).doesNotContain("${device");
            assertThat(count(rendered, "'" + DEVICE + "'")).isEqualTo(23);
            assertThat(rendered).doesNotContain("owner_email");
        }

        @Test
        @DisplayName("station v1: all 15 placeholders replaced")
        void stationV1Rendered() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_STATION_V1);
            String rendered = service.render(t, nav, "st1-" + DEVICE, "AirBox – " + DEVICE, DEVICE);

            assertThat(rendered).doesNotContain("${device");
            assertThat(count(rendered, "'" + DEVICE + "'")).isEqualTo(15);
        }

        @Test
        @DisplayName("identity fields, tags, templating and links are rewritten")
        void identityFields() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_STATION_V2);
            JsonNode root = MAPPER.readTree(
                    service.render(t, nav, "st2-" + DEVICE, "AirBox – " + DEVICE + " – detalii", DEVICE));

            assertThat(root.get("id").isNull()).isTrue();
            assertThat(root.get("uid").stringValue("")).isEqualTo("st2-" + DEVICE);
            assertThat(root.get("title").stringValue("")).isEqualTo("AirBox – " + DEVICE + " – detalii");
            assertThat(root.get("version").asInt(-1)).isZero();

            assertThat(root.get("tags").size()).isEqualTo(3);
            assertThat(root.get("tags").get(0).stringValue("")).isEqualTo("airbox-generated");
            assertThat(root.get("tags").get(1).stringValue("")).isEqualTo("tpl-" + t.hash());
            assertThat(root.get("tags").get(2).stringValue("")).isEqualTo("nav-" + nav.hash());

            assertThat(root.path("templating").path("list").size())
                    .as("device variable removed (it was the only one)").isZero();
            assertThat(root.get("links").size()).isZero();
        }
    }

    @Nested
    @DisplayName("render() — global overview twin")
    class OverviewRender {

        @Test
        @DisplayName("renders with null device: all-devices filter, no placeholders, no owner_email")
        void overviewRendered() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_OVERVIEW);
            String rendered = service.render(t, nav, "airbox-public-overview", "AirBox Overview (public)", null);

            assertThat(rendered).doesNotContain("${device");
            assertThat(rendered).doesNotContain("owner_email");
            assertThat(rendered).contains("IN (SELECT DISTINCT device FROM airbox_readings)");

            JsonNode root = MAPPER.readTree(rendered);
            assertThat(root.get("uid").stringValue("")).isEqualTo("airbox-public-overview");
            assertThat(root.path("templating").path("list").size()).isZero();
        }
    }

    @Nested
    @DisplayName("render() — nav panel injection")
    class NavInjection {

        @Test
        @DisplayName("nav panels prepended, originals shifted down by the fragment height")
        void navPanelsInjected() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_STATION_V1);
            JsonNode panels = MAPPER.readTree(
                            service.render(t, nav, "st1-" + DEVICE, "AirBox – " + DEVICE, DEVICE))
                    .get("panels");

            assertThat(panels.size()).as("16 original + 2 nav").isEqualTo(18);
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
        void overviewNavInjected() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_OVERVIEW);
            String rendered = service.render(t, nav, "airbox-public-overview", "AirBox Overview (public)", null);

            JsonNode panels = MAPPER.readTree(rendered).get("panels");
            assertThat(panels.size()).as("12 original + 2 nav").isEqualTo(14);
            assertThat(rendered).contains("${__data.fields.url_v1}");
            assertThat(rendered).contains("'/public-dashboards/' ||");
        }

        @Test
        @DisplayName("combinedHash is tplHash-navHash")
        void combinedHashFormat() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_OVERVIEW);
            String combined = DashboardTemplateService.combinedHash(t, nav);
            assertThat(combined).isEqualTo(t.hash() + "-" + nav.hash());
            assertThat(combined).matches("^[0-9a-f]{16}-[0-9a-f]{16}$");
        }
    }

    @Nested
    @DisplayName("device_id allowlist")
    class Allowlist {

        @Test
        @DisplayName("ids with quotes, semicolons, dots, over-length or blank are rejected")
        void invalidIdsRejected() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_STATION_V2);
            for (String bad : new String[]{"bad'id", "bad;id", "bad.id", "a".repeat(37), ""}) {
                assertThatThrownBy(() -> service.render(t, nav, "st2-x", "x", bad))
                        .as("device_id '%s'", bad)
                        .isInstanceOf(IllegalArgumentException.class);
            }
        }

        @Test
        @DisplayName("36-char id (the maximum) is accepted")
        void maxLengthAccepted() throws IOException {
            DashboardTemplateService.LoadedTemplate t = service.load(DashboardTemplateService.TEMPLATE_STATION_V2);
            String max = "a".repeat(36);
            String rendered = service.render(t, nav, "st2-" + max, "AirBox – " + max, max);
            assertThat(MAPPER.readTree(rendered).get("uid").stringValue("")).hasSize(40);
        }
    }

    @Test
    @DisplayName("sqlLiteral quotes and doubles embedded quotes")
    void sqlLiteral() {
        assertThat(DashboardTemplateService.sqlLiteral("a'b")).isEqualTo("'a''b'");
        assertThat(DashboardTemplateService.sqlLiteral(DEVICE)).isEqualTo("'" + DEVICE + "'");
    }
}
