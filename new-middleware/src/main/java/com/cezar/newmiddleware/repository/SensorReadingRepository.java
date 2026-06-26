package com.cezar.newmiddleware.repository;

import com.cezar.newmiddleware.entity.SensorReading;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.*;

@Repository
public class SensorReadingRepository {
    private static final String INSERT_QUERY = """
            INSERT INTO airbox_readings(time, device, installation, geohash,
                                        charge, sun, co2, pm1, pm25, pm4, pm10,
                                        temp, hum, voc_index, nox_index, voc, nox)
            VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
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

            return ps;
        });
    }

    private <T> void setNullable(PreparedStatement ps, int idx, T value, int sqlType) throws SQLException {
        if(value == null)
            ps.setNull(idx, sqlType);
        else
            ps.setObject(idx, value, sqlType);
    }
}
