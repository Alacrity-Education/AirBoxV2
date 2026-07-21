package com.cezar.newmiddleware.configs;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.core.StreamReadConstraints;
import tools.jackson.core.json.JsonFactory;
import tools.jackson.core.json.JsonReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Configuration
public class JacksonConfig {

    public static final String INGEST_MAPPER = "ingestJsonMapper";
    private StreamReadConstraints streamReadConstraints() {
        return StreamReadConstraints.builder()
                .maxStringLength(100)
                .maxNumberLength(100)
                .maxNestingDepth(2)
                .build();
    }

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
