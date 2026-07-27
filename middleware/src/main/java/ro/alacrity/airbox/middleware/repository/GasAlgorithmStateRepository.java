package ro.alacrity.airbox.middleware.repository;

import ro.alacrity.airbox.middleware.gasindex.GasIndexAlgorithm.Type;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Persistence for the Sensirion gas-index algorithm State, one row per
 * (device, VOC|NOX) in {@code airbox_gas_algorithm}. The State itself is opaque
 * here — it is a JSON string produced/consumed by the service layer's mapper.
 *
 * <p>{@link #lockRow} takes a row-level {@code FOR UPDATE} lock so a device's
 * two concurrent submits cannot interleave the read-modify-write of its state,
 * and {@link #upsert} writes it back. Both use the ambient {@link JdbcTemplate},
 * so when the caller runs under {@code @Transactional} they join the SAME
 * transaction as the reading insert — the lock is held to commit and the state
 * advance is atomic with the reading it produced.
 */
@Repository
public class GasAlgorithmStateRepository {

    private static final String LOCK_QUERY = """
            SELECT state, updated_at FROM airbox_gas_algorithm
            WHERE device_id = ? AND algo_type = ?
            FOR UPDATE
            """;

    // Create the row on the first raw sample for this (device, signal); advance it in
    // place thereafter. updated_at is the instant this sample was processed and becomes
    // the dt anchor for the next one. The caller decides INSERT vs UPDATE from whether
    // lockRow() found the row — a portable UPSERT that avoids Postgres-only ON CONFLICT
    // (H2, used by the ingest test, does not support ON CONFLICT ... DO UPDATE). The row
    // is FOR UPDATE-locked for the life of the transaction, so the read-decide-write is
    // race-free for an existing row; a brand-new (device, signal)'s first two concurrent
    // samples are still guarded by the primary key.
    private static final String INSERT_QUERY = """
            INSERT INTO airbox_gas_algorithm(device_id, algo_type, state, updated_at)
            VALUES (?, ?, ?, ?)
            """;

    private static final String UPDATE_QUERY = """
            UPDATE airbox_gas_algorithm
            SET state = ?, updated_at = ?
            WHERE device_id = ? AND algo_type = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public GasAlgorithmStateRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    /** The persisted State JSON and the instant it was last written. */
    public record StateRow(String state, OffsetDateTime updatedAt) {}

    /**
     * Row-locking read of the current state for one (device, signal), or empty
     * when no raw sample has ever been processed for it.
     */
    public Optional<StateRow> lockRow(String deviceId, Type type) {
        List<StateRow> rows = jdbcTemplate.query(LOCK_QUERY,
                (rs, rowNum) -> new StateRow(
                        rs.getString("state"),
                        rs.getObject("updated_at", OffsetDateTime.class)),
                deviceId, type.name());
        return rows.stream().findFirst();
    }

    /** Create the state row for a (device, signal) seen for the first time. */
    public void insert(String deviceId, Type type, String state, OffsetDateTime updatedAt) {
        jdbcTemplate.update(INSERT_QUERY, deviceId, type.name(), state, updatedAt);
    }

    /** Advance an existing, {@link #lockRow}-locked state row in place. */
    public void update(String deviceId, Type type, String state, OffsetDateTime updatedAt) {
        jdbcTemplate.update(UPDATE_QUERY, state, updatedAt, deviceId, type.name());
    }
}
