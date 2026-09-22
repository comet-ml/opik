package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Covers {@link JsonRowValues#putDoubleExact}, which decides how every {@code Float64} column on the
 * JSONEachRow write paths is spelled. The three branches -- exact expansion, signed zero, non-finite --
 * each exist for a different reason, so each is pinned separately.
 */
class JsonRowValuesTest {

    @ParameterizedTest
    @ValueSource(doubles = {1.2583709557071319E9, 12.5, -3.25E-7, Double.MIN_VALUE, Double.MAX_VALUE,
            1.0E-300, 0.1})
    @DisplayName("a finite double is written as its exact expansion and reads back bit-for-bit")
    void finiteDoublesAreExact(double value) {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putDoubleExact(node, "ttft", value);

        assertThat(node.get("ttft").asText()).isEqualTo(new BigDecimal(value).toString());
        // Through the serialized text, which is what reaches ClickHouse.
        assertThat(JsonUtils.getJsonNodeFromString(node.toString()).get("ttft").asDouble()).isEqualTo(value);
    }

    @Test
    @DisplayName("negative zero keeps its sign, which BigDecimal would have discarded")
    void negativeZeroKeepsItsSign() {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putDoubleExact(node, "ttft", -0.0);

        // new BigDecimal(-0.0) is 0 -- BigDecimal has no signed zero -- so routing -0.0 through it would
        // silently store +0.0. Asserted via the reciprocal, since -0.0 == 0.0 is true.
        double readBack = JsonUtils.getJsonNodeFromString(node.toString()).get("ttft").asDouble();
        assertThat(1 / readBack).isNegative();
        assertThat(node.get("ttft").asText()).isEqualTo("-0.0");
    }

    @Test
    @DisplayName("positive zero stays positive")
    void positiveZeroStaysPositive() {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putDoubleExact(node, "ttft", 0.0);

        assertThat(1 / JsonUtils.getJsonNodeFromString(node.toString()).get("ttft").asDouble()).isPositive();
    }

    @Test
    @DisplayName("the non-finite values keep the quoted form ClickHouse is configured to accept")
    void nonFiniteValuesAreQuoted() {
        var nan = JsonUtils.createObjectNode();
        var positiveInfinity = JsonUtils.createObjectNode();
        var negativeInfinity = JsonUtils.createObjectNode();

        JsonRowValues.putDoubleExact(nan, "ttft", Double.NaN);
        JsonRowValues.putDoubleExact(positiveInfinity, "ttft", Double.POSITIVE_INFINITY);
        JsonRowValues.putDoubleExact(negativeInfinity, "ttft", Double.NEGATIVE_INFINITY);

        // Jackson quotes non-finite numbers (QUOTE_NON_NUMERIC_NUMBERS, on by default), which is the
        // form input_format_json_read_numbers_as_strings exists to accept. BigDecimal has no
        // representation for any of the three, so they must not take the exact-expansion branch.
        assertThat(nan.toString()).contains("\"ttft\":\"NaN\"");
        assertThat(positiveInfinity.toString()).contains("\"ttft\":\"Infinity\"");
        assertThat(negativeInfinity.toString()).contains("\"ttft\":\"-Infinity\"");
    }

    @Test
    @DisplayName("a blank column name is rejected rather than producing an unnamed cell")
    void blankFieldIsRejected() {
        var node = JsonUtils.createObjectNode();

        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> JsonRowValues.putDoubleExact(node, "  ", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
