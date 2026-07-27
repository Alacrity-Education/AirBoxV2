/*
 * Sensirion Gas Index Algorithm — single-entry-point Java port.
 *
 * Derived from https://github.com/Sensirion/gas-index-algorithm
 * Copyright (c) 2021, Sensirion AG. BSD 3-Clause License.
 *
 *     int index = GasIndexAlgorithm.process(srawTicks, dtSeconds, state);
 *
 * The whole algorithm is one static function. All memory lives in the mutable
 * State object, which is updated in place. Load State from the DB, call
 * process(), write State back — no instances to keep alive between samples.
 *
 * Converts SGP41 raw ticks into a 1..500 gas index. On a SEN6x the ticks from
 * "Read Measured Raw Values" (0x0405) are already RH/T compensated on-module
 * and feed straight in.
 *
 * dtSeconds is the real gap since the previous sample for this State. Both
 * variance gammas approach GAMMA_SCALING (64) asymptotically from below as dt
 * grows, so the sqrt in the std update can never take a negative radicand,
 * for any dt. Values are clamped to [MIN_DT_SECONDS, MAX_DT_SECONDS]; NaN and
 * non-positive input collapse to MIN_DT_SECONDS.
 */
package ro.alacrity.airbox.middleware.gasindex;

public final class GasIndexAlgorithm {

    private GasIndexAlgorithm() {
    }

    public enum Type { VOC, NOX }

    public static final float MIN_DT_SECONDS = 1f;
    public static final float MAX_DT_SECONDS = 7f * 86400f;

    // ---- constants, verbatim from sensirion_gas_index_algorithm.h ----
    private static final float INITIAL_BLACKOUT = 45f;
    private static final float INDEX_GAIN = 230f;
    private static final float SRAW_STD_INITIAL = 50f;
    private static final float SRAW_STD_BONUS_VOC = 220f;
    private static final float SRAW_STD_NOX = 2000f;
    private static final float TAU_MEAN_HOURS = 12f;
    private static final float TAU_VARIANCE_HOURS = 12f;
    private static final float TAU_INITIAL_MEAN_VOC = 20f;
    private static final float TAU_INITIAL_MEAN_NOX = 1200f;
    private static final float INIT_DURATION_MEAN_VOC = 3600f * 0.75f;
    private static final float INIT_DURATION_MEAN_NOX = 3600f * 4.75f;
    private static final float INIT_TRANSITION_MEAN = 0.01f;
    private static final float TAU_INITIAL_VARIANCE = 2500f;
    private static final float INIT_DURATION_VARIANCE_VOC = 3600f * 1.45f;
    private static final float INIT_DURATION_VARIANCE_NOX = 3600f * 5.70f;
    private static final float INIT_TRANSITION_VARIANCE = 0.01f;
    private static final float GATING_THRESHOLD_VOC = 340f;
    private static final float GATING_THRESHOLD_NOX = 30f;
    private static final float GATING_THRESHOLD_INITIAL = 510f;
    private static final float GATING_THRESHOLD_TRANSITION = 0.09f;
    private static final float GATING_VOC_MAX_DURATION_MINUTES = 60f * 3f;
    private static final float GATING_NOX_MAX_DURATION_MINUTES = 60f * 12f;
    private static final float GATING_MAX_RATIO = 0.3f;
    private static final float SIGMOID_L = 500f;
    private static final float SIGMOID_K_VOC = -0.0065f;
    private static final float SIGMOID_X0_VOC = 213f;
    private static final float SIGMOID_K_NOX = -0.0101f;
    private static final float SIGMOID_X0_NOX = 614f;
    private static final float VOC_INDEX_OFFSET_DEFAULT = 100f;
    private static final float NOX_INDEX_OFFSET_DEFAULT = 1f;
    private static final float LP_TAU_FAST = 20f;
    private static final float LP_TAU_SLOW = 500f;
    private static final float LP_ALPHA = -0.2f;
    private static final int VOC_SRAW_MINIMUM = 20000;
    private static final int NOX_SRAW_MINIMUM = 10000;
    private static final float GAMMA_SCALING = 64f;
    private static final float ADDITIONAL_GAMMA_MEAN_SCALING = 8f;
    private static final float FIX16_MAX = 32767f;

    /**
     * Everything the algorithm remembers. Serialize the whole object per
     * (device, signal) and restore it before the next call — it is a plain
     * mutable POJO with public fields so any JSON mapper handles it directly.
     *
     * The first seven fields are configuration and are never modified by
     * process(); the rest is accumulated state. Restoring a state under
     * different configuration is silently wrong, so persist a fingerprint of
     * the config alongside it and reset() rather than restore on a mismatch.
     *
     * Note there is no stored sampling interval: every dt-dependent
     * coefficient is recomputed inside process() from the dt you pass.
     */
    public static final class State {

