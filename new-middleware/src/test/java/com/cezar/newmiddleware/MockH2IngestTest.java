package com.cezar.newmiddleware;

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

    private long readingCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM airbox_readings", Long.class);
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
    // Helpers
    // ---------------------------------------------------------------------

    private static String minimal(String geohash) {
        return "{ \"geohash\": \"" + geohash + "\" }";
    }

    private static String withCharge(String geohash, double charge) {
        return "{ \"geohash\": \"" + geohash + "\", \"charge\": " + charge + " }";
    }
}
