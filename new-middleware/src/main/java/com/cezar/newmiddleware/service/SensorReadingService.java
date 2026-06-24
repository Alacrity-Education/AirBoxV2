package com.cezar.newmiddleware.service;

import com.cezar.newmiddleware.configs.JacksonConfig;
import com.cezar.newmiddleware.dto.SensorReadingDTO;
import com.cezar.newmiddleware.entity.Installation;
import com.cezar.newmiddleware.entity.SensorReading;
import com.cezar.newmiddleware.exception.UnknownApiKeyException;
import com.cezar.newmiddleware.exception.ValidationException;
import com.cezar.newmiddleware.exception.ValidationKind;
import com.cezar.newmiddleware.repository.InstallationsRepository;
import com.cezar.newmiddleware.repository.SensorReadingRepository;
import com.cezar.newmiddleware.tools.ApiKeySolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;

@Service
public class SensorReadingService {

    private final SensorReadingRepository sensorReadingRepository;
    private final InstallationsRepository installationsRepository;
    private final JsonMapper jsonMapper;

    public SensorReadingService(SensorReadingRepository sensorReadingRepository, InstallationsRepository installationsRepository,
                                @Qualifier(JacksonConfig.INGEST_MAPPER) JsonMapper jsonMapper) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.installationsRepository = installationsRepository;
        this.jsonMapper = jsonMapper;
    }

    public void ingest(String authorization, String apiKeyHeader,
                       String xApiKeyHeader, byte[] rawBody) {

        String token = ApiKeySolver.resolve(authorization, apiKeyHeader, xApiKeyHeader);

        Installation installation = installationsRepository.findByApiKey(token)
                .orElseThrow(UnknownApiKeyException::new);

        String ownerEmail = installation.getOwnerEmail();

        SensorReadingDTO srDTO;
        try {
            srDTO = jsonMapper.readValue(rawBody, SensorReadingDTO.class);
        } catch (JacksonException e) {
            throw new ValidationException(ownerEmail, ValidationKind.MALFORMED_PAYLOAD);
        }

        validateSemantics(srDTO, ownerEmail);

        SensorReading sensorReading = new SensorReading(OffsetDateTime.now(),
                installation.getDeviceId(), srDTO.geohash(),
                installation.getInstallation(), srDTO.charge(), srDTO.sun(),
                srDTO.pm1(), srDTO.pm25(), srDTO.pm4(), srDTO.pm10(),
                srDTO.temp(), srDTO.hum(), srDTO.voc_index(), srDTO.nox_index(),
                srDTO.voc(), srDTO.nox(), srDTO.co2()
        );

        sensorReadingRepository.insert(sensorReading);
    }

    public void validateSemantics(SensorReadingDTO srDTO, String ownerEmail) {
        if(srDTO.geohash() == null || srDTO.geohash().isBlank()) {
            throw new ValidationException(ownerEmail, ValidationKind.MISSING_GEOHASH);
        }

        Double charge = srDTO.charge();
        if(charge != null && (charge < 0d || charge > 100d)) {
            throw new ValidationException(ownerEmail, ValidationKind.CHARGE_OUT_OF_RANGE);
        }
    }
}
