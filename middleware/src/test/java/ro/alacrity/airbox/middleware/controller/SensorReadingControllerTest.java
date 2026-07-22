package ro.alacrity.airbox.middleware.controller;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The ingest endpoint doubles as a public help page: a GET renders a static HTML how-to
 * (no auth, no runtime data) while the POST continues to ingest readings (covered elsewhere).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DisplayName("Ingest endpoint GET help page")
class SensorReadingControllerTest {

    @Autowired private MockMvc mockMvc;

    @Test
    @DisplayName("GET /api/v2/submit -> 200 HTML how-to page, no auth required")
    void getRendersHelpPage() throws Exception {
        mockMvc.perform(get("/api/v2/submit"))
                .andExpect(status().isOk())
                .andExpect(content().contentType("text/html;charset=utf-8"))
                // distinctive phrase from the page body
                .andExpect(content().string(containsString("Send your air-quality sensor readings to AirBox")))
                // the wiki documentation link
                .andExpect(content().string(containsString("https://wiki.alacrity.ro/en/Projects/airbox-v2")))
                // the ingest endpoint and title
                .andExpect(content().string(containsString("AirBox Ingest API")))
                .andExpect(content().string(containsString("https://ingest.airbox.alacrity.ro/api/v2/submit")))
                // all three accepted API-key header names
                .andExpect(content().string(containsString("Authorization: ApiKey")))
                .andExpect(content().string(containsString("ApiKey: &lt;key&gt;")))
                .andExpect(content().string(containsString("X-ApiKey: &lt;key&gt;")));
    }
}
