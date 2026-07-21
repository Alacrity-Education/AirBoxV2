package com.cezar.newmiddleware;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

// The test profile (H2 + h2migration Flyway) keeps this runnable without MDW_DB_* env vars.
@SpringBootTest
@ActiveProfiles("test")
class NewMiddlewareApplicationTests {

    @Test
    void contextLoads() {
    }

}
