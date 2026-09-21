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
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit coverage for the JSONEachRow body this insert renders, with no ClickHouse in the loop.
 *
 * <p>What is worth testing here is the framing, because JSONEachRow is newline-delimited and a
 * missing separator or an unflushed buffer would merge two rows into one malformed document — a
 * failure that shows up as a server-side parse error far from its cause. That hazard is live in this
 * implementation rather than hypothetical: rows go through the generator's buffer while the row
 * separator is written straight to the {@code BufferedWriter}, so the two have to be ordered against
 * each other, and only the byte-level assertions below can see when they are not. The row content itself is
 * the caller's business, and the type-level questions (quoted decimals, NaN, omitted defaults) can
 * only be answered by a real server, so they live in the integration tests instead.
 */
class JsonEachRowBulkInsertTest {

    private static String randomValue() {
        return RandomStringUtils.secure().nextAlphanumeric(12);
    }

    private static final Function<String, ObjectNode> ROW_MAPPER = value -> {
        ObjectNode node = JsonUtils.createObjectNode();
        node.put("name", value);
        return node;
    };

    private record Captured(String body, InsertSettings settings) {
    }

    private Captured insertAndCapture(Client client, List<String> items) throws IOException {
        var writerCaptor = ArgumentCaptor.forClass(DataStreamWriter.class);
        var settingsCaptor = ArgumentCaptor.forClass(InsertSettings.class);

        Long rows = new JsonEachRowBulkInsert(client, JsonUtils.getMapper())
                .insert("feedback_scores", "log-comment", items, ROW_MAPPER)
                .block();

        assertThat(rows).isEqualTo(items.size());

        verify(client).insert(eq("feedback_scores"), writerCaptor.capture(), eq(ClickHouseFormat.JSONEachRow),
                settingsCaptor.capture());

        var out = new ByteArrayOutputStream();
        writerCaptor.getValue().onOutput(out);

        return new Captured(out.toString(StandardCharsets.UTF_8), settingsCaptor.getValue());
    }

    private static String settingValue(InsertSettings settings, String name) {
        return settings.getAllSettings().entrySet().stream()
                .filter(entry -> entry.getKey().equals(name) || entry.getKey().endsWith("." + name)
                        || entry.getKey().endsWith("_" + name))
                .map(entry -> String.valueOf(entry.getValue()))
                .findFirst()
                .orElseGet(() -> "ABSENT, settings were " + settings.getAllSettings().keySet());
    }

    private Client clientReturning(long rowsWritten) {
        var metric = mock(Metric.class);
        when(metric.getLong()).thenReturn(rowsWritten);

        var metrics = mock(OperationMetrics.class);
        when(metrics.getMetric(ServerMetrics.NUM_ROWS_WRITTEN)).thenReturn(metric);

        var response = mock(InsertResponse.class);
        when(response.getMetrics()).thenReturn(metrics);

        var client = mock(Client.class);
        when(client.insert(any(String.class), any(DataStreamWriter.class), any(ClickHouseFormat.class),
                any(InsertSettings.class)))
                .thenReturn(CompletableFuture.completedFuture(response));

        return client;
    }

    @Test
    @DisplayName("one row per item, newline delimited, no trailing separator issues")
    void writesOneNewlineDelimitedRowPerItem() throws Exception {
        var items = List.of(randomValue(), randomValue(), randomValue());
        var captured = insertAndCapture(clientReturning(items.size()), items);

        var expected = items.stream()
                .map("{\"name\":\"%s\"}\n"::formatted)
                .collect(java.util.stream.Collectors.joining());
        assertThat(captured.body()).isEqualTo(expected);
        assertThat(captured.body().lines()).hasSize(items.size());
    }

    @Test
    @DisplayName("a single row still terminates with a newline")
    void singleRowIsNewlineTerminated() throws Exception {
        var only = randomValue();
        var captured = insertAndCapture(clientReturning(1L), List.of(only));

        assertThat(captured.body()).isEqualTo("{\"name\":\"%s\"}\n".formatted(only));
    }

    @Test
    @DisplayName("the writer is replayable, so a client retry sends an identical body")
    void writerIsReplayable() throws Exception {
        var client = clientReturning(2L);
        var writerCaptor = ArgumentCaptor.forClass(DataStreamWriter.class);
        var first0 = randomValue();
        var second0 = randomValue();

        new JsonEachRowBulkInsert(client, JsonUtils.getMapper())
                .insert("authored_feedback_scores", "log-comment", List.of(first0, second0), ROW_MAPPER).block();

        verify(client).insert(eq("authored_feedback_scores"), writerCaptor.capture(), any(ClickHouseFormat.class),
                any(InsertSettings.class));

        DataStreamWriter writer = writerCaptor.getValue();

        var first = new ByteArrayOutputStream();
        var second = new ByteArrayOutputStream();
        writer.onOutput(first);
        writer.onRetry();
        writer.onOutput(second);

        assertThat(second.toString(StandardCharsets.UTF_8))
                .isEqualTo(first.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"name\":\"%s\"}\n{\"name\":\"%s\"}\n".formatted(first0, second0));
    }

