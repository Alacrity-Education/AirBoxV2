-- AirBox V2 — stateful VOC/NOx gas-index algorithm state.
-- Some SEN66 units report raw SGP41 ticks (voc_raw / nox_raw) instead of the
-- computed voc_index / nox_index. The middleware converts raw -> index at ingest
-- using the Sensirion Gas Index Algorithm (see ro.alacrity.airbox.middleware.gasindex),
-- whose per-(device, signal) memory is the algorithm State serialized to JSON.
-- One row per (device, VOC|NOX). The row is SELECT ... FOR UPDATE-locked, the State
-- deserialized, advanced by process(), and UPSERTed back within the same transaction
-- as the reading insert; updated_at drives the dt (elapsed seconds) of the next sample.
CREATE TABLE IF NOT EXISTS airbox_gas_algorithm (
    device_id   VARCHAR(100) NOT NULL,
    algo_type   VARCHAR(3)   NOT NULL CHECK (algo_type IN ('VOC', 'NOX')),
    state       TEXT         NOT NULL,
    updated_at  TIMESTAMPTZ  NOT NULL,
    PRIMARY KEY (device_id, algo_type)
);
