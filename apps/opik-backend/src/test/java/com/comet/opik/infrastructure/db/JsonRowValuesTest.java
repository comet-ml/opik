package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.DataStreamWriter;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.Metric;
import com.clickhouse.client.api.metrics.OperationMetrics;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
        var metric = mock(Metric.class);
        when(metric.getLong()).thenReturn(1L);
        var metrics = mock(OperationMetrics.class);
        when(metrics.getMetric(ServerMetrics.NUM_ROWS_WRITTEN)).thenReturn(metric);
        var response = mock(InsertResponse.class);
        when(response.getMetrics()).thenReturn(metrics);
        var client = mock(Client.class);
        when(client.insert(any(String.class), any(DataStreamWriter.class), any(ClickHouseFormat.class),
                any(InsertSettings.class))).thenReturn(CompletableFuture.completedFuture(response));

        new JsonEachRowBulkInsert(client, JsonUtils.getMapper())
                .insert("spans", "log-comment", List.of(row), Function.identity())
                .block();

        var writer = ArgumentCaptor.forClass(DataStreamWriter.class);
        verify(client).insert(eq("spans"), writer.capture(), eq(ClickHouseFormat.JSONEachRow),
                any(InsertSettings.class));
        var body = new ByteArrayOutputStream();
        writer.getValue().onOutput(body);
        return body.toString(StandardCharsets.UTF_8).stripTrailing();
    }

    private static String randomText(int length) {
        return RandomStringUtils.secure().nextAlphanumeric(length);
    }

    static Stream<JsonNode> containerPayloads() {
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        return Stream.of(
                JsonUtils.getJsonNodeFromString("{}"),
                JsonUtils.getJsonNodeFromString("[]"),
                JsonUtils.getJsonNodeFromString("{\"short\":\"%s\",\"n\":1,\"none\":null}"
                        .formatted(randomText(20))),
                JsonUtils.getJsonNodeFromString("[{\"long\":\"%s\"},\"%s\",1.5]"
                        .formatted(randomText(max + 1), randomText(max * 2))));
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

    private static JsonNode nestedArrays(int depth) {
        JsonNode node = JsonNodeFactory.instance.arrayNode().add(randomText(10));
        for (int level = 1; level < depth; level++) {
            node = JsonNodeFactory.instance.arrayNode().add(node);
        }
        return node;
    }

    static Stream<JsonNode> deepPayloads() {
        return Stream.of(
                nestedArrays(StreamWriteConstraints.DEFAULT_MAX_DEPTH - 1),
                // As deep as a request body can be: the row around it goes one level past the default limit.
                nestedArrays(StreamWriteConstraints.DEFAULT_MAX_DEPTH));
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
                JsonUtils.getJsonNodeFromString("42"),
                JsonUtils.getJsonNodeFromString("\"%s\"".formatted(
                        randomText(TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH + 1))));
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

    /**
     * The cells ClickHouse stores from one JSONEachRow row into String columns: a string's value, and an
     * object or array verbatim, as input_format_json_read_objects_as_strings / _arrays_as_strings do.
     */
    private static Map<String, String> storedCells(String row) throws IOException {
        Map<String, String> cells = new LinkedHashMap<>();
        // The row is one level deeper than its deepest payload, as JsonEachRowBulkInsert allows.
        var factory = JsonFactory.builder()
                .streamReadConstraints(StreamReadConstraints.builder()
                        .maxNestingDepth(StreamReadConstraints.DEFAULT_MAX_DEPTH + 1)
                        .build())
                .build();
        try (var parser = factory.createParser(row)) {
            parser.nextToken();
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                String field = parser.currentName();
                JsonToken value = parser.nextToken();
                if (value == JsonToken.START_OBJECT || value == JsonToken.START_ARRAY) {
                    int start = (int) parser.currentTokenLocation().getCharOffset();
                    parser.skipChildren();
                    cells.put(field, row.substring(start, (int) parser.currentLocation().getCharOffset()));
                } else {
                    cells.put(field, parser.getText());
                }
            }
        }
        return cells;
    }

    @ParameterizedTest
    @MethodSource({"containerPayloads", "deepPayloads", "textPayloads",
            "com.comet.opik.utils.TruncationUtilsTest$ToJsonWithSlimTests#parsedPayloads",
            "com.comet.opik.utils.TruncationUtilsTest$ToJsonWithSlimTests#builtPayloads"})
    @DisplayName("the stored cells are the ones the full and slim strings produced before")
    void storedCellsMatchThePreviousStrings(JsonNode payload) throws IOException {
        var actual = JsonUtils.createObjectNode();
        JsonRowValues.putJsonWithSlim(actual, "input", "input_slim", payload);

        // The previous row: the payload rendered to a string, and its slim copy from that string.
        String json = TruncationUtils.toJsonString(payload);
        var expected = JsonUtils.createObjectNode()
                .put("input", json)
                .put("input_slim", TruncationUtils.createSlimJsonString(json));

        assertThat(storedCells(written(actual))).isEqualTo(storedCells(written(expected)));
    }
}
