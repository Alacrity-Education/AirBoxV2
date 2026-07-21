package ro.alacrity.airbox.middleware.aqi;

/**
 * A single row of an EPA AQI breakpoint table: a concentration band
 * [{@code cLo}, {@code cHi}] (in the pollutant's own unit) mapping to an AQI
 * sub-index band [{@code iLo}, {@code iHi}].
 *
 * <p>The sub-index for a concentration {@code Cp} that falls inside this band is the
 * linear interpolation defined by EPA (40 CFR Part 58, Appendix G):
 * <pre>Ip = (iHi - iLo) / (cHi - cLo) * (Cp - cLo) + iLo</pre>
 * where {@code Cp} has first been truncated to the pollutant's reporting precision.
 */
public record Breakpoint(double cLo, double cHi, int iLo, int iHi) {

    /** Linear interpolation of the sub-index for a (already-truncated) concentration. */
    double interpolate(double cTrunc) {
        return (double) (iHi - iLo) / (cHi - cLo) * (cTrunc - cLo) + iLo;
    }
}
