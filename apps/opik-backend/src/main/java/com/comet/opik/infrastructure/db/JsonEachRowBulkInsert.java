package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.DataStreamWriter;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import com.comet.opik.utils.AsyncUtils;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.StreamWriteConstraints;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;

import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.Function;

import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;

/**
 * Bulk insert through the ClickHouse Java client v2 using {@link ClickHouseFormat#JSONEachRow}.
 *
 * <p>Why this exists: the R2DBC bulk path renders one placeholder per column per row and binds each
 * by name, and the driver resolves every {@code bind(name, value)} with a linear scan over the
 * statement's parameter names, so binding alone is O(n²) and dominates the insert. JSONEachRow
 * removes parameter binding from the picture entirely: one HTTP body, parsed server-side in
 * ClickHouse's fast path. {@code ExperimentAggregatesDAOImpl} established this pattern for
 * experiment aggregates — this class is the same approach, factored out so a write path can adopt it
 * without growing its own copy of the plumbing. {@code FeedbackScoreDAO} is the first caller.
 *
 * <p>This is the only bulk write path. It was introduced behind a toggle alongside the R2DBC
 * implementation it replaced; both the toggle and that implementation are gone.
 *
 * <p><b>The body is streamed</b>: {@link #write} runs as the client's {@code DataStreamWriter}, so each row is mapped
 * and serialized into the body as it is sent, and the batch is never held as a buffer: the client sends the body
 * chunked through its fixed-size compression buffer. The writer runs on the client's {@code chc-operation} pool
 * ({@code useAsyncRequests(true)} in {@code DatabaseAnalyticsFactory}), and the insert holds its pooled connection
 * while the rows are mapped and serialized. The client leases that connection before it calls the writer, so
 * {@code clientMaxOpenConnections} bounds how many inserts serialize at once.
 *
 * <p>The client calls the writer again on a retry, which maps and serializes the items again. That gives identical
 * bytes as long as the row mapper is a pure function of the item; callers resolve values such as the batch
 * timestamp before calling {@link #insert}.
 *
 * <p>The body is <b>not</b> compressed here, and must not be: the shared client is built with
 * {@code compressClientRequest(true)} in {@code DatabaseAnalyticsFactory}, which compresses the
 * request body itself. Compressing here would double-compress and burn CPU for nothing.
 *
 * <p>Rows are built with Jackson, not string concatenation: the payload carries user-supplied
 * content, so escaping has to be correct by construction. A failure logs the table, the row count and
 * the exception as its cause — never the rows.
 *
 * <p>The client is the shared Dropwizard-managed singleton from
 * {@link DatabaseAnalyticsModule#getClickHouseClient}; this class never closes it.
 */
@Singleton
@Slf4j
public class JsonEachRowBulkInsert {

    private static final SerializedString EMPTY_ROOT_SEPARATOR = new SerializedString("");

    private final Client clickHouseClient;

    private final JsonFactory rowFactory;

    private final ObjectWriter rowWriter;

    /**
     * The mapper is injected rather than read from {@code JsonUtils}: that one is a static field the
     * application <em>replaces</em> during startup, so anything reaching for it risks the pre-configuration
     * instance, and it is on its way out of the codebase. What this class needs from it is the
     * <b>serialization</b> configuration — {@code NON_NULL} inclusion, snake_case naming, the
     * date/duration handling and the {@code JavaTimeModule} — which {@code OpikApplication} applies to the
     * Dropwizard mapper bound here.
     *
     * <p>{@code FLUSH_AFTER_WRITE_VALUE} is left <b>enabled</b>, which is the library default and
     * load-bearing for the framing in {@link #writeRows}: rows go through the generator's own buffer
     * while the row separator is written straight to the {@link BufferedWriter}, so without a flush per
     * row the separator would overtake the row it is supposed to follow and merge two rows into one
     * malformed document. The generator's {@code FLUSH_PASSED_TO_STREAM} is disabled instead, so that
     * flush empties the generator into the {@link BufferedWriter} without draining the writer itself.
     */
    @Inject
    public JsonEachRowBulkInsert(@NonNull Client clickHouseClient, @NonNull ObjectMapper objectMapper) {
        this.clickHouseClient = clickHouseClient;
        // One nesting level above the mapper's, so a payload node.toString() can serialize still
        // fits inside the row object that JsonRowValues embeds it in.
        this.rowFactory = objectMapper.getFactory().rebuild()
                .streamWriteConstraints(StreamWriteConstraints.builder()
                        .maxNestingDepth(objectMapper.getFactory().streamWriteConstraints().getMaxNestingDepth() + 1)
                        .build())
                .build();
        this.rowWriter = objectMapper.writer();
    }

