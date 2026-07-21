package com.cezar.newmiddleware.aqi;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Optional;

/**
 * Pure, side-effect-free computation of an EPA-style Air Quality Index from already-averaged
 * pollutant concentrations. No Spring, no I/O — fully unit-testable in isolation.
 *
 * <h2>Algorithm (per the task specification / EPA 40 CFR Part 58, Appendix G)</h2>
 * <ol>
 *   <li>For each supplied pollutant, TRUNCATE (not round) its concentration to the pollutant's
 *       reporting precision ({@link Pollutant#decimals()}).</li>
 *   <li>Locate the breakpoint band the truncated concentration falls in and linearly
 *       interpolate the sub-index {@code Ip = (Ihi-Ilo)/(Chi-Clo)*(Cp-Clo)+Ilo}. Concentrations
 *       above the top band clamp to that band's high index (500); a concentration at/below 0
 *       yields sub-index 0.</li>
 *   <li>The final AQI is the MAXIMUM sub-index across pollutants, rounded to the nearest integer
 *       (round-half-up). The reported dominant pollutant is the one owning that maximum.</li>
 * </ol>
 *
 * <h2>Eligibility</h2>
 * An AQI is produced only when at least THREE pollutant sub-indices are computable AND at least
 * one of those is PM2.5 or PM10 ("airboxes feeding enough data"). Otherwise {@link #compute}
 * returns {@link Optional#empty()} and the caller stores NULL.
 *
 * <p>Sub-indices are compared as real (un-rounded) numbers so that the dominant-pollutant choice
 * and the max selection are exact; only the single final value is rounded. On an exact tie the
 * pollutant earlier in {@link Pollutant}'s declaration order (PM2.5 &lt; PM10 &lt; NO2 &lt; …) wins.
 */
public final class AqiCalculator {

    /** Minimum number of computable pollutant sub-indices for an AQI to be reported. */
    private static final int MIN_POLLUTANTS = 3;

    private AqiCalculator() {}

    /** Result of a successful AQI computation. */
    public record AqiResult(int aqi, Pollutant dominant) {}

    /**
     * Compute the AQI from a map of pollutant → trailing-mean concentration. Only pollutants
     * present in the map (non-null concentration) are considered computable. Iteration order
     * follows {@link Pollutant}'s natural (declaration) order for deterministic tie-breaking, so
     * an {@link java.util.EnumMap} is the recommended input.
     *
     * @return the AQI and its dominant pollutant, or empty if the reading is not eligible.
     */
    public static Optional<AqiResult> compute(Map<Pollutant, Double> concentrations) {
        boolean hasParticulate = false;
        int computable = 0;
        double maxSubIndex = Double.NEGATIVE_INFINITY;
        Pollutant dominant = null;

        // Iterate in enum declaration order (not map order) for deterministic tie-breaking.
        for (Pollutant p : Pollutant.values()) {
            Double concentration = concentrations.get(p);
            if (concentration == null) {
                continue;
            }
            computable++;
            hasParticulate |= p.isParticulate();

            double subIndex = subIndex(p, concentration);
            if (subIndex > maxSubIndex) {
                maxSubIndex = subIndex;
                dominant = p;
            }
        }

        if (computable < MIN_POLLUTANTS || !hasParticulate) {
            return Optional.empty();
        }

        int aqi = (int) Math.round(maxSubIndex);
        return Optional.of(new AqiResult(aqi, dominant));
    }

    /**
     * Sub-index for one pollutant at a given concentration: truncate to the pollutant's
     * precision, then interpolate within (or clamp to) its breakpoint table.
     */
    static double subIndex(Pollutant pollutant, double concentration) {
        double cTrunc = truncate(concentration, pollutant.decimals());
        var table = pollutant.breakpoints();

        // Below the scale (defensive — concentrations are non-negative in practice): index 0.
        if (cTrunc <= table.get(0).cLo()) {
            return table.get(0).iLo();
        }
        for (Breakpoint bp : table) {
            if (cTrunc <= bp.cHi()) {
                return bp.interpolate(cTrunc);
            }
        }
        // Above the top breakpoint: clamp to the top sub-index (500).
        return table.get(table.size() - 1).iHi();
    }

    /**
     * Truncate (round toward zero / floor for the non-negative concentrations we handle) to
     * {@code decimals} decimal places. BigDecimal is used so the truncation is exact — e.g.
     * 9.06 → 9.0 at 1 dp, not 9.0999… artefacts from binary floating point.
     */
    static double truncate(double value, int decimals) {
        return BigDecimal.valueOf(value).setScale(decimals, RoundingMode.FLOOR).doubleValue();
    }
}
