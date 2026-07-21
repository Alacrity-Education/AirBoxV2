package ro.alacrity.airbox.middleware.service;

import ro.alacrity.airbox.middleware.aqi.AqiCalculator;
import ro.alacrity.airbox.middleware.aqi.Pollutant;
import ro.alacrity.airbox.middleware.configs.JacksonConfig;
import ro.alacrity.airbox.middleware.dto.SensorReadingDTO;
import ro.alacrity.airbox.middleware.entity.Installation;
import ro.alacrity.airbox.middleware.entity.SensorReading;
import ro.alacrity.airbox.middleware.exception.UnknownApiKeyException;
import ro.alacrity.airbox.middleware.exception.ValidationException;
import ro.alacrity.airbox.middleware.exception.ValidationKind;
import ro.alacrity.airbox.middleware.repository.InstallationsRepository;
import ro.alacrity.airbox.middleware.repository.SensorReadingRepository;
import ro.alacrity.airbox.middleware.repository.SensorReadingRepository.TrailingAggregates;
import ro.alacrity.airbox.middleware.tools.ApiKeySolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;

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

        enrichAqi(sensorReading);

        sensorReadingRepository.insert(sensorReading);
    }

    /**
     * Compute and attach the EPA-style AQI for this reading before it is persisted.
     *
     * <p>AirBox field → EPA pollutant mapping (only the three fields with an EPA AQI table
     * that AirBox can supply feed the calculator today):
     * <ul>
     *   <li>{@code pm25} → PM2.5 (µg/m³, 24-hour trailing mean)</li>
     *   <li>{@code pm10} → PM10 (µg/m³, 24-hour trailing mean)</li>
     *   <li>{@code nox}  → NO2 <b>proxy</b>: the raw NOx value is treated as an NO2
     *       concentration in ppb over a 1-hour trailing mean. This is a chemically approximate
     *       stand-in — NOx (NO + NO2) is not NO2, and the sensor's raw units are not calibrated
     *       ppb — used only because AirBox exposes no dedicated NO2 channel.</li>
     * </ul>
     * co2 / voc / voc_index / nox_index have no EPA AQI and are excluded; O3 / SO2 / CO are not
     * measured. Consequently only readings carrying pm25 + pm10 + nox (the "full" sensor profile)
     * reach the 3-pollutant eligibility threshold.
     *
     * <p>Trailing means include the current reading: the DB supplies the historical sum/count per
     * pollutant over its window and the current value is folded in ({@code (sum+cur)/(count+1)}),
     * so a device's very first reading averages to itself. One aggregate query per insert.
     */
    private void enrichAqi(SensorReading reading) {
        OffsetDateTime asOf = reading.getTimestamp();
        TrailingAggregates agg = sensorReadingRepository.trailingAggregates(
                reading.getDevice(), asOf.minusHours(24), asOf.minusHours(1));

        Map<Pollutant, Double> concentrations = new EnumMap<>(Pollutant.class);
        putIfPresent(concentrations, Pollutant.PM25, agg.meanPm25(reading.getPm25()));
        putIfPresent(concentrations, Pollutant.PM10, agg.meanPm10(reading.getPm10()));
        putIfPresent(concentrations, Pollutant.NO2, agg.meanNox(reading.getNox()));

        AqiCalculator.compute(concentrations).ifPresent(result -> {
            reading.setAqi(result.aqi());
            reading.setAqiPollutant(result.dominant().key());
        });
    }

    private static void putIfPresent(Map<Pollutant, Double> map, Pollutant pollutant, Double value) {
        if (value != null) {
            map.put(pollutant, value);
        }
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