    /**
     * Inserts {@code items} into {@code table}, mapping each to one JSON row, and returns the row count
     * ClickHouse reports as written — the server's own {@code NUM_ROWS_WRITTEN}, not
     * {@code items.size()}, so engine-side deduplication or truncation is visible to the caller rather
     * than silently assumed away.
     */
    public <T> Mono<Long> insert(@NonNull String table,
            @NonNull String logComment,
            @Nullable Collection<T> items,
            @NonNull Function<T, ObjectNode> rowMapper) {

        // Nullable rather than @NonNull: the check below treats a null batch as a no-op, and rejecting it
        // one line earlier would make that unreachable.
        if (CollectionUtils.isEmpty(items)) {
            return Mono.just(0L);
        }

        return send(table, logComment, body -> write(body, items, rowMapper))
                // The throwable is passed as the cause, not formatted into the message: a failed bulk
                // insert is diagnosable only from the stack and the cause chain, which say whether it
                // was serialization, a connection reset or a server-side rejection. Only the table and
                // the row COUNT are logged alongside it — never the rows themselves. Note a ClickHouse
                // parse error quotes the offending value in its own message, so this log can carry a
                // fragment of customer content by way of the exception.
                //
                // Mono.fromFuture unwraps the CompletionException the client's future fails with, so the
                // cause seen here is the client's own exception rather than a wrapper.
                //
                // Note the callers' retry (RetryUtils.handleConnectionError, applied in
                // ExperimentItemBulkIngestionService and the resource layer) matches SocketException
                // subclasses, and this client fails transport drops with Apache HC's own
                // NoHttpResponseException, which is not one. So a dropped response is retried on the
                // R2DBC path and not on this one. That gap is the v2 client's, not this class's — it is
                // equally true of the DatasetItemVersionDAO queries already running on it — so widening
                // the filter belongs with the other v2-client settings rather than here.
                .doOnError(err -> log.error("Failed JSONEachRow insert: table='{}' rows='{}'",
                        table, items.size(), err));
    }

