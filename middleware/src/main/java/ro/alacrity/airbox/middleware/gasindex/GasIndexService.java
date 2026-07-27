package ro.alacrity.airbox.middleware.gasindex;

import ro.alacrity.airbox.middleware.configs.JacksonConfig;
import ro.alacrity.airbox.middleware.gasindex.GasIndexAlgorithm.State;
import ro.alacrity.airbox.middleware.gasindex.GasIndexAlgorithm.Type;
import ro.alacrity.airbox.middleware.repository.GasAlgorithmStateRepository;
import ro.alacrity.airbox.middleware.repository.GasAlgorithmStateRepository.StateRow;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import tools.jackson.databind.json.JsonMapper;

import java.time.OffsetDateTime;
import java.util.Optional;

/**
 * Converts raw SGP41 ticks into a gas index while keeping the algorithm's
 * per-(device, signal) State in the database.
 *
 * <p>Each call locks the state row ({@code FOR UPDATE}), deserializes it (or
 * starts a fresh State when this is the device's first raw sample), advances it
 * by exactly one {@link GasIndexAlgorithm#process} step over the real elapsed
 * time since the previous sample, then serializes and UPSERTs it back. Callers
 * MUST invoke this inside the same transaction as the reading insert so the
 * lock, the state advance and the reading are one atomic unit.
 */
@Service
public class GasIndexService {

    private final GasAlgorithmStateRepository stateRepository;
    private final JsonMapper jsonMapper;

    public GasIndexService(GasAlgorithmStateRepository stateRepository,
                           @Qualifier(JacksonConfig.GAS_STATE_MAPPER) JsonMapper jsonMapper) {
        this.stateRepository = stateRepository;
        this.jsonMapper = jsonMapper;
    }

    /**
     * Advance the stored state for {@code (deviceId, type)} by one sample and
     * return the resulting gas index (0 during the algorithm's 45s startup
     * blackout, otherwise 1..500).
     *
     * @param deviceId  device whose state to advance
     * @param type      VOC or NOX — must match the raw signal supplied
     * @param rawTicks  raw SGP41 ticks (already validated to 0..65535)
     * @param now       the ingest instant; stored as the row's updated_at and
     *                  used as the dt anchor for the next sample
     */
    public int advanceAndComputeIndex(String deviceId, Type type, int rawTicks, OffsetDateTime now) {
        Optional<StateRow> existing = stateRepository.lockRow(deviceId, type);

        State state;
        float dtSeconds;
        if (existing.isPresent()) {
            state = jsonMapper.readValue(existing.get().state(), State.class);
            // Real gap since the previous sample for this state; process() clamps to [1s, 7d].
            dtSeconds = (now.toInstant().toEpochMilli()
                    - existing.get().updatedAt().toInstant().toEpochMilli()) / 1000f;
        } else {
            state = (type == Type.NOX) ? State.forNox() : State.forVoc();
            // Fresh state: no previous sample. 0 clamps to MIN_DT_SECONDS inside process().
            dtSeconds = 0f;
        }

        int index = GasIndexAlgorithm.process(rawTicks, dtSeconds, state);

        String serialized = jsonMapper.writeValueAsString(state);
        if (existing.isPresent()) {
            stateRepository.update(deviceId, type, serialized, now);
        } else {
            stateRepository.insert(deviceId, type, serialized, now);
        }
        return index;
    }
}
