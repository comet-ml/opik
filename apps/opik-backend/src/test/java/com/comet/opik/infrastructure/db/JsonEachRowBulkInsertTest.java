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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.io.ByteArrayOutputStream;
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
 * Unit coverage for the JSONEachRow body this insert streams, with no ClickHouse in the loop.
 *
 * <p>What is worth testing here is the framing, because JSONEachRow is newline-delimited and a
 * missing separator or an unflushed buffer would merge two rows into one malformed document — a
 * failure that shows up as a server-side parse error far from its cause. The row content itself is
 * the caller's business, and the type-level questions (quoted decimals, NaN, omitted defaults) can
 * only be answered by a real server, so they live in the integration tests instead.
 */
class JsonEachRowBulkInsertTest {

    private static final Function<String, ObjectNode> ROW_MAPPER = value -> {
        ObjectNode node = JsonUtils.createObjectNode();
        node.put("name", value);
        return node;
    };

    private record Captured(String body, InsertSettings settings) {
    }

    private Captured insertAndCapture(Client client, List<String> items) {
        var writerCaptor = ArgumentCaptor.forClass(DataStreamWriter.class);
        var settingsCaptor = ArgumentCaptor.forClass(InsertSettings.class);

        Long rows = new JsonEachRowBulkInsert(client)
                .insert("traces", "log-comment", items, ROW_MAPPER)
                .block();

        assertThat(rows).isEqualTo(items.size());

        verify(client).insert(eq("traces"), writerCaptor.capture(), eq(ClickHouseFormat.JSONEachRow),
                settingsCaptor.capture());

        var out = new ByteArrayOutputStream();
        try {
            writerCaptor.getValue().onOutput(out);
        } catch (Exception e) {
            throw new AssertionError("writer threw", e);
        }

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
    void writesOneNewlineDelimitedRowPerItem() {
        var captured = insertAndCapture(clientReturning(3L), List.of("a", "b", "c"));

        assertThat(captured.body()).isEqualTo("""
                {"name":"a"}
                {"name":"b"}
                {"name":"c"}
                """);
        assertThat(captured.body().lines()).hasSize(3);
    }

    @Test
    @DisplayName("a single row still terminates with a newline")
    void singleRowIsNewlineTerminated() {
        var captured = insertAndCapture(clientReturning(1L), List.of("only"));

        assertThat(captured.body()).isEqualTo("{\"name\":\"only\"}\n");
    }

    @Test
    @DisplayName("the writer is replayable, so a client retry sends an identical body")
    void writerIsReplayable() {
        var client = clientReturning(2L);
        var writerCaptor = ArgumentCaptor.forClass(DataStreamWriter.class);

        new JsonEachRowBulkInsert(client).insert("spans", "log-comment", List.of("x", "y"), ROW_MAPPER).block();

        verify(client).insert(eq("spans"), writerCaptor.capture(), any(ClickHouseFormat.class),
                any(InsertSettings.class));

        DataStreamWriter writer = writerCaptor.getValue();

        var first = new ByteArrayOutputStream();
        var second = new ByteArrayOutputStream();
        try {
            writer.onOutput(first);
            writer.onRetry();
            writer.onOutput(second);
        } catch (Exception e) {
            throw new AssertionError("writer threw", e);
        }

        assertThat(second.toString(StandardCharsets.UTF_8))
                .isEqualTo(first.toString(StandardCharsets.UTF_8))
                .isEqualTo("{\"name\":\"x\"}\n{\"name\":\"y\"}\n");
    }

    @Test
    @DisplayName("multi-byte content is written as UTF-8, so the byte count is not the char count")
    void writesUtf8() {
        var captured = insertAndCapture(clientReturning(1L), List.of("日本語"));

        assertThat(captured.body()).isEqualTo("{\"name\":\"日本語\"}\n");
    }

    @Test
    @DisplayName("content that would break a hand-built body is escaped by Jackson")
    void escapesContentThatWouldBreakTheBody() {
        var captured = insertAndCapture(clientReturning(1L), List.of("a\"b\nc"));

        // The newline inside the value must be escaped, not emitted literally — a literal one would
        // split a single row into two malformed JSONEachRow lines.
        assertThat(captured.body()).isEqualTo("{\"name\":\"a\\\"b\\nc\"}\n");
        assertThat(captured.body().lines()).hasSize(1);
    }

    @Test
    @DisplayName("the per-request server settings the row encoding depends on are set")
    void setsTheServerSettingsTheEncodingDependsOn() {
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

        Long rows = new JsonEachRowBulkInsert(client).insert("traces", "log-comment", List.of(), ROW_MAPPER).block();

        assertThat(rows).isZero();
        verify(client, never()).insert(any(String.class), any(DataStreamWriter.class), any(ClickHouseFormat.class),
                any(InsertSettings.class));
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

        new JsonEachRowBulkInsert(client).insert("traces", "log-comment", List.of("a"), ROW_MAPPER).block();

        // try-with-resources should release it; an unclosed response holds its stream, which over a
        // few hundred batches per run would accumulate rather than fail loudly.
        verify(response).close();
    }

    @Test
    @DisplayName("a client failure surfaces its cause, not the ExecutionException wrapper")
    void unwrapsTheClientFailure() {
        var client = mock(Client.class);
        when(client.insert(any(String.class), any(DataStreamWriter.class), any(ClickHouseFormat.class),
                any(InsertSettings.class)))
                .thenReturn(CompletableFuture.failedFuture(new SocketException("connection reset")));

        // RetryUtils.handleConnectionError matches on the throwable's own class, so a SocketException
        // still wrapped in ExecutionException would silently bypass the retry the R2DBC path gets.
        assertThatThrownBy(() -> new JsonEachRowBulkInsert(client)
                .insert("traces", "log-comment", List.of("a"), ROW_MAPPER)
                .block())
                .hasRootCauseInstanceOf(SocketException.class);
    }

    @Test
    @DisplayName("the row count comes from the server, not from items.size()")
    void returnsTheServerRowCount() {
        // Deduplication or truncation would make these differ; the caller must see the server's number.
        Long rows = new JsonEachRowBulkInsert(clientReturning(2L))
                .insert("traces", "log-comment", List.of("a", "b", "c"), ROW_MAPPER)
                .block();

        assertThat(rows).isEqualTo(2L);
    }
}
