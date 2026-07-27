-- AirBox V2 — stateful VOC/NOx gas-index algorithm state (H2 test variant).
-- Mirrors db/migration/V10__create_gas_algorithm_state.sql. TIMESTAMPTZ is spelled
-- as TIMESTAMP WITH TIME ZONE (H2's canonical form, matching V2's created_at) so the
-- SELECT ... FOR UPDATE / UPSERT round-trip and the OffsetDateTime read-back are
-- exercised faithfully against the mock in-memory H2 database.
CREATE TABLE IF NOT EXISTS airbox_gas_algorithm (
    device_id   VARCHAR(100)             NOT NULL,
    algo_type   VARCHAR(3)               NOT NULL CHECK (algo_type IN ('VOC', 'NOX')),
    state       TEXT                     NOT NULL,
    updated_at  TIMESTAMP WITH TIME ZONE NOT NULL,
    PRIMARY KEY (device_id, algo_type)
);
