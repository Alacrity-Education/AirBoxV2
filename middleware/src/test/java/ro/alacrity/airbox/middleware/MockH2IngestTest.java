package ro.alacrity.airbox.middleware;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;

import java.time.OffsetDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end exercise of the ingest pipeline against a mock in-memory H2 database.
 *
 * The schema is created by the H2-compatible Flyway migrations under
 * src/test/resources/db/migration. Installations (and therefore API keys) are
 * seeded directly via JdbcTemplate; readings are pushed through the real HTTP
 * endpoint so deserialization, validation, API-key resolution and the JDBC
 * insert are all covered.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Mock H2 ingest pipeline")
class MockH2IngestTest {

    private static final String SUBMIT = "/api/v2/submit";

    // Seeded installations.
    private static final String API_KEY      = "test-api-key-0001";
    private static final String DEVICE_ID    = "device-aaa-001";
    private static final String INSTALLATION = "balcony-north";

    private static final String API_KEY_2    = "test-api-key-0002";
    private static final String DEVICE_ID_2  = "device-bbb-002";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;

    @BeforeEach
    void seed() {
        // Clean slate every test — in-memory DB is shared across the JVM.
        jdbc.update("DELETE FROM airbox_readings");
        jdbc.update("DELETE FROM airbox_gas_algorithm");
        jdbc.update("DELETE FROM airbox_installations");

        insertInstallation(DEVICE_ID, API_KEY, "owner@example.com",
                "co1@example.com", "co2@example.com", INSTALLATION, "primary unit");

        // Second device with the bare minimum: co-owners and notes are NULL.
        insertInstallation(DEVICE_ID_2, API_KEY_2, "owner2@example.com",
                null, null, "garden", null);
    }

