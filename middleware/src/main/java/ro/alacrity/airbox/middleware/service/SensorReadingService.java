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
import ro.alacrity.airbox.middleware.gasindex.GasIndexAlgorithm.Type;
import ro.alacrity.airbox.middleware.gasindex.GasIndexService;
import ro.alacrity.airbox.middleware.repository.InstallationsRepository;
import ro.alacrity.airbox.middleware.repository.SensorReadingRepository;
import ro.alacrity.airbox.middleware.repository.SensorReadingRepository.TrailingAggregates;
import ro.alacrity.airbox.middleware.tools.ApiKeySolver;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.EnumMap;
import java.util.Map;

@Service
public class SensorReadingService {

    // SGP41 raw ticks are a 16-bit unsigned value.
    private static final int RAW_TICKS_MIN = 0;
    private static final int RAW_TICKS_MAX = 65535;

    private final SensorReadingRepository sensorReadingRepository;
    private final InstallationsRepository installationsRepository;
    private final GasIndexService gasIndexService;
    private final JsonMapper jsonMapper;

    public SensorReadingService(SensorReadingRepository sensorReadingRepository, InstallationsRepository installationsRepository,
                                GasIndexService gasIndexService,
                                @Qualifier(JacksonConfig.INGEST_MAPPER) JsonMapper jsonMapper) {
        this.sensorReadingRepository = sensorReadingRepository;
        this.installationsRepository = installationsRepository;
        this.gasIndexService = gasIndexService;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Ingest one reading. Runs in a single transaction so the gas-index state
     * advance (row lock + UPSERT in {@link GasIndexService}) and the reading
     * insert commit atomically: a device's raw sample and the state it produced
     * are never split across transactions.
     */
    @Transactional
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

        OffsetDateTime now = OffsetDateTime.now();
        String deviceId = installation.getDeviceId();

        SensorReading sensorReading = new SensorReading(now,
                deviceId, srDTO.geohash(),
                installation.getInstallation(), srDTO.charge(), srDTO.sun(),
                srDTO.pm1(), srDTO.pm25(), srDTO.pm4(), srDTO.pm10(),
                srDTO.temp(), srDTO.hum(), srDTO.voc_index(), srDTO.nox_index(),
                srDTO.voc(), srDTO.nox(), srDTO.co2()
        );

        enrichGasIndices(sensorReading, srDTO, deviceId, now);

        enrichAqi(sensorReading);

        sensorReadingRepository.insert(sensorReading);
    }

    /**
     * Fold raw SGP41 ticks into the reading's index fields via the stateful
     * Sensirion algorithm. Precedence: an explicitly supplied voc_index/nox_index
     * always wins and the corresponding raw is ignored — and, crucially, the
     * stored algorithm state is NOT advanced in that case (only raw samples drive
     * it). A raw value converts only when its index counterpart is absent.
     */
    private void enrichGasIndices(SensorReading reading, SensorReadingDTO srDTO,
                                  String deviceId, OffsetDateTime now) {
        if (srDTO.voc_index() == null && srDTO.voc_raw() != null) {
            reading.setVoc_index((double) gasIndexService.advanceAndComputeIndex(
                    deviceId, Type.VOC, srDTO.voc_raw(), now));
        }
        if (srDTO.nox_index() == null && srDTO.nox_raw() != null) {
            reading.setNox_index((double) gasIndexService.advanceAndComputeIndex(
                    deviceId, Type.NOX, srDTO.nox_raw(), now));
        }
    }

    /**
     * Compute and attach the EPA-style AQI for this reading before it is persisted.
     *
     * <p>AirBox field → EPA pollutant mapping (only the three fields with an EPA AQI table
     * that AirBox can supply feed the calculator today):
     * <ul>
     *   <li>{@code pm25} → PM2.5 (µg/m³, 3-hour trailing mean)</li>
     *   <li>{@code pm10} → PM10 (µg/m³, 3-hour trailing mean)</li>
     *   <li>{@code nox}  → NO2 <b>proxy</b>: the raw NOx value is treated as an NO2
     *       concentration in ppb over a 1-hour trailing mean. This is a chemically approximate
     *       stand-in — NOx (NO + NO2) is not NO2, and the sensor's raw units are not calibrated
     *       ppb — used only because AirBox exposes no dedicated NO2 channel.</li>
     *   <li>{@code co2} → CO2 (ppm, 1-hour trailing mean) via a <b>CUSTOM, non-EPA</b> IAQ
     *       comfort-proxy breakpoint table (see {@link Pollutant} — the EPA publishes no CO2 AQI).</li>
     * </ul>
     * voc / voc_index / nox_index have no AQI table and are excluded; O3 / SO2 / CO are not
     * measured. With the gate lowered to two sub-indices, a real SEN66 (pm25 + pm10 + co2, no raw
     * nox) now reaches eligibility, as does the "full" profile (pm25 + pm10 + nox + co2).
     *
     * <p>Trailing means include the current reading: the DB supplies the historical sum/count per
     * pollutant over its window and the current value is folded in ({@code (sum+cur)/(count+1)}),
     * so a device's very first reading averages to itself. One aggregate query per insert.
     */
    private void enrichAqi(SensorReading reading) {
        OffsetDateTime asOf = reading.getTimestamp();
        TrailingAggregates agg = sensorReadingRepository.trailingAggregates(
                reading.getDevice(), asOf.minusHours(3), asOf.minusHours(1));

        Map<Pollutant, Double> concentrations = new EnumMap<>(Pollutant.class);
        putIfPresent(concentrations, Pollutant.PM25, agg.meanPm25(reading.getPm25()));
        putIfPresent(concentrations, Pollutant.PM10, agg.meanPm10(reading.getPm10()));
        putIfPresent(concentrations, Pollutant.NO2, agg.meanNox(reading.getNox()));
        putIfPresent(concentrations, Pollutant.CO2, agg.meanCo2(reading.getCo2()));

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

        // Raw SGP41 ticks must be a 16-bit unsigned value; out-of-range is a 400 like
        // any other validation failure, whether or not the index counterpart is present.
        Integer vocRaw = srDTO.voc_raw();
        if(vocRaw != null && (vocRaw < RAW_TICKS_MIN || vocRaw > RAW_TICKS_MAX)) {
            throw new ValidationException(ownerEmail, ValidationKind.VOC_RAW_OUT_OF_RANGE);
        }

        Integer noxRaw = srDTO.nox_raw();
        if(noxRaw != null && (noxRaw < RAW_TICKS_MIN || noxRaw > RAW_TICKS_MAX)) {
            throw new ValidationException(ownerEmail, ValidationKind.NOX_RAW_OUT_OF_RANGE);
        }
    }
}
