package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonGenerator;
import com.fasterxml.jackson.core.io.SerializedString;
import com.fasterxml.jackson.databind.ObjectWriter;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedWriter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.Function;

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

    /**
     * Initial buffer size per row. Only an allocation hint: {@link ByteArrayOutputStream} doubles when
     * it is wrong, and a doubling copy of a large batch is exactly the churn worth avoiding here.
     */
    private static final int ESTIMATED_ROW_BYTES = 256;

    /**
     * Ceiling on that hint. Two reasons, and the second is the one that bites: a wrong guess should cost
     * a few doublings rather than a large eager allocation the batch may never need, and
     * {@code size() * ESTIMATED_ROW_BYTES} overflows {@code int} past ~8.4M rows, which would reach
     * {@link ByteArrayOutputStream} as a negative capacity and fail the batch with "Negative initial
     * size" instead of writing it. The arithmetic below is done in {@code long} so that cannot happen.
     */
    private static final int MAX_INITIAL_BUFFER_BYTES = 8 * 1024 * 1024;

    private final Client clickHouseClient;

    private final ObjectWriter rowWriter;

    /**
     * The row writer is built here, in the constructor, rather than in a static initializer.
     *
     * <p>{@code JsonUtils.configure} <em>replaces</em> the static mapper during startup — the hazard
     * {@code JsonUtilsConfigurationBundle}'s javadoc warns about — so anything capturing it at class-load
     * holds the pre-bundle instance. What that instance carries is the mapper's <b>serialization</b>
     * configuration: {@code NON_NULL} inclusion, snake_case naming, the date/duration handling and the
     * {@code JavaTimeModule}. Deriving the writer from whatever mapper the application configured is the
     * point; Guice builds this singleton after the bundle has run.
     *
     * <p>Note what this does <b>not</b> buy, because it would be easy to assume otherwise: the
     * {@code jacksonConfig} limits ({@code maxStringLength} / {@code maxDocumentLength}) are
     * {@link com.fasterxml.jackson.core.StreamReadConstraints} — parser-side only, as
     * {@code JsonUtils#applyStreamReadConstraints}' own javadoc states. They bound what is read, e.g. an
     * inbound request body, and place no bound whatsoever on what this class writes. The outbound
     * payload here is as large as the batch it is given: {@code ExperimentItemBulkUpload} caps a bulk
     * request at 4MB, but a {@code FeedbackScoreBatch} carries no such annotation, so an outbound guard
     * would be a new behaviour rather than one inherited from the mapper.
     *
     * <p>{@code FLUSH_AFTER_WRITE_VALUE} is left <b>enabled</b> here, which is the library default and
     * load-bearing for the framing in {@link #serialize}: rows go through the generator's own buffer
     * while the row separator is written straight to the {@link BufferedWriter}, so without a flush per
     * row the separator would overtake the row it is supposed to follow and merge two rows into one
     * malformed document. The generator's {@code FLUSH_PASSED_TO_STREAM} is disabled instead, so that
     * flush empties the generator into the {@link BufferedWriter} without draining the writer itself —
     * which is what keeps the buffering that makes a large batch cheap to write.
     */
    @Inject
    public JsonEachRowBulkInsert(@NonNull Client clickHouseClient) {
        this.clickHouseClient = clickHouseClient;
        this.rowWriter = JsonUtils.getMapper().writer();
    }

    /**
     * Inserts {@code items} into {@code table}, mapping each to one JSON row, and returns the row count
     * ClickHouse reports as written — the server's own {@code NUM_ROWS_WRITTEN}, not
     * {@code items.size()}, so engine-side deduplication or truncation is visible to the caller rather
     * than silently assumed away.
     */
    public <T> Mono<Long> insert(@NonNull String table,
            @NonNull String logComment,
            @NonNull Collection<T> items,
            @NonNull Function<T, ObjectNode> rowMapper) {

        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.fromCallable(() -> serialize(items, rowMapper))
                // Serialization is CPU work that scales with the batch; keep it on the bounded pool and
                // off the reactive event loop.
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(payload -> Mono.fromFuture(() -> clickHouseClient.insert(
                        table, payload::writeTo, ClickHouseFormat.JSONEachRow, settings(logComment))))
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

        var initialSize = (int) Math.min((long) items.size() * ESTIMATED_ROW_BYTES, MAX_INITIAL_BUFFER_BYTES);
        var payload = new ByteArrayOutputStream(initialSize);
        try (var writer = new BufferedWriter(new OutputStreamWriter(payload, StandardCharsets.UTF_8))) {
            JsonGenerator generator = JsonUtils.getMapper().getFactory().createGenerator(writer);
            // The generator writes into, but does not own, the writer: the enclosing
            // try-with-resources closes it, and closing it twice would flush a closed writer.
            generator.disable(JsonGenerator.Feature.AUTO_CLOSE_TARGET);
            // Keep generator.flush() from draining the BufferedWriter as well. Without this the
            // per-row flush below would push every row through to the byte buffer, and the
            // BufferedWriter would buffer nothing.
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
            generator.close();
            // Explicit because FLUSH_PASSED_TO_STREAM is off: nothing above this line pushes the
            // writer into payload, so without it the buffer's completeness rests on close() alone.
            writer.flush();
        }
        return payload;
    }
}