        // ---- configuration ----
        public Type type;
        public float indexOffset;
        public float tauMeanHours;
        public float tauVarianceHours;
        public float gatingMaxDurationMinutes;
        public float srawStdInitial;
        public float indexGain;

        // ---- accumulated state ----
        public float uptime;
        public float sraw;
        public float gasIndex;
        public boolean mveInitialized;
        public float mveMean;
        public float mveSrawOffset;
        public float mveStd;
        public float mveUptimeGamma;
        public float mveUptimeGating;
        public float mveGatingDurationMinutes;
        public float moxSrawStd;
        public float moxSrawMean;
        public boolean lpInitialized;
        public float lpX1;
        public float lpX2;
        public float lpX3;

        /** No-arg constructor for deserialization. Prefer the factories. */
        public State() {
        }

        public static State forVoc() {
            return create(Type.VOC);
        }

        public static State forNox() {
            return create(Type.NOX);
        }

        private static State create(Type t) {
            State s = new State();
            s.type = t;
            s.indexOffset = (t == Type.NOX) ? NOX_INDEX_OFFSET_DEFAULT : VOC_INDEX_OFFSET_DEFAULT;
            s.tauMeanHours = TAU_MEAN_HOURS;
            s.tauVarianceHours = TAU_VARIANCE_HOURS;
            s.gatingMaxDurationMinutes = (t == Type.NOX)
                    ? GATING_NOX_MAX_DURATION_MINUTES : GATING_VOC_MAX_DURATION_MINUTES;
            s.srawStdInitial = SRAW_STD_INITIAL;
            s.indexGain = INDEX_GAIN;
            s.reset();
            return s;
        }

        /** Clear all accumulated state, keeping configuration. */
        public void reset() {
            uptime = 0f;
            sraw = 0f;
            gasIndex = 0f;
            mveInitialized = false;
            mveMean = 0f;
            mveSrawOffset = 0f;
            mveStd = srawStdInitial;
            mveUptimeGamma = 0f;
            mveUptimeGating = 0f;
            mveGatingDurationMinutes = 0f;
            moxSrawStd = srawStdInitial;
            moxSrawMean = 0f;
            lpInitialized = false;
            lpX1 = 0f;
            lpX2 = 0f;
            lpX3 = 0f;
        }

        /**
         * Same six knobs and ranges as the SEN6x tuning commands. Like the
         * reference implementation, this resets accumulated state — call it
         * once at construction, not mid-stream.
         */
        public void setTuningParameters(int indexOffset, int learningTimeOffsetHours,
                                        int learningTimeGainHours, int gatingMaxDurationMinutes,
                                        int stdInitial, int gainFactor) {
            this.indexOffset = indexOffset;
            this.tauMeanHours = learningTimeOffsetHours;
            this.tauVarianceHours = learningTimeGainHours;
            this.gatingMaxDurationMinutes = gatingMaxDurationMinutes;
            this.srawStdInitial = stdInitial;
            this.indexGain = gainFactor;
            reset();
        }

        public State copy() {
            State s = new State();
            s.type = type;
            s.indexOffset = indexOffset;
            s.tauMeanHours = tauMeanHours;
            s.tauVarianceHours = tauVarianceHours;
            s.gatingMaxDurationMinutes = gatingMaxDurationMinutes;
            s.srawStdInitial = srawStdInitial;
            s.indexGain = indexGain;
            s.uptime = uptime;
            s.sraw = sraw;
            s.gasIndex = gasIndex;
            s.mveInitialized = mveInitialized;
            s.mveMean = mveMean;
            s.mveSrawOffset = mveSrawOffset;
            s.mveStd = mveStd;
            s.mveUptimeGamma = mveUptimeGamma;
            s.mveUptimeGating = mveUptimeGating;
            s.mveGatingDurationMinutes = mveGatingDurationMinutes;
            s.moxSrawStd = moxSrawStd;
            s.moxSrawMean = moxSrawMean;
            s.lpInitialized = lpInitialized;
            s.lpX1 = lpX1;
            s.lpX2 = lpX2;
            s.lpX3 = lpX3;
            return s;
        }
    }

