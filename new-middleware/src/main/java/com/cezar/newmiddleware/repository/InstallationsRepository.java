package com.cezar.newmiddleware.repository;

import com.cezar.newmiddleware.entity.Installation;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.Optional;

@Repository
public class InstallationsRepository {
    private static final String READ_QUERY = """
            SELECT * FROM airbox_installations WHERE apikey = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public InstallationsRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    private static final RowMapper<Installation> INSTALLATION_ROW_MAPPER = (rs, rowNum) ->
    new Installation(
            rs.getString("device_id"),
            rs.getString("apikey"),
            rs.getString("owner_email"),
            rs.getString("co_owner1_email"),
            rs.getString("co_owner2_email"),
            rs.getString("installation"),
            rs.getString("notes"),
            rs.getObject(8, OffsetDateTime.class)
    );

    public Optional<Installation> findByApiKey(String apikey) {
        return jdbcTemplate.query(READ_QUERY, INSTALLATION_ROW_MAPPER, apikey)
                .stream()
                .findFirst();
    }
}