    private void insertInstallation(String deviceId, String apiKey, String owner,
                                    String co1, String co2, String installation, String notes) {
        jdbc.update("""
                INSERT INTO airbox_installations
                    (device_id, apikey, owner_email, co_owner1_email,
                     co_owner2_email, installation, notes)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, deviceId, apiKey, owner, co1, co2, installation, notes);
    }

    private void insertInstallationWithOverride(String deviceId, String apiKey, String owner,
                                                String installation, String geohashOverride) {
        jdbc.update("""
                INSERT INTO airbox_installations
                    (device_id, apikey, owner_email, installation, geohash_override)
                VALUES (?, ?, ?, ?, ?)
                """, deviceId, apiKey, owner, installation, geohashOverride);
    }

    private long readingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM airbox_readings", Long.class);
    }

    private String storedGeohash(String device) {
        return jdbc.queryForObject(
                "SELECT geohash FROM airbox_readings WHERE device = ?", String.class, device);
    }

    // ---------------------------------------------------------------------
    // Happy path — full payload
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("fully-populated reading is persisted with every column set")
    void fullReadingPersisted() throws Exception {
        String body = """
                {
                  "geohash": "u4pruydqqvj",
                  "charge": 87.5,
                  "sun": true,
                  "co2": 612.0,
                  "pm1": 3.1,
                  "pm25": 5.4,
                  "pm4": 6.0,
                  "pm10": 7.2,
                  "temp": 21.7,
                  "hum": 48.3,
                  "voc_index": 102.0,
                  "nox_index": 1.0,
                  "voc": 25000.0,
                  "nox": 16000.0
                }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM airbox_readings WHERE geohash = ?", "u4pruydqqvj");

        // Device + installation come from the seeded installation, not the payload.
        assertThat(row.get("device")).isEqualTo(DEVICE_ID);
        assertThat(row.get("installation")).isEqualTo(INSTALLATION);
        assertThat(row.get("time")).isNotNull();              // server-assigned default/insert
        assertThat(row.get("sun")).isEqualTo(Boolean.TRUE);
        // DOUBLE PRECISION columns round-trip the Java Doubles exactly.
        assertThat(((Number) row.get("charge")).doubleValue()).isEqualTo(87.5);
        assertThat(((Number) row.get("co2")).doubleValue()).isEqualTo(612.0);
        assertThat(((Number) row.get("pm25")).doubleValue()).isEqualTo(5.4);
        assertThat(((Number) row.get("nox")).doubleValue()).isEqualTo(16000.0);
    }

    @Test
    @DisplayName("X-ApiKey and bare ApiKey headers resolve the same as Authorization")
    void alternateApiKeyHeaders() throws Exception {
        String body = minimal("u4pruydqqv1");
        mockMvc.perform(post(SUBMIT)
                        .header("X-ApiKey", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        mockMvc.perform(post(SUBMIT)
                        .header("ApiKey", API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(minimal("u4pruydqqv2")))
                .andExpect(status().isOk());

        assertThat(readingCount()).isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Null handling
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("omitted optional fields are stored as SQL NULL, not 0")
    void nullsArePersistedAsNull() throws Exception {
        // Only geohash is mandatory; everything else omitted -> JSON null.
        String body = """
                { "geohash": "sparsegeohash" }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY_2)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM airbox_readings WHERE geohash = ?", "sparsegeohash");

        // device/installation/time still required + present.
        assertThat(row.get("device")).isEqualTo(DEVICE_ID_2);
        assertThat(row.get("installation")).isEqualTo("garden");
        assertThat(row.get("time")).isNotNull();

        // Every measurement column must be genuinely NULL.
        for (String col : new String[]{"charge", "sun", "co2", "pm1", "pm25", "pm4",
                "pm10", "temp", "hum", "voc_index", "nox_index", "voc", "nox"}) {
            assertThat(row.get(col)).as("column %s should be NULL", col).isNull();
        }
    }

    @Test
    @DisplayName("explicit JSON null is treated the same as an omitted field")
    void explicitNullsHandled() throws Exception {
        String body = """
                {
                  "geohash": "mixednulls",
                  "charge": 50.0,
                  "sun": null,
                  "co2": null,
                  "pm1": 1.0,
                  "pm25": null,
                  "temp": 19.0,
                  "hum": null
                }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT * FROM airbox_readings WHERE geohash = ?", "mixednulls");

        assertThat(((Number) row.get("charge")).doubleValue()).isEqualTo(50.0);
        assertThat(((Number) row.get("pm1")).doubleValue()).isEqualTo(1.0);
        assertThat(((Number) row.get("temp")).doubleValue()).isEqualTo(19.0);
        assertThat(row.get("sun")).isNull();
        assertThat(row.get("co2")).isNull();
        assertThat(row.get("pm25")).isNull();
        assertThat(row.get("hum")).isNull();
    }

    // ---------------------------------------------------------------------
    // Edge cases on the charge bounds [0, 100]
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("charge bounds")
    class ChargeBounds {

        @Test
        @DisplayName("charge = 0 and charge = 100 are accepted (inclusive bounds)")
        void inclusiveBoundsAccepted() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withCharge("chargezero", 0.0)))
                    .andExpect(status().isOk());

            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withCharge("chargefull", 100.0)))
                    .andExpect(status().isOk());

            assertThat(readingCount()).isEqualTo(2);
        }

        @Test
        @DisplayName("charge < 0 is rejected with 400 and nothing is written")
        void negativeChargeRejected() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withCharge("chargeneg", -0.01)))
                    .andExpect(status().isBadRequest());

            assertThat(readingCount()).isZero();
        }

        @Test
        @DisplayName("charge > 100 is rejected with 400 and nothing is written")
        void overChargeRejected() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(withCharge("chargeover", 100.01)))
                    .andExpect(status().isBadRequest());

            assertThat(readingCount()).isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Validation + auth edge cases
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("missing geohash -> 400, no row")
    void missingGeohashRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"charge\": 10.0 }"))
                .andExpect(status().isBadRequest());
        assertThat(readingCount()).isZero();
    }

    @Test
    @DisplayName("blank geohash -> 400, no row")
    void blankGeohashRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"geohash\": \"   \" }"))
                .andExpect(status().isBadRequest());
        assertThat(readingCount()).isZero();
    }

    @Test
    @DisplayName("malformed JSON -> 400, no row")
    void malformedPayloadRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ this is not json "))
                .andExpect(status().isBadRequest());
        assertThat(readingCount()).isZero();
    }

    @Test
    @DisplayName("unknown property -> 400 (FAIL_ON_UNKNOWN_PROPERTIES), no row")
    void unknownPropertyRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"geohash\": \"x\", \"bogus\": 1 }"))
                .andExpect(status().isBadRequest());
        assertThat(readingCount()).isZero();
    }

    @Test
    @DisplayName("unknown API key -> 401, no row")
    void unknownApiKeyRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey not-a-real-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimal("u4pruydqqv9")))
                .andExpect(status().isUnauthorized());
        assertThat(readingCount()).isZero();
    }

    @Test
    @DisplayName("missing API key entirely -> 401, no row")
    void noApiKeyRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimal("u4pruydqqv8")))
                .andExpect(status().isUnauthorized());
        assertThat(readingCount()).isZero();
    }

    // ---------------------------------------------------------------------
    // AQI enrichment at ingest (V9 columns)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("full-profile submit (pm25+pm10+nox) is stored with a non-null AQI + dominant pollutant")
    void fullProfileProducesAqi() throws Exception {
        // Mirrors the mock "full" profile: pm25 -> PM2.5, pm10 -> PM10, nox -> NO2 proxy.
        String body = """
                {
                  "geohash": "aqifull",
                  "pm25": 37.5,
                  "pm10": 60.0,
                  "nox": 500.0,
                  "co2": 800.0,
                  "voc_index": 120.0,
                  "nox_index": 30.0
                }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT aqi, aqi_pollutant FROM airbox_readings WHERE geohash = ?", "aqifull");

        // Single reading -> trailing means equal the reading itself. With pm25=37.5 (~105.9),
        // pm10=60 (~53.5) and nox->NO2=500 (~174.6), NO2 dominates and rounds to 175.
        assertThat(((Number) row.get("aqi")).intValue()).isEqualTo(175);
        assertThat(row.get("aqi_pollutant")).isEqualTo("no2");
    }

    @Test
    @DisplayName("sen66 submit (pm25+pm10, no raw nox/co2) now reaches the 2-sub-index gate -> non-null AQI")
    void sen66ProfileProducesAqi() throws Exception {
        // Mirrors the "sen66_no_raw_voc_nox" mock profile: two PM sub-indices, no raw nox, no co2.
        // With the gate lowered to 2 (and PM present) this is now eligible.
        String body = """
                { "geohash": "aqisen66", "pm25": 37.5, "pm10": 60.0, "voc_index": 120.0, "nox_index": 30.0 }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT aqi, aqi_pollutant FROM airbox_readings WHERE geohash = ?", "aqisen66");
        // Single reading -> trailing means equal the reading. pm25=37.5 (~105.9) dominates
        // pm10=60 (~53.5) -> rounds to 106, dominant PM2.5.
        assertThat(((Number) row.get("aqi")).intValue()).isEqualTo(106);
        assertThat(row.get("aqi_pollutant")).isEqualTo("pm25");
    }

    @Test
    @DisplayName("real-SEN66 submit (pm25+pm10+co2) can be CO2-dominant")
    void sen66WithCo2CanBeCo2Dominant() throws Exception {
        // Low PM, high CO2 -> CO2's custom sub-index dominates. pm25=5 (~27.8), pm10=10 (~9.3),
        // co2=1800 -> ~180.36 => aqi 180, dominant co2.
        String body = """
                { "geohash": "aqico2", "pm25": 5.0, "pm10": 10.0, "co2": 1800.0 }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT aqi, aqi_pollutant FROM airbox_readings WHERE geohash = ?", "aqico2");
        assertThat(((Number) row.get("aqi")).intValue()).isEqualTo(180);
        assertThat(row.get("aqi_pollutant")).isEqualTo("co2");
    }

    @Test
    @DisplayName("scd30 profile (co2 only, no PM) is not AQI-eligible -> aqi NULL")
    void scd30ProfileProducesNullAqi() throws Exception {
        String body = """
                { "geohash": "aqiscd30", "co2": 800.0 }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT aqi, aqi_pollutant FROM airbox_readings WHERE geohash = ?", "aqiscd30");
        assertThat(row.get("aqi")).isNull();
        assertThat(row.get("aqi_pollutant")).isNull();
    }

    // ---------------------------------------------------------------------
    // Bulk mock data — many readings across both devices
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("a batch of mock readings all land in H2")
    void bulkMockData() throws Exception {
        String[][] mock = {
                // geohash,        charge, sun,   co2,   pm25
                {"u4pruydqqv10", "12.5",  "false", "410", "4.1"},
                {"u4pruydqqv11", "0",     "true",  "900", "12.0"},
                {"u4pruydqqv12", "100",   "false", "null", "null"},
                {"u4pruydqqv13", "null",  "null",  "500", "8.8"},
        };

        for (String[] m : mock) {
            String body = """
                    {
                      "geohash": "%s",
                      "charge": %s,
                      "sun": %s,
                      "co2": %s,
                      "pm25": %s
                    }
                    """.formatted(m[0], m[1], m[2], m[3], m[4]);

            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON).content(body))
                    .andExpect(status().isOk());
        }

        assertThat(readingCount()).isEqualTo(mock.length);
        // The charge=100/co2=null row exists with co2 genuinely NULL.
        assertThat(jdbc.queryForObject(
                "SELECT co2 FROM airbox_readings WHERE geohash = ?", Object.class, "u4pruydqqv12"))
                .isNull();
    }

    // ---------------------------------------------------------------------
    // Raw SGP41 ticks -> stateful gas-index conversion (V10)
    // ---------------------------------------------------------------------

    @Test
    @DisplayName("voc_raw/nox_raw -> 200, computed indices stored, state rows created and advanced")
    void rawTicksConvertedAndStatePersisted() throws Exception {
        String body = """
                { "geohash": "rawticks1", "voc_raw": 30000, "nox_raw": 17000 }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        // The reading carries computed indices (0 during the 45s blackout, but never NULL).
        Map<String, Object> row = jdbc.queryForMap(
                "SELECT voc_index, nox_index FROM airbox_readings WHERE geohash = ?", "rawticks1");
        assertThat(row.get("voc_index")).as("voc_index computed from voc_raw").isNotNull();
        assertThat(row.get("nox_index")).as("nox_index computed from nox_raw").isNotNull();

        // A state row exists for each of (device, VOC) and (device, NOX).
        assertThat(gasRowCount(DEVICE_ID)).isEqualTo(2);
        Map<String, Object> vocBefore = gasRow(DEVICE_ID, "VOC");
        Map<String, Object> noxBefore = gasRow(DEVICE_ID, "NOX");
        assertThat(vocBefore.get("state")).isNotNull();
        assertThat(noxBefore.get("state")).isNotNull();

        // Ensure the wall clock advances so updated_at is observably later.
        Thread.sleep(5);

        // Second submit advances the stored state: JSON changes and updated_at moves forward.
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"geohash\": \"rawticks2\", \"voc_raw\": 30100, \"nox_raw\": 17100 }"))
                .andExpect(status().isOk());

        assertThat(gasRowCount(DEVICE_ID)).isEqualTo(2);          // still one row per signal
        Map<String, Object> vocAfter = gasRow(DEVICE_ID, "VOC");
        assertThat(vocAfter.get("state")).isNotEqualTo(vocBefore.get("state"));
        assertThat((OffsetDateTime) vocAfter.get("updated_at"))
                .isAfter((OffsetDateTime) vocBefore.get("updated_at"));
    }

    @Test
    @DisplayName("explicit index + raw together -> explicit wins and its state is NOT advanced")
    void explicitIndexWinsRawIgnored() throws Exception {
        // voc_index supplied explicitly (raw must be ignored, no VOC state row);
        // nox has only raw (must convert, NOX state row created).
        String body = """
                { "geohash": "rawmix", "voc_index": 150.0, "voc_raw": 30000, "nox_raw": 17000 }
                """;

        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk());

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT voc_index, nox_index FROM airbox_readings WHERE geohash = ?", "rawmix");
        assertThat(((Number) row.get("voc_index")).doubleValue()).isEqualTo(150.0); // explicit wins
        assertThat(row.get("nox_index")).isNotNull();                               // converted from raw

        // VOC state NOT advanced (no row); NOX state created.
        assertThat(gasRowCount(DEVICE_ID, "VOC")).isZero();
        assertThat(gasRowCount(DEVICE_ID, "NOX")).isEqualTo(1);
    }

    @Test
    @DisplayName("voc_raw out of range -> 400, nothing written (no reading, no state)")
    void rawOutOfRangeRejected() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"geohash\": \"rawbad\", \"voc_raw\": 70000 }"))
                .andExpect(status().isBadRequest());

        assertThat(readingCount()).isZero();
        assertThat(gasRowCount(DEVICE_ID)).isZero();
    }

    @Test
    @DisplayName("reading with neither raw nor index creates no gas-algorithm state rows")
    void noRawNoStateRows() throws Exception {
        mockMvc.perform(post(SUBMIT)
                        .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{ \"geohash\": \"norawplain\", \"pm25\": 5.0 }"))
                .andExpect(status().isOk());

        assertThat(gasRowCount(DEVICE_ID)).isZero();
    }

    private long gasRowCount(String deviceId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM airbox_gas_algorithm WHERE device_id = ?", Long.class, deviceId);
    }

    private long gasRowCount(String deviceId, String algoType) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM airbox_gas_algorithm WHERE device_id = ? AND algo_type = ?",
                Long.class, deviceId, algoType);
    }

    private Map<String, Object> gasRow(String deviceId, String algoType) {
        return jdbc.queryForMap(
                "SELECT state, updated_at FROM airbox_gas_algorithm WHERE device_id = ? AND algo_type = ?",
                deviceId, algoType);
    }

    // ---------------------------------------------------------------------
    // Server-side geohash override (V11)
    // ---------------------------------------------------------------------

    @Nested
    @DisplayName("geohash override")
    class GeohashOverride {

        private static final String OVR_DEVICE = "device-ovr-001";
        private static final String OVR_KEY    = "test-api-key-ovr1";
        private static final String OVERRIDE   = "sxfsg111";

        private static final String BLANK_DEVICE = "device-blank-001";
        private static final String BLANK_KEY     = "test-api-key-blank";

        @BeforeEach
        void seedOverrides() {
            insertInstallationWithOverride(OVR_DEVICE, OVR_KEY, "ovr@example.com",
                    "outdoor", OVERRIDE);
            // Blank-string override (a single space) must behave exactly like no override.
            insertInstallationWithOverride(BLANK_DEVICE, BLANK_KEY, "blank@example.com",
                    "outdoor", " ");
        }

        @Test
        @DisplayName("override set + payload geohash present -> stored geohash is the override")
        void overrideWinsOverPayload() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + OVR_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"geohash\": \"u8mb999zz\", \"charge\": 55.0 }"))
                    .andExpect(status().isOk());

            assertThat(storedGeohash(OVR_DEVICE)).isEqualTo(OVERRIDE);
        }

        @Test
        @DisplayName("override set + payload geohash ABSENT -> 200, stored geohash is the override")
        void overrideSatisfiesMissingGeohash() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + OVR_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"charge\": 42.0 }"))
                    .andExpect(status().isOk());

            assertThat(storedGeohash(OVR_DEVICE)).isEqualTo(OVERRIDE);
        }

        @Test
        @DisplayName("override set + blank payload geohash -> 200, stored geohash is the override")
        void overrideSatisfiesBlankGeohash() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + OVR_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"geohash\": \"   \" }"))
                    .andExpect(status().isOk());

            assertThat(storedGeohash(OVR_DEVICE)).isEqualTo(OVERRIDE);
        }

        @Test
        @DisplayName("override NULL -> payload geohash is used, unchanged")
        void nullOverrideUsesPayload() throws Exception {
            // DEVICE_ID (seeded in the outer @BeforeEach) has a NULL override.
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"geohash\": \"payloadgh1\" }"))
                    .andExpect(status().isOk());

            assertThat(storedGeohash(DEVICE_ID)).isEqualTo("payloadgh1");
        }

        @Test
        @DisplayName("override NULL + payload geohash absent -> 400, nothing written")
        void nullOverrideMissingGeohashRejected() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + API_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"charge\": 10.0 }"))
                    .andExpect(status().isBadRequest());
            assertThat(readingCount()).isZero();
        }

        @Test
        @DisplayName("blank-string override behaves as no override: payload geohash used")
        void blankOverrideUsesPayload() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + BLANK_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"geohash\": \"payloadgh2\" }"))
                    .andExpect(status().isOk());

            assertThat(storedGeohash(BLANK_DEVICE)).isEqualTo("payloadgh2");
        }

        @Test
        @DisplayName("blank-string override + payload geohash absent -> 400 (override is inert)")
        void blankOverrideMissingGeohashRejected() throws Exception {
            mockMvc.perform(post(SUBMIT)
                            .header(HttpHeaders.AUTHORIZATION, "ApiKey " + BLANK_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{ \"charge\": 10.0 }"))
                    .andExpect(status().isBadRequest());
            assertThat(readingCount()).isZero();
        }
    }

    // ---------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------

    private static String minimal(String geohash) {
        return "{ \"geohash\": \"" + geohash + "\" }";
    }

    private static String withCharge(String geohash, double charge) {
        return "{ \"geohash\": \"" + geohash + "\", \"charge\": " + charge + " }";
    }
}
