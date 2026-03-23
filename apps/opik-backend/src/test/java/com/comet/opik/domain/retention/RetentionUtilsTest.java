package com.comet.opik.domain.retention;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetentionUtils - Fraction range computation")
class RetentionUtilsTest {

    @Test
    @DisplayName("Every UUID prefix maps to exactly one fraction")
    void everyUuidPrefixMapsToExactlyOneFraction() {
        int totalFractions = 48;

        for (int fraction = 0; fraction < totalFractions; fraction++) {
            var range = RetentionUtils.computeWorkspaceRange(fraction, totalFractions);
            assertThat(range).hasSize(2);
            assertThat(range[0]).isNotNull();
            assertThat(range[1]).isNotNull();
            assertThat(range[0].compareTo(range[1])).isLessThan(0);
        }
    }

    @Test
    @DisplayName("Fractions cover entire UUID space without gaps")
    void fractionsCoverEntireUuidSpace() {
        int totalFractions = 48;

        var firstRange = RetentionUtils.computeWorkspaceRange(0, totalFractions);
        assertThat(firstRange[0]).isEqualTo("00000000");

        var lastRange = RetentionUtils.computeWorkspaceRange(totalFractions - 1, totalFractions);
        assertThat(lastRange[1]).isEqualTo("~");

        for (int i = 0; i < totalFractions - 1; i++) {
            var current = RetentionUtils.computeWorkspaceRange(i, totalFractions);
            var next = RetentionUtils.computeWorkspaceRange(i + 1, totalFractions);
            assertThat(current[1]).isEqualTo(next[0]);
        }
    }

    @Test
    @DisplayName("Single fraction covers everything")
    void singleFractionCoversEverything() {
        var range = RetentionUtils.computeWorkspaceRange(0, 1);
        assertThat(range[0]).isEqualTo("00000000");
        assertThat(range[1]).isEqualTo("~");
    }
}
