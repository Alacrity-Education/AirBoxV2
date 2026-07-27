package ro.alacrity.airbox.middleware.configs;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

    public static final String INGEST_MAPPER = "ingestJsonMapper";

    /**
     * Dedicated mapper for persisting the Sensirion gas-index algorithm State
     * (see {@link ro.alacrity.airbox.middleware.gasindex.GasIndexAlgorithm.State}).
     * The State is a plain POJO of public fields with NO getters/setters, so
     * visibility is pinned to fields only (getters/setters/creators off) to
     * guarantee every field — and only the fields — round-trips. Floats are
     * written via their shortest exact decimal form and read straight back, so
     * serialize -> deserialize -> serialize is byte-identical.
     */
    public static final String GAS_STATE_MAPPER = "gasStateJsonMapper";

    @Bean(GAS_STATE_MAPPER)
    public JsonMapper gasStateJsonMapper() {
        return JsonMapper.builder()
                .changeDefaultVisibility(vc -> vc
                        .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.NONE)
                        .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
                .build();
    }

    private StreamReadConstraints streamReadConstraints() {
        return StreamReadConstraints.builder()
                .maxStringLength(100)
                .maxNumberLength(100)
                .maxNestingDepth(2)
                .build();
    }

    // @Primary so the generic-typed JsonMapper injection points (Spring Boot's HTTP
    // message-converter autoconfig, etc.) still resolve to this mapper now that a second
    // JsonMapper (GAS_STATE_MAPPER, qualifier-only) exists. Preserves pre-existing behavior
    // when ingestJsonMapper was the sole JsonMapper bean.
    @Primary
    @Bean(INGEST_MAPPER)
    public JsonMapper ingestJsonMapper() {
        StreamReadConstraints streamReadConstraints = streamReadConstraints();
        JsonFactory jsonFactory = JsonFactory.builder()
                .streamReadConstraints(streamReadConstraints)
                .build();

        return JsonMapper.builder(jsonFactory)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .disable(JsonReadFeature.ALLOW_NON_NUMERIC_NUMBERS)
                .build();
    }
}
