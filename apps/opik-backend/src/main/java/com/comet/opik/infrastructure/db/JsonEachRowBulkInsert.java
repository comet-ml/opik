package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import com.fasterxml.jackson.core.JsonGenerator;
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
import reactor.core.scheduler.Schedulers;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
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
 * <p>Callers reach this only when {@code bulkInsert.v2ClientEnabled} is set — see
 * {@link com.comet.opik.infrastructure.BulkInsertConfig}.
 *
 * <p><b>Which thread does what.</b> The two halves run on different pools, deliberately:
 *
 * <ul>
 *   <li><b>Serialization</b> — the row building that replaces R2DBC's parameter binding — runs in
 *       {@link #serialize} on {@link Schedulers#boundedElastic()}, which is bounded at
 *       {@code 10 × cores}. That bound is the point: it is CPU work, and it is the one part of this
 *       path whose cost scales with the batch.</li>
 *   <li><b>The HTTP round trip</b> runs on the client's own {@code chc-operation} pool, because
 *       {@code DatabaseAnalyticsFactory} builds the client with {@code useAsyncRequests(true)} and
 *       {@link Client#insert} therefore returns a genuinely deferred future. {@link Mono#fromFuture}
 *       consumes it without parking a thread of ours on it.</li>
 * </ul>
 *
 * <p>The split is why the payload is materialized rather than serialized straight into the client's
 * output stream: the {@code DataStreamWriter} callback is the HTTP body pump, so anything written
 * there runs on {@code chc-operation}. Handing that callback a finished buffer keeps the CPU work on
 * the bounded pool. The cost is holding the batch in memory — for feedback scores that is a large
 * count of small rows rather than the reverse, and {@link ByteArrayOutputStream#writeTo} hands the
 * buffer to the client without copying it again.
 *
 * <p>Writing from a fixed buffer also makes the writer replayable for free: the client's
 * {@code onRetry} re-invokes it and gets identical bytes, rather than resuming a half-written stream.
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
 * {@link DatabaseAnalyticsModule#getClickHouseClient()}; this class never closes it.
 */
@Singleton
@Slf4j
public class JsonEachRowBulkInsert {

    private static final SerializedString EMPTY_ROOT_SEPARATOR = new SerializedString("");

    private final Client clickHouseClient;

    private final ObjectMapper objectMapper;

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
     * load-bearing for the framing in {@link #serialize}: rows go through the generator's own buffer
     * while the row separator is written straight to the {@link BufferedWriter}, so without a flush per
     * row the separator would overtake the row it is supposed to follow and merge two rows into one
     * malformed document. The generator's {@code FLUSH_PASSED_TO_STREAM} is disabled instead, so that
     * flush empties the generator into the {@link BufferedWriter} without draining the writer itself.
     */
    @Inject
    public JsonEachRowBulkInsert(@NonNull Client clickHouseClient, @NonNull ObjectMapper objectMapper) {
        this.clickHouseClient = clickHouseClient;
        this.objectMapper = objectMapper;
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

        return Mono.fromCallable(() -> serialize(items, rowMapper))
                // Serialization is CPU work that scales with the batch; keep it on the bounded pool and
                // off the reactive event loop.
                .subscribeOn(Schedulers.boundedElastic())
                // Opened here, around the statement alone, not around the serialization above: the
                // R2DBC paths time statement.execute() and nothing else, so measuring more here would
                // make the two transports incomparable on the metric that exists to compare them.
                // Named for the table, so a JSONEachRow write lands on the same segment as its R2DBC
                // counterpart -- spans already report custom-reactive-spans / batch_insert.
                //
                // No Mono.defer: the flatMap body runs when the payload is emitted, which is already
                // subscription time, so the segment cannot open on an unsubscribed publisher.
                .flatMap(payload -> {
                    Segment segment = startSegment(table, "Clickhouse", "batch_insert");

                    return Mono.fromFuture(() -> clickHouseClient.insert(
                            table, payload::writeTo, ClickHouseFormat.JSONEachRow, settings(logComment)))
                            .doFinally(signalType -> endSegment(segment));
                })
                .map(response -> {
                    try (response) {
                        return response.getMetrics().getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong();
                    }
                })
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
                .serverSetting("input_format_json_read_numbers_as_strings", "1");
    }

    /**
     * Renders the batch as newline-delimited JSON rows. Returns the buffer rather than a {@code byte[]}
     * so the caller can hand it to the client via {@link ByteArrayOutputStream#writeTo}, which writes
     * the backing array directly instead of copying the whole payload a second time.
     *
     * <p>Note {@link BufferedWriter#newLine()} writes {@code System.lineSeparator()}, so the row
     * terminator follows the platform rather than being a fixed {@code '\n'}. ClickHouse accepts the
     * {@code \r\n} that produces on Windows — JSONEachRow treats the {@code \r} as inter-row
     * whitespace — and the backend runs on Linux, where the two are the same byte.
     */
    private <T> ByteArrayOutputStream serialize(Collection<T> items, Function<T, ObjectNode> rowMapper)
            throws IOException {

        // Outside the try-with-resources, unlike the other ByteArrayOutputStream uses in this codebase:
        // they copy out with toByteArray() before the block ends, while this one is handed to the client
        // as writeTo and read on its thread after this method returns, so it has to outlive the block.
        var payload = new ByteArrayOutputStream();
        // Declared in this order so they close in the reverse one: the generator drains into the
        // writer, then the writer into payload. Both are resources so a row that fails to serialize
        // still releases the generator's buffer back to Jackson's recycler on the way out.
        try (var writer = new BufferedWriter(new OutputStreamWriter(payload, StandardCharsets.UTF_8));
                var generator = objectMapper.getFactory().createGenerator(writer)) {

            // Both set before anything is written, since they govern what close() and flush() do.
            // The generator writes into, but does not own, the writer, which the try-with-resources
            // closes; left enabled, closing the generator would close the writer under it.
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

            // The payload is complete here rather than as a side effect of the closes below.
            // FLUSH_AFTER_WRITE_VALUE has already drained the generator into the writer after every
            // row, so this pushes the whole batch into payload; closing would do it too.
            writer.flush();
        }
        return payload;
    }
}
