package com.cezar.newmiddleware.aqi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/**
 * Unit tests for the pure {@link AqiCalculator}: breakpoint interpolation, truncation,
 * above-scale clamping, the 3-pollutant/at-least-one-PM eligibility rule, and
 * max-selection with dominant-pollutant reporting.
 */
@DisplayName("AqiCalculator")
class AqiCalculatorTest {

    // ------------------------------------------------------------------
    // Sub-index interpolation, truncation and clamping
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("PM2.5 sub-index")
    class Pm25SubIndex {

        @Test
        @DisplayName("9.0 sits at the top of the Good band -> exactly 50")
        void goodBandTop() {
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 9.0)).isEqualTo(50.0);
        }

        @Test
        @DisplayName("9.1 sits at the bottom of the Moderate band -> exactly 51")
        void moderateBandBottom() {
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 9.1)).isEqualTo(51.0);
        }

        @Test
        @DisplayName("9.06 truncates to 9.0 (not rounds to 9.1) -> stays 50")
        void truncatesDownIntoGoodBand() {
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 9.06)).isEqualTo(50.0);
        }

        @Test
        @DisplayName("a mid-band value interpolates linearly")
        void midBandInterpolation() {
            // 37.5 -> band 35.5-55.4 / 101-150: (150-101)/(55.4-35.5)*(37.5-35.5)+101
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 37.5))
                    .isCloseTo(105.9246, within(1e-3));
        }

        @Test
        @DisplayName("above the top breakpoint clamps to 500")
        void aboveScaleClamps() {
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 400.0)).isEqualTo(500.0);
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 325.4)).isEqualTo(500.0);
        }

        @Test
        @DisplayName("zero concentration -> sub-index 0")
        void zero() {
            assertThat(AqiCalculator.subIndex(Pollutant.PM25, 0.0)).isEqualTo(0.0);
        }
    }

    @Test
    @DisplayName("PM10 truncates to an integer before interpolation")
    void pm10TruncatesToInteger() {
        // 154.9 truncates to 154 -> top of 55-154 / 51-100 band -> exactly 100
        assertThat(AqiCalculator.subIndex(Pollutant.PM10, 154.9)).isEqualTo(100.0);
        // 155 -> bottom of next band -> exactly 101
        assertThat(AqiCalculator.subIndex(Pollutant.PM10, 155.0)).isEqualTo(101.0);
    }

    @Test
    @DisplayName("NO2 (proxy) interpolates over its ppb band")
    void no2Interpolation() {
        // 500 -> band 361-649 / 151-200: (200-151)/(649-361)*(500-361)+151
        assertThat(AqiCalculator.subIndex(Pollutant.NO2, 500.0))
                .isCloseTo(174.649, within(1e-3));
    }

    @Test
    @DisplayName("truncation is FLOOR to the pollutant precision, not rounding")
    void truncationIsFloor() {
        assertThat(AqiCalculator.truncate(9.06, 1)).isEqualTo(9.0);
        assertThat(AqiCalculator.truncate(9.19, 1)).isEqualTo(9.1);
        assertThat(AqiCalculator.truncate(154.9, 0)).isEqualTo(154.0);
        assertThat(AqiCalculator.truncate(53.99, 0)).isEqualTo(53.0);
    }

    // ------------------------------------------------------------------
    // Eligibility matrix
    // ------------------------------------------------------------------

    @Nested
    @DisplayName("eligibility")
    class Eligibility {

        @Test
        @DisplayName("only 2 pollutants (both PM) -> not eligible -> empty")
        void twoPollutantsIneligible() {
            assertThat(AqiCalculator.compute(conc(
                    Pollutant.PM25, 37.5,
                    Pollutant.PM10, 60.0)))
                    .isEmpty();
        }

        @Test
        @DisplayName("3 pollutants but none PM (NO2+SO2+CO) -> not eligible -> empty")
        void threeNonPmIneligible() {
            assertThat(AqiCalculator.compute(conc(
                    Pollutant.NO2, 500.0,
                    Pollutant.SO2, 200.0,
                    Pollutant.CO, 8.0)))
                    .isEmpty();
        }

        @Test
        @DisplayName("3 pollutants with a PM member -> eligible -> present")
        void threeWithPmEligible() {
            assertThat(AqiCalculator.compute(conc(
                    Pollutant.PM25, 37.5,
                    Pollutant.PM10, 60.0,
                    Pollutant.NO2, 500.0)))
                    .isPresent();
        }

        @Test
        @DisplayName("empty input -> empty")
        void emptyInput() {
            assertThat(AqiCalculator.compute(new EnumMap<>(Pollutant.class))).isEmpty();
        }
    }

    // ------------------------------------------------------------------
    // Max selection + dominant pollutant
    // ------------------------------------------------------------------

    @Test
    @DisplayName("final AQI is the max sub-index (rounded) and reports the dominant pollutant")
    void maxSelectionAndDominant() {
        // pm25 37.5 -> 105.9, pm10 60 -> 53.5, no2 500 -> 174.6  => max is NO2 -> 175
        Optional<AqiCalculator.AqiResult> result = AqiCalculator.compute(conc(
                Pollutant.PM25, 37.5,
                Pollutant.PM10, 60.0,
                Pollutant.NO2, 500.0));

        assertThat(result).isPresent();
        assertThat(result.get().aqi()).isEqualTo(175);
        assertThat(result.get().dominant()).isEqualTo(Pollutant.NO2);
    }

    @Test
    @DisplayName("a dominant PM2.5 wins over lower NO2/PM10")
    void particulateCanDominate() {
        // pm25 200 -> band 125.5-225.4 / 201-300: (300-201)/(225.4-125.5)*(200-125.5)+201
        //          ~ 274.8; pm10 60 -> 53.5; no2 50 -> ~47.2  => PM2.5 dominates
        Optional<AqiCalculator.AqiResult> result = AqiCalculator.compute(conc(
                Pollutant.PM25, 200.0,
                Pollutant.PM10, 60.0,
                Pollutant.NO2, 50.0));

        assertThat(result).isPresent();
        assertThat(result.get().aqi()).isEqualTo(275);
        assertThat(result.get().dominant()).isEqualTo(Pollutant.PM25);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static Map<Pollutant, Double> conc(Object... pairs) {
        Map<Pollutant, Double> map = new EnumMap<>(Pollutant.class);
        for (int i = 0; i < pairs.length; i += 2) {
            map.put((Pollutant) pairs[i], (Double) pairs[i + 1]);
        }
        return map;
    }
}