    /**
     * Process one sample. Updates {@code state} in place and returns the gas
     * index (0 during the initial blackout, otherwise 1..500).
     *
     * @param srawTicks raw ticks for this signal — SRAW_VOC into a VOC state,
     *                  SRAW_NOx into a NOX state. Never cross them.
     * @param dtSeconds seconds since the previous sample for this state. For
     *                  the very first sample pass your nominal cycle time.
     * @param state     mutable memory, updated in place.
     */
    public static int process(int srawTicks, float dtSeconds, State state) {

        // ---- interval, clamped (the !(dt > MIN) form also rejects NaN) ----
        float dt = dtSeconds;
        if (!(dt > MIN_DT_SECONDS)) {
            dt = MIN_DT_SECONDS;
        } else if (dt > MAX_DT_SECONDS) {
            dt = MAX_DT_SECONDS;
        }

        // ---- initial blackout: consume the sample, emit nothing ----
        if (state.uptime <= INITIAL_BLACKOUT) {
            state.uptime = state.uptime + dt;
            return (int) (state.gasIndex + 0.5f);
        }

        final boolean nox = state.type == Type.NOX;
        final int srawMinimum = nox ? NOX_SRAW_MINIMUM : VOC_SRAW_MINIMUM;
        final float initDurationMean = nox ? INIT_DURATION_MEAN_NOX : INIT_DURATION_MEAN_VOC;
        final float initDurationVariance =
                nox ? INIT_DURATION_VARIANCE_NOX : INIT_DURATION_VARIANCE_VOC;
        final float gatingThreshold = nox ? GATING_THRESHOLD_NOX : GATING_THRESHOLD_VOC;
        final float tauInitialMean = nox ? TAU_INITIAL_MEAN_NOX : TAU_INITIAL_MEAN_VOC;

        // ---- dt-dependent coefficients, first-order dt/(tau+dt) form ----
        final float gammaMeanSteady =
                ((ADDITIONAL_GAMMA_MEAN_SCALING * GAMMA_SCALING) * (dt / 3600f))
                        / (state.tauMeanHours + (dt / 3600f));
        final float gammaVarSteady = (GAMMA_SCALING * (dt / 3600f))
                / (state.tauVarianceHours + (dt / 3600f));
        final float gammaMeanInitial =
                ((ADDITIONAL_GAMMA_MEAN_SCALING * GAMMA_SCALING) * dt) / (tauInitialMean + dt);
        final float gammaVarInitial = (GAMMA_SCALING * dt) / (TAU_INITIAL_VARIANCE + dt);
        final float lpA1 = dt / (LP_TAU_FAST + dt);
        final float lpA2 = dt / (LP_TAU_SLOW + dt);

        // ---- accept the raw value (out-of-range readings keep the previous) ----
        if (srawTicks > 0 && srawTicks < 65000) {
            if (srawTicks < srawMinimum + 1) {
                srawTicks = srawMinimum + 1;
            } else if (srawTicks > srawMinimum + 32767) {
                srawTicks = srawMinimum + 32767;
            }
            state.sraw = (float) (srawTicks - srawMinimum);
        }

        // ---- ticks -> index, using the mean/std learned through the PREVIOUS
        //      sample (moxSraw* are the lagged copies; this lag is load-bearing)
        if (!nox || state.mveInitialized) {
            float sample;
            if (nox) {
                sample = ((state.sraw - state.moxSrawMean) / SRAW_STD_NOX) * state.indexGain;
            } else {
                sample = ((state.sraw - state.moxSrawMean)
                        / (-1f * (state.moxSrawStd + SRAW_STD_BONUS_VOC))) * state.indexGain;
            }
            final float k = nox ? SIGMOID_K_NOX : SIGMOID_K_VOC;
            final float x0 = nox ? SIGMOID_X0_NOX : SIGMOID_X0_VOC;
            final float offsetDefault =
                    nox ? NOX_INDEX_OFFSET_DEFAULT : VOC_INDEX_OFFSET_DEFAULT;
            final float x = k * (sample - x0);
            if (x < -50f) {
                state.gasIndex = SIGMOID_L;
            } else if (x > 50f) {
                state.gasIndex = 0f;
            } else if (sample >= 0f) {
                final float shift = (offsetDefault == 1f)
                        ? ((500f / 499f) * (1f - state.indexOffset))
                        : ((SIGMOID_L - (5f * state.indexOffset)) / 4f);
                state.gasIndex = (float) (((SIGMOID_L + shift) / (1f + Math.exp(x))) - shift);
            } else {
                state.gasIndex = (float) ((state.indexOffset / offsetDefault)
                        * (SIGMOID_L / (1f + Math.exp(x))));
            }
        } else {
            state.gasIndex = state.indexOffset;
        }

        // ---- adaptive lowpass ----
        if (!state.lpInitialized) {
            state.lpX1 = state.gasIndex;
            state.lpX2 = state.gasIndex;
            state.lpX3 = state.gasIndex;
            state.lpInitialized = true;
        }
        state.lpX1 = ((1f - lpA1) * state.lpX1) + (lpA1 * state.gasIndex);
        state.lpX2 = ((1f - lpA2) * state.lpX2) + (lpA2 * state.gasIndex);
        float absDelta = state.lpX1 - state.lpX2;
        if (absDelta < 0f) {
            absDelta = -absDelta;
        }
        final float f1 = (float) Math.exp(LP_ALPHA * absDelta);
        final float tauA = ((LP_TAU_SLOW - LP_TAU_FAST) * f1) + LP_TAU_FAST;
        final float a3 = dt / (dt + tauA);
        state.lpX3 = ((1f - a3) * state.lpX3) + (a3 * state.gasIndex);
        state.gasIndex = state.lpX3;

        if (state.gasIndex < 0.5f) {
            state.gasIndex = 0.5f;
        }

        // ---- estimator update, AFTER the index is emitted (the one-sample lag) ----
        if (state.sraw > 0f) {
            if (!state.mveInitialized) {
                state.mveInitialized = true;
                state.mveSrawOffset = state.sraw;
                state.mveMean = 0f;
            } else {
                // keep the running mean near zero for float precision
                if (state.mveMean >= 100f || state.mveMean <= -100f) {
                    state.mveSrawOffset = state.mveSrawOffset + state.mveMean;
                    state.mveMean = 0f;
                }
                final float srawRelative = state.sraw - state.mveSrawOffset;

                // --- gamma / gating ---
                final float uptimeLimit = FIX16_MAX - dt;
                if (state.mveUptimeGamma < uptimeLimit) {
                    state.mveUptimeGamma = state.mveUptimeGamma + dt;
                }
                if (state.mveUptimeGating < uptimeLimit) {
                    state.mveUptimeGating = state.mveUptimeGating + dt;
                }

                final float sigGammaMean =
                        sigmoid(initDurationMean, INIT_TRANSITION_MEAN, state.mveUptimeGamma);
                final float gammaMean = gammaMeanSteady
                        + ((gammaMeanInitial - gammaMeanSteady) * sigGammaMean);
                final float gatingThresholdMean = gatingThreshold
                        + ((GATING_THRESHOLD_INITIAL - gatingThreshold)
                        * sigmoid(initDurationMean, INIT_TRANSITION_MEAN, state.mveUptimeGating));
                final float sigGatingMean = sigmoid(gatingThresholdMean,
                        GATING_THRESHOLD_TRANSITION, state.gasIndex);
                final float effGammaMean = sigGatingMean * gammaMean;

                final float sigGammaVar = sigmoid(initDurationVariance,
                        INIT_TRANSITION_VARIANCE, state.mveUptimeGamma);
                // difference of two sigmoids — not a typo for one of them
                final float gammaVar = gammaVarSteady
                        + ((gammaVarInitial - gammaVarSteady) * (sigGammaVar - sigGammaMean));
                final float gatingThresholdVar = gatingThreshold
                        + ((GATING_THRESHOLD_INITIAL - gatingThreshold)
                        * sigmoid(initDurationVariance, INIT_TRANSITION_VARIANCE,
                        state.mveUptimeGating));
                final float sigGatingVar = sigmoid(gatingThresholdVar,
                        GATING_THRESHOLD_TRANSITION, state.gasIndex);
                final float effGammaVar = sigGatingVar * gammaVar;

                state.mveGatingDurationMinutes = state.mveGatingDurationMinutes
                        + ((dt / 60f) * (((1f - sigGatingMean) * (1f + GATING_MAX_RATIO))
                        - GATING_MAX_RATIO));
                if (state.mveGatingDurationMinutes < 0f) {
                    state.mveGatingDurationMinutes = 0f;
                }
                if (state.mveGatingDurationMinutes > state.gatingMaxDurationMinutes) {
                    state.mveUptimeGating = 0f;
                }

                // --- std and mean ---
                final float deltaSgp = (srawRelative - state.mveMean) / GAMMA_SCALING;
                final float c = (deltaSgp < 0f)
                        ? (state.mveStd - deltaSgp) : (state.mveStd + deltaSgp);
                float additionalScaling = 1f;
                if (c > 1440f) {
                    additionalScaling = (c / 1440f) * (c / 1440f);
                }
                state.mveStd = (float) (
                        Math.sqrt(additionalScaling * (GAMMA_SCALING - effGammaVar))
                                * Math.sqrt((state.mveStd
                                * (state.mveStd / (GAMMA_SCALING * additionalScaling)))
                                + (((effGammaVar * deltaSgp) / additionalScaling) * deltaSgp)));
                state.mveMean = state.mveMean
                        + ((effGammaMean * deltaSgp) / ADDITIONAL_GAMMA_MEAN_SCALING);
            }
            state.moxSrawStd = state.mveStd;
            state.moxSrawMean = state.mveMean + state.mveSrawOffset;
        }

        return (int) (state.gasIndex + 0.5f);
    }

    private static float sigmoid(float x0, float k, float sample) {
        final float x = k * (sample - x0);
        if (x < -50f) {
            return 1f;
        } else if (x > 50f) {
            return 0f;
        }
        return (float) (1f / (1f + Math.exp(x)));
    }
}
