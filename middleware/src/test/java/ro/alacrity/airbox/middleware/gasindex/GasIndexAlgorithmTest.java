package ro.alacrity.airbox.middleware.gasindex;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import ro.alacrity.airbox.middleware.configs.JacksonConfig;
import ro.alacrity.airbox.middleware.gasindex.GasIndexAlgorithm.State;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the Sensirion gas-index algorithm and the JSON persistence of its State.
 * No Spring context: the State mapper is built straight from {@link JacksonConfig} so the
 * exact production serialization idiom is exercised.
 */
@DisplayName("Gas Index Algorithm")
class GasIndexAlgorithmTest {

    private final JsonMapper mapper = new JacksonConfig().gasStateJsonMapper();

    @Test
    @DisplayName("State survives a Jackson round-trip exactly, and drives process() identically")
    void stateRoundTripsExactly() {
        // Warm a VOC state well past the blackout so most fields hold non-trivial values.
        State original = State.forVoc();
        for (int i = 0; i < 30; i++) {
            GasIndexAlgorithm.process(29000 + (i * 50), 10f, original);
        }

        String json1 = mapper.writeValueAsString(original);
        State restored = mapper.readValue(json1, State.class);
        String json2 = mapper.writeValueAsString(restored);

        // serialize -> deserialize -> serialize is byte-identical (all fields, float precision).
        assertThat(json2).isEqualTo(json1);

        // process() on the round-tripped state matches process() on the original for the same
        // input — both the returned index and the resulting (re-serialized) state.
        State originalCopy = original.copy();
        int fromOriginal = GasIndexAlgorithm.process(31000, 7f, originalCopy);
        int fromRestored = GasIndexAlgorithm.process(31000, 7f, restored);

        assertThat(fromRestored).isEqualTo(fromOriginal);
        assertThat(mapper.writeValueAsString(restored)).isEqualTo(mapper.writeValueAsString(originalCopy));
    }

    @Test
    @DisplayName("NOX state also round-trips exactly")
    void noxStateRoundTripsExactly() {
        State original = State.forNox();
        for (int i = 0; i < 30; i++) {
            GasIndexAlgorithm.process(16500 + (i * 40), 10f, original);
        }
        String json1 = mapper.writeValueAsString(original);
        String json2 = mapper.writeValueAsString(mapper.readValue(json1, State.class));
        assertThat(json2).isEqualTo(json1);
    }

    @Test
    @DisplayName("fresh state returns index 0 through the 45s blackout, then rises above 0")
    void blackoutThenActive() {
        State state = State.forVoc();

        // dt=10s: accumulated uptime after each of the first five samples is 10,20,30,40,50.
        // While uptime <= 45 (first five samples) the algorithm consumes the sample and emits 0.
        for (int i = 0; i < 5; i++) {
            assertThat(GasIndexAlgorithm.process(30000, 10f, state))
                    .as("sample %d during blackout", i + 1)
                    .isZero();
        }

        // Sixth sample: uptime is now 50 > 45, so the algorithm runs and emits a real index.
        assertThat(GasIndexAlgorithm.process(30000, 10f, state))
                .as("first post-blackout sample")
                .isGreaterThan(0);
    }
}
