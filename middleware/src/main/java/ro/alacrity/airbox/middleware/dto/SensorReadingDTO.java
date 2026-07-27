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
        Double co2,
        // Raw SGP41 ticks (0..65535) from SEN66 units that don't compute the index
        // on-module. Converted server-side to voc_index/nox_index via the stateful
        // Sensirion Gas Index Algorithm. Mutually exclusive with sending the index
        // directly: if the matching *_index is present it wins and the raw is ignored.
        Integer voc_raw,
        Integer nox_raw
) {}
