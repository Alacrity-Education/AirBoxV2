package ro.alacrity.airbox.middleware.grafana;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("PublicTokenDeriver")
class PublicTokenDeriverTest {

    @Test
    @DisplayName("token is 32 lowercase hex chars (Grafana accessToken shape)")
    void tokenShape() {
        assertThat(PublicTokenDeriver.derive("s3cret", "st2-airbox-mock-001"))
                .matches("^[0-9a-f]{32}$");
    }

    @Test
    @DisplayName("deterministic: same inputs, same token")
    void deterministic() {
        assertThat(PublicTokenDeriver.derive("s3cret", "st2-airbox-mock-001"))
                .isEqualTo(PublicTokenDeriver.derive("s3cret", "st2-airbox-mock-001"));
    }

    @Test
    @DisplayName("varies by dashboard uid")
    void variesByUid() {
        assertThat(PublicTokenDeriver.derive("s3cret", "st1-airbox-mock-001"))
                .isNotEqualTo(PublicTokenDeriver.derive("s3cret", "st2-airbox-mock-001"));
    }

    @Test
    @DisplayName("varies by secret")
    void variesBySecret() {
        assertThat(PublicTokenDeriver.derive("secret-one", "st2-airbox-mock-001"))
                .isNotEqualTo(PublicTokenDeriver.derive("secret-two", "st2-airbox-mock-001"));
    }
}