    /**
     * Sends the body the writer produces. Deferred so the segment opens at subscription, not at assembly. Named for
     * the table, so spans report custom-reactive-spans / batch_insert. It times the rows' mapping and serialization
     * with the statement.
     */
    private Mono<Long> send(String table, String logComment, DataStreamWriter body) {
        return Mono.defer(() -> {
            Segment segment = startSegment(table, "Clickhouse", "batch_insert");

            return AsyncUtils.usingClickHouseFuture(
                    () -> clickHouseClient.insert(table, body, ClickHouseFormat.JSONEachRow, settings(logComment)),
                    response -> response.getMetrics().getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong())
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    private InsertSettings settings(String logComment) {
        return new InsertSettings()
                .logComment(logComment)
                // best_effort so the client-side formatted DateTime64 literals parse, and
                // defaults_for_omitted_fields so columns absent from the row (created_at,
                // MATERIALIZED columns) still take their DDL defaults. Both are scoped to this
                // request rather than set on the shared Client.Builder, so unrelated queries
                // keep the server defaults.
                .serverSetting("date_time_input_format", "best_effort")
                .serverSetting("input_format_defaults_for_omitted_fields", "1")
                // Jackson quotes non-numeric doubles (QUOTE_NON_NUMERIC_NUMBERS), so an absent
                // ttft reaches ClickHouse as the string "NaN" once the sentinel columns are
                // non-nullable. Set explicitly rather than inherited from the server default,
                // which would make correctness depend on an unrelated server-side setting.
                .serverSetting("input_format_json_read_numbers_as_strings", "1")
                // The parallel parser splits the body on chunk boundaries and cannot carry one row
                // across them, so a single row past 10x min_chunk_bytes_for_parallel_parsing -- 100
                // MiB at the 10 MiB default -- is rejected with "Size of JSON object at position N
                // is extremely large" (code 117, INCORRECT_DATA), taking the whole batch with it at
                // written_rows = 0. Note the message quotes min_chunk_bytes itself as the bound it
                // enforces, which is off by the factor of 10: measured on the server image this
                // suite runs, 99 MiB is accepted and 100 MiB is not.
                //
                // Spans carry user-supplied input and output, so rows that large are a normal thing
                // for a customer to send, and in production this silently dropped batches. Raising
                // the threshold would only move the cliff; disabling the parallel parser removes it,
                // and it engages at all only above 10 MiB of body -- under 2% of span inserts, and
                // none of the other write paths.
                .serverSetting("input_format_parallel_parsing", "0")
                // JsonRowValues.putJsonWithSlim embeds object and array payloads as raw JSON, and these store them
                // verbatim in their String columns. Both default to 1; pinned here so the stored text does not depend
                // on the server default.
                .serverSetting("input_format_json_read_objects_as_strings", "1")
                .serverSetting("input_format_json_read_arrays_as_strings", "1");
    }

    /**
     * Streams the batch into the request body, mapping each item as it goes. The writer is not closed: that would
     * close the body, which the client owns and closes.
     */
    private <T> void write(OutputStream body, Collection<T> items, Function<T, ObjectNode> rowMapper)
            throws IOException {
        writeRows(new BufferedWriter(new OutputStreamWriter(body, StandardCharsets.UTF_8)), items, rowMapper);
    }

    /**
     * Writes the batch as newline-delimited JSON rows into {@code writer}, which the caller owns, and flushes it.
     *
     * <p>Note {@link BufferedWriter#newLine()} writes {@code System.lineSeparator()}, so the row
     * terminator follows the platform rather than being a fixed {@code '\n'}. ClickHouse accepts the
     * {@code \r\n} that produces on Windows — JSONEachRow treats the {@code \r} as inter-row
     * whitespace — and the backend runs on Linux, where the two are the same byte.
     */
    private <T> void writeRows(BufferedWriter writer, Collection<T> items, Function<T, ObjectNode> rowMapper)
            throws IOException {

        // A resource so a row that fails to serialize still releases the generator's buffer back to Jackson's
        // recycler on the way out.
        try (var generator = rowFactory.createGenerator(writer)) {

            // Both set before anything is written, since they govern what close() and flush() do.
            // The generator writes into, but does not own, the writer; left enabled, closing the generator
            // would close the writer under it.
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            // Keep the per-row flush from draining the BufferedWriter as well, which would leave it
            // buffering nothing.
            generator.disable(JsonGenerator.Feature.FLUSH_PASSED_TO_STREAM);
            // Jackson's default root separator is a space, which would prepend one to every row
            // after the first. The separator that matters is written by newLine() below.
            generator.setRootValueSeparator(EMPTY_ROOT_SEPARATOR);

            for (T item : items) {
                // writeValue flushes the generator into the writer (FLUSH_AFTER_WRITE_VALUE), so the
                // row is fully written before its terminator goes in after it.
                rowWriter.writeValue(generator, rowMapper.apply(item));
                writer.newLine();
            }

            // FLUSH_AFTER_WRITE_VALUE has already drained the generator into the writer after every row, so this
            // pushes the rest of the batch to the writer's target.
            writer.flush();
        }
    }
}
