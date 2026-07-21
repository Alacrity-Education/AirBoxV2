package ro.alacrity.airbox.middleware.repository;

import ro.alacrity.airbox.middleware.entity.SensorReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;
import java.time.OffsetDateTime;

@Repository
public class SensorReadingRepository {
    private static final String INSERT_QUERY = """
            INSERT INTO airbox_readings(time, device, installation, geohash,
                                        charge, sun, co2, pm1, pm25, pm4, pm10,
                                        temp, hum, voc_index, nox_index, voc, nox,
                                        aqi, aqi_pollutant)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    /**
     * Trailing per-pollutant aggregates for one device, as of an ingest instant.
     * PM2.5 / PM10 use a 24-hour trailing window; NO2 (fed from raw NOx) a 1-hour window.
     * Returns running sums and non-null counts so the caller can fold in the current
     * reading's value and derive the trailing mean (mean = (sum + current) / (count + 1)).
     * COUNT(...) ignores NULLs and COALESCE(SUM(...),0) guards the empty-window case, so a
     * device with no history yields sum 0 / count 0 (mean then equals the current reading).
     * CASE-guarded NOx aggregation keeps NO2 on its own 1-hour window within the single 24h scan.
     */
    private static final String TRAILING_AGG_QUERY = """
            SELECT COALESCE(SUM(pm25), 0)                             AS sum_pm25,
                   COUNT(pm25)                                        AS cnt_pm25,
                   COALESCE(SUM(pm10), 0)                             AS sum_pm10,
                   COUNT(pm10)                                        AS cnt_pm10,
                   COALESCE(SUM(CASE WHEN time >= ? THEN nox END), 0) AS sum_nox,
                   COUNT(CASE WHEN time >= ? THEN nox END)            AS cnt_nox
            FROM airbox_readings
            WHERE device = ? AND time >= ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public SensorReadingRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void insert(SensorReading sr) {
        jdbcTemplate.update(connection -> {
            PreparedStatement ps = connection.prepareStatement(INSERT_QUERY);

            // never null
            ps.setObject(1, sr.getTimestamp());
            ps.setString(2, sr.getDevice());
            ps.setString(3, sr.getInstallation());
            ps.setString(4, sr.getGeohash());

            setNullable(ps, 5, sr.getCharge(), Types.FLOAT);
            setNullable(ps, 6, sr.getSun(), Types.BOOLEAN);
            setNullable(ps, 7, sr.getCo2(), Types.FLOAT);
            setNullable(ps, 8, sr.getPm1(), Types.FLOAT);
            setNullable(ps, 9, sr.getPm25(), Types.FLOAT);
            setNullable(ps, 10, sr.getPm4(), Types.FLOAT);
            setNullable(ps, 11, sr.getPm10(), Types.FLOAT);
            setNullable(ps, 12, sr.getTemp(), Types.FLOAT);
            setNullable(ps, 13, sr.getHum(), Types.FLOAT);
            setNullable(ps, 14, sr.getVoc_index(), Types.FLOAT);
            setNullable(ps, 15, sr.getNox_index(), Types.FLOAT);
            setNullable(ps, 16, sr.getVoc(), Types.FLOAT);
            setNullable(ps, 17, sr.getNox(), Types.FLOAT);
            setNullable(ps, 18, sr.getAqi(), Types.INTEGER);
            setNullable(ps, 19, sr.getAqiPollutant(), Types.VARCHAR);

            return ps;
        });
    }

    /**
     * Fetch the trailing aggregates this device needs to derive AQI input concentrations.
     *
     * @param device       device id to aggregate over
     * @param window24hLow  lower bound of the PM 24-hour window (exclusive of older rows)
     * @param window1hLow   lower bound of the NO2 1-hour window
     */
    public TrailingAggregates trailingAggregates(String device,
                                                 OffsetDateTime window24hLow,
                                                 OffsetDateTime window1hLow) {
        return jdbcTemplate.queryForObject(TRAILING_AGG_QUERY, (rs, rowNum) -> new TrailingAggregates(
                        rs.getDouble("sum_pm25"), rs.getLong("cnt_pm25"),
                        rs.getDouble("sum_pm10"), rs.getLong("cnt_pm10"),
                        rs.getDouble("sum_nox"), rs.getLong("cnt_nox")),
                window1hLow, window1hLow, device, window24hLow);
    }

    /** Running sums and non-null counts for the AQI-relevant pollutants over their windows. */
    public record TrailingAggregates(double sumPm25, long countPm25,
                                     double sumPm10, long countPm10,
                                     double sumNox, long countNox) {

        /** Trailing mean including the supplied current value, or {@code null} if it is null. */
        public Double meanPm25(Double current) { return fold(sumPm25, countPm25, current); }
        public Double meanPm10(Double current) { return fold(sumPm10, countPm10, current); }
        public Double meanNox(Double current)  { return fold(sumNox, countNox, current); }

        private static Double fold(double sum, long count, Double current) {
            if (current == null) {
                return null;
            }
            return (sum + current) / (count + 1);
        }
    }

    private <T> void setNullable(PreparedStatement ps, int idx, T value, int sqlType) throws SQLException {
        if(value == null)
            ps.setNull(idx, sqlType);
        else
            ps.setObject(idx, value, sqlType);
    }
}
