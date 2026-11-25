package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.DataStreamWriter;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.api.resources.utils.PayloadCases;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

/**
 * Covers {@link JsonRowValues#putDouble}, which decides how every {@code Float64} column on the
 * JSONEachRow write paths is spelled. The contract is parity with the R2DBC driver's own rendering, so
 * the finite case is pinned against {@code String.valueOf} rather than against a hand-picked literal.
 * Also covers {@link JsonRowValues#putJsonWithSlim}, which embeds object and array payloads as raw JSON.
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

    /** The row as {@link JsonEachRowBulkInsert} sends it to ClickHouse, without its line separator. */
    private static String written(ObjectNode row) throws IOException {
        var client = JsonEachRowBulkInsertTest.clientReturning(1L);

        new JsonEachRowBulkInsert(client, JsonUtils.getMapper(), JsonEachRowBulkInsertTest.streamPayloads(true))
                .insert("spans", "log-comment", List.of(row), Function.identity())
                .block();

        var writer = ArgumentCaptor.forClass(DataStreamWriter.class);
        verify(client).insert(eq("spans"), writer.capture(), eq(ClickHouseFormat.JSONEachRow),
                any(InsertSettings.class));
        var body = new ByteArrayOutputStream();
        writer.getValue().onOutput(body);
        return body.toString(StandardCharsets.UTF_8).stripTrailing();
    }

    static Stream<JsonNode> containerPayloads() {
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        return Stream.of(
                JsonUtils.getJsonNodeFromString("{}"),
                JsonUtils.getJsonNodeFromString("[]"),
                JsonUtils.getJsonNodeFromString("{\"short\":\"%s\",\"n\":%d,\"none\":null}"
                        .formatted(PayloadCases.randomAlphanumeric(20), RandomUtils.secure().randomInt())),
                JsonUtils.getJsonNodeFromString("[{\"long\":\"%s\"},\"%s\",%d]"
                        .formatted(PayloadCases.randomAlphanumeric(max + 1), PayloadCases.randomAlphanumeric(max * 2),
                                RandomUtils.secure().randomInt())));
    }

    @ParameterizedTest
    @MethodSource("containerPayloads")
    @DisplayName("an object or array payload is embedded as raw JSON, with its slim copy")
    void containerPayloadIsEmbeddedRaw(JsonNode payload) throws IOException {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putJsonWithSlim(node, "input", "input_slim", payload);

        var columns = TruncationUtils.toJsonWithSlim(payload);
        assertThat(written(node))
                .isEqualTo("{\"input\":%s,\"input_slim\":%s}".formatted(columns.json(), columns.slim()));
    }

    static Stream<JsonNode> deepPayloads() {
        return Stream.of(
                PayloadCases.nestedArrays(StreamWriteConstraints.DEFAULT_MAX_DEPTH - 1),
                // As deep as node.toString() can serialize: the row around it goes one level past the default limit.
                PayloadCases.nestedArrays(StreamWriteConstraints.DEFAULT_MAX_DEPTH));
    }

    @ParameterizedTest
    @MethodSource("deepPayloads")
    @DisplayName("a payload as deep as a request body can be is embedded raw, and still serializes")
    void deepestPayloadIsEmbeddedRaw(JsonNode payload) throws IOException {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putJsonWithSlim(node, "input", "input_slim", payload);

        var json = payload.toString();
        assertThat(written(node))
                .isEqualTo("{\"input\":%s,\"input_slim\":%s}".formatted(json, json));
    }

    static Stream<JsonNode> textPayloads() {
        return Stream.of(
                null,
                JsonUtils.getJsonNodeFromString("null"),
                JsonUtils.getJsonNodeFromString(String.valueOf(RandomUtils.secure().randomInt())),
                JsonUtils.getJsonNodeFromString("\"%s\"".formatted(
                        PayloadCases.randomAlphanumeric(TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH + 1))));
    }

    @ParameterizedTest
    @MethodSource("textPayloads")
    @DisplayName("any other payload is written as its JSON text")
    void otherPayloadIsWrittenAsText(JsonNode payload) {
        var node = JsonUtils.createObjectNode();

        JsonRowValues.putJsonWithSlim(node, "input", "input_slim", payload);

        var columns = TruncationUtils.toJsonWithSlim(payload);
        var expected = JsonUtils.createObjectNode()
                .put("input", columns.json())
                .put("input_slim", columns.slim());
        assertThat(node).isEqualTo(expected);
    }
}
