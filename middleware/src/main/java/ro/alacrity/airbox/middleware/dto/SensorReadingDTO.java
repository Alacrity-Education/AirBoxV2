package ro.alacrity.airbox.middleware.dto;

// The request template
public record SensorReadingDTO(
        String geohash,
        Double charge,
        Boolean sun,
        Double pm1,
        Double pm25,
        Double pm4,
        Double pm10,
        Double temp,
        Double hum,
        Double voc_index,
        Double nox_index,
        Double voc,
        Double nox,
        Double co2
) {}