    @Test
    @DisplayName("the per-request server settings the row encoding depends on are set")
    void setsTheServerSettingsTheEncodingDependsOn() throws Exception {
        var captured = insertAndCapture(clientReturning(1L), List.of("a"));

        // Matched by key suffix: the client may namespace server settings internally, and this test is
        // about the settings being applied, not about that internal key shape.
        assertThat(settingValue(captured.settings(), "date_time_input_format")).isEqualTo("best_effort");
        assertThat(settingValue(captured.settings(), "input_format_defaults_for_omitted_fields")).isEqualTo("1");
        assertThat(settingValue(captured.settings(), "input_format_json_read_numbers_as_strings")).isEqualTo("1");
    }

    @Test
    @DisplayName("an empty batch is a no-op rather than an empty insert")
    void emptyBatchDoesNotCallTheClient() {
        var client = mock(Client.class);

        Long rows = new JsonEachRowBulkInsert(client, JsonUtils.getMapper())
                .insert("feedback_scores", "log-comment", List.of(), ROW_MAPPER)
                .block();

        assertThat(rows).isZero();
        verify(client, never()).insert(any(String.class), any(DataStreamWriter.class), any(ClickHouseFormat.class),
                any(InsertSettings.class));
    }

    @Test
    @DisplayName("an explicit null is written, not dropped by NON_NULL inclusion")
    void writesExplicitNulls() throws Exception {
        // The configured mapper sets serialization inclusion to NON_NULL, so a putNull() would be dropped
        // unless the writer is derived from it the way this class does. That distinction is load-bearing
        // for any column that must receive SQL NULL rather than its DDL default: an omitted field takes
        // the default, which is a different cell.
        Function<String, ObjectNode> nullMapper = value -> {
            ObjectNode node = JsonUtils.createObjectNode();
            node.put("name", value);
            node.putNull("project_id");
            return node;
        };

        var client = clientReturning(1L);
        var writerCaptor = ArgumentCaptor.forClass(DataStreamWriter.class);
        var value = randomValue();

        new JsonEachRowBulkInsert(client, JsonUtils.getMapper())
                .insert("feedback_scores", "log-comment", List.of(value), nullMapper).block();

        verify(client).insert(eq("feedback_scores"), writerCaptor.capture(), any(ClickHouseFormat.class),
                any(InsertSettings.class));

        var out = new ByteArrayOutputStream();
        writerCaptor.getValue().onOutput(out);

        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"name\":\"%s\",\"project_id\":null}\n".formatted(value));
    }

    @Test
    @DisplayName("the InsertResponse is closed on success")
    void closesTheResponse() throws Exception {
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
                .insert("feedback_scores", "log-comment", List.of("a"), ROW_MAPPER).block();

        // try-with-resources should release it; an unclosed response holds its stream, which over a
        // few hundred batches per run would accumulate rather than fail loudly.
        verify(response).close();
    }

    @Test
    @DisplayName("a client failure surfaces its cause, not the CompletionException wrapper")
    void unwrapsTheClientFailure() {
        var client = mock(Client.class);
        when(client.insert(any(String.class), any(DataStreamWriter.class), any(ClickHouseFormat.class),
                any(InsertSettings.class)))
                .thenReturn(CompletableFuture.failedFuture(new SocketException("connection reset")));

        // RetryUtils.handleConnectionError matches on the throwable's own class, so a SocketException
        // still wrapped in the future's CompletionException would silently bypass the retry the R2DBC
        // path gets. Mono.fromFuture unwraps that wrapper; this pins the behaviour we depend on.
        assertThatThrownBy(() -> new JsonEachRowBulkInsert(client, JsonUtils.getMapper())
                .insert("feedback_scores", "log-comment", List.of("a"), ROW_MAPPER)
                .block())
                .hasRootCauseInstanceOf(SocketException.class);
    }

    @Test
    @DisplayName("the row count comes from the server, not from items.size()")
    void returnsTheServerRowCount() {
        // Deduplication or truncation would make these differ; the caller must see the server's number.
        Long rows = new JsonEachRowBulkInsert(clientReturning(2L), JsonUtils.getMapper())
                .insert("feedback_scores", "log-comment", List.of("a", "b", "c"), ROW_MAPPER)
                .block();

        assertThat(rows).isEqualTo(2L);
    }
}
