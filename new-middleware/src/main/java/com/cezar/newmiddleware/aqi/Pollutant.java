package com.cezar.newmiddleware.aqi;

import java.util.List;

/**
 * The six criteria pollutants for which the US EPA publishes an AQI breakpoint table,
 * together with that table and the concentration reporting precision (number of decimal
 * places the raw concentration is TRUNCATED to before interpolation).
 *
 * <p>Only {@link #PM25}, {@link #PM10} and {@link #NO2} are wired to AirBox sensor fields
 * today (see {@code SensorReadingService}); {@link #O3}, {@link #SO2} and {@link #CO} are
 * present so the registry covers the full EPA set and is trivial to extend when/if those
 * measurements are added — they simply never receive a concentration at runtime.
 *
 * <h2>Breakpoint tables</h2>
 * Values are the current US EPA AQI breakpoints (40 CFR Part 58, Appendix G, Table 2),
 * incorporating the 2024-05-06 PM2.5 revision (Fed. Reg. Vol. 89, No. 88). Per the task
 * specification the top two "Hazardous" rows of every table are collapsed into a single
 * band whose sub-index runs 301–500; concentrations above the highest {@code cHi} clamp to
 * the top sub-index (500) — see {@link AqiCalculator}.
 *
 * <ul>
 *   <li><b>PM2.5</b> (µg/m³, 24-hour mean, truncate to 0.1):
 *       0.0–9.0→0–50, 9.1–35.4→51–100, 35.5–55.4→101–150, 55.5–125.4→151–200,
 *       125.5–225.4→201–300, 225.5–325.4→301–500.</li>
 *   <li><b>PM10</b> (µg/m³, 24-hour mean, truncate to integer):
 *       0–54→0–50, 55–154→51–100, 155–254→101–150, 255–354→151–200,
 *       355–424→201–300, 425–604→301–500.</li>
 *   <li><b>NO2</b> (ppb, 1-hour mean, truncate to integer):
 *       0–53→0–50, 54–100→51–100, 101–360→101–150, 361–649→151–200,
 *       650–1249→201–300, 1250–2049→301–500.</li>
 *   <li><b>O3</b> (ppm, 8-hour mean, truncate to 0.001) — reference only, not fed:
 *       0.000–0.054→0–50, 0.055–0.070→51–100, 0.071–0.085→101–150,
 *       0.086–0.105→151–200, 0.106–0.200→201–300.</li>
 *   <li><b>SO2</b> (ppb, 1-hour mean, truncate to integer) — reference only, not fed:
 *       0–35→0–50, 36–75→51–100, 76–185→101–150, 186–304→151–200,
 *       305–604→201–300, 605–1004→301–500.</li>
 *   <li><b>CO</b> (ppm, 8-hour mean, truncate to 0.1) — reference only, not fed:
 *       0.0–4.4→0–50, 4.5–9.4→51–100, 9.5–12.4→101–150, 12.5–15.4→151–200,
 *       15.5–30.4→201–300, 30.5–50.4→301–500.</li>
 * </ul>
 */
public enum Pollutant {

    PM25("pm25", true, 1, List.of(
            new Breakpoint(0.0, 9.0, 0, 50),
            new Breakpoint(9.1, 35.4, 51, 100),
            new Breakpoint(35.5, 55.4, 101, 150),
            new Breakpoint(55.5, 125.4, 151, 200),
            new Breakpoint(125.5, 225.4, 201, 300),
            new Breakpoint(225.5, 325.4, 301, 500))),

    PM10("pm10", true, 0, List.of(
            new Breakpoint(0, 54, 0, 50),
            new Breakpoint(55, 154, 51, 100),
            new Breakpoint(155, 254, 101, 150),
            new Breakpoint(255, 354, 151, 200),
            new Breakpoint(355, 424, 201, 300),
            new Breakpoint(425, 604, 301, 500))),

    NO2("no2", false, 0, List.of(
            new Breakpoint(0, 53, 0, 50),
            new Breakpoint(54, 100, 51, 100),
            new Breakpoint(101, 360, 101, 150),
            new Breakpoint(361, 649, 151, 200),
            new Breakpoint(650, 1249, 201, 300),
            new Breakpoint(1250, 2049, 301, 500))),

    O3("o3", false, 3, List.of(
            new Breakpoint(0.000, 0.054, 0, 50),
            new Breakpoint(0.055, 0.070, 51, 100),
            new Breakpoint(0.071, 0.085, 101, 150),
            new Breakpoint(0.086, 0.105, 151, 200),
            new Breakpoint(0.106, 0.200, 201, 300))),

    SO2("so2", false, 0, List.of(
            new Breakpoint(0, 35, 0, 50),
            new Breakpoint(36, 75, 51, 100),
            new Breakpoint(76, 185, 101, 150),
            new Breakpoint(186, 304, 151, 200),
            new Breakpoint(305, 604, 201, 300),
            new Breakpoint(605, 1004, 301, 500))),

    CO("co", false, 1, List.of(
            new Breakpoint(0.0, 4.4, 0, 50),
            new Breakpoint(4.5, 9.4, 51, 100),
            new Breakpoint(9.5, 12.4, 101, 150),
            new Breakpoint(12.5, 15.4, 151, 200),
            new Breakpoint(15.5, 30.4, 201, 300),
            new Breakpoint(30.5, 50.4, 301, 500)));

    private final String key;
    private final boolean particulate;
    private final int decimals;
    private final List<Breakpoint> breakpoints;

    Pollutant(String key, boolean particulate, int decimals, List<Breakpoint> breakpoints) {
        this.key = key;
        this.particulate = particulate;
        this.decimals = decimals;
        this.breakpoints = breakpoints;
    }

    /** Stable, lower-case identifier stored in {@code airbox_readings.aqi_pollutant}. */
    public String key() {
        return key;
    }

    /** True for PM2.5 / PM10 — the pollutants the EPA eligibility rule requires at least one of. */
    public boolean isParticulate() {
        return particulate;
    }

    /** Number of decimal places the raw concentration is truncated to before interpolation. */
    public int decimals() {
        return decimals;
    }

    public List<Breakpoint> breakpoints() {
        return breakpoints;
    }
}
