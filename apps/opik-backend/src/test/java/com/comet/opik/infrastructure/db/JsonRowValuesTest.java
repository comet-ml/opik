package com.comet.opik.infrastructure.db;

import com.comet.opik.utils.JsonUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Covers {@link JsonRowValues#putDouble}, which decides how every {@code Float64} column on the
 * JSONEachRow write paths is spelled. The contract is parity with the R2DBC driver's own rendering, so
 * the finite case is pinned against {@code String.valueOf} rather than against a hand-picked literal.
 */
class JsonRowValuesTest {

    @ParameterizedTest
    @ValueSource(doubles = {1.2583709557071319E9, 12.5, -3.25E-7, Double.MIN_VALUE, Double.MAX_VALUE,
            1.0E-300, 0.1})
    @DisplayName("a finite double is written as its exact expansion and reads back bit-for-bit")
    void finiteDoublesAreExact(double value) {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putDouble(node, "ttft", value);

        // Byte-identical to what the R2DBC driver renders (ClickHouseDoubleValue#toSqlExpression is
        // String.valueOf), which is the parity this write path has to hold.
        assertThat(node.get("ttft").asText()).isEqualTo(String.valueOf(value));
        // And it round-trips through the serialized text, which is what reaches ClickHouse.
        assertThat(JsonUtils.getJsonNodeFromString(node.toString()).get("ttft").asDouble()).isEqualTo(value);
    }

    @Test
    @DisplayName("negative zero keeps its sign")
    void negativeZeroKeepsItsSign() {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putDouble(node, "ttft", -0.0);

        // Asserted via the reciprocal, since -0.0 == 0.0 is true. Routing this through BigDecimal would
        // lose the sign -- BigDecimal has no signed zero -- which is one reason the plain form wins.
        double readBack = JsonUtils.getJsonNodeFromString(node.toString()).get("ttft").asDouble();
        assertThat(1 / readBack).isNegative();
        assertThat(node.get("ttft").asText()).isEqualTo("-0.0");
    }

    @Test
    @DisplayName("positive zero stays positive")
    void positiveZeroStaysPositive() {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putDouble(node, "ttft", 0.0);

        assertThat(1 / JsonUtils.getJsonNodeFromString(node.toString()).get("ttft").asDouble()).isPositive();
    }

    @Test
    @DisplayName("the non-finite values keep the quoted form ClickHouse is configured to accept")
    void nonFiniteValuesAreQuoted() {
        var nan = JsonUtils.createObjectNode();
        var positiveInfinity = JsonUtils.createObjectNode();
        var negativeInfinity = JsonUtils.createObjectNode();

        JsonRowValues.putDouble(nan, "ttft", Double.NaN);
        JsonRowValues.putDouble(positiveInfinity, "ttft", Double.POSITIVE_INFINITY);
        JsonRowValues.putDouble(negativeInfinity, "ttft", Double.NEGATIVE_INFINITY);

        // Jackson quotes non-finite numbers (QUOTE_NON_NUMERIC_NUMBERS, on by default), which is the
        // form input_format_json_read_numbers_as_strings exists to accept.
        assertThat(nan.toString()).contains("\"ttft\":\"NaN\"");
        assertThat(positiveInfinity.toString()).contains("\"ttft\":\"Infinity\"");
        assertThat(negativeInfinity.toString()).contains("\"ttft\":\"-Infinity\"");
    }

    @Test
    @DisplayName("a blank column name is rejected rather than producing an unnamed cell")
    void blankFieldIsRejected() {
        var node = JsonUtils.createObjectNode();

        assertThatThrownBy(() -> JsonRowValues.putDouble(node, "  ", 1.0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
