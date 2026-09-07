package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import com.fasterxml.jackson.databind.node.ObjectNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.Function;

/**
 * Bulk insert through the ClickHouse Java client v2 using {@link ClickHouseFormat#JSONEachRow}.
 *
 * <p>Why this exists: the R2DBC bulk path renders one placeholder per column per row and binds each
 * by name, and the driver resolves every {@code bind(name, value)} with a linear scan over the
 * statement's parameter names. A 1000-row trace batch carries ~22k names, so binding alone is
 * O(n²) and dominates the insert. Streaming JSONEachRow removes parameter binding from the picture
 * entirely: one HTTP body, parsed server-side in ClickHouse's fast path.
 * {@code ExperimentAggregatesDAOImpl} established this pattern for experiment aggregates — this
 * class is the same approach, factored out so the trace / span / experiment-item write paths can
 * share it rather than each growing its own copy of the plumbing.
 *
 * <p>Callers reach this only when {@code bulkInsert.v2ClientEnabled} is set — see
 * {@link com.comet.opik.infrastructure.BulkInsertConfig}.
 *
 * <p>The body is <b>not</b> compressed here, and must not be: the shared client is built with
 * {@code compressClientRequest(true)} in {@code DatabaseAnalyticsFactory}, which compresses the
 * request body itself. Compressing here would double-compress and burn CPU for nothing.
 *
 * <p>Rows are streamed rather than materialized. The batch is written straight into the client's
 * output stream one row at a time, so peak memory is a single row rather than the whole payload —
 * which matters because trace {@code input}/{@code output} can be hundreds of KiB each, and a
 * 1000-row batch of those would otherwise be held in full (twice over, converting chars to UTF-8)
 * per concurrent request. Rows are built with Jackson, not string concatenation: the payload carries
 * user-supplied trace content, so escaping has to be correct by construction.
 *
 * <p>The client is the shared Dropwizard-managed singleton from
 * {@link DatabaseAnalyticsModule#getClickHouseClient()}; this class never closes it.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class JsonEachRowBulkInsert {

    private final @NonNull Client clickHouseClient;

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

        return Mono.fromCallable(() -> {
            var settings = new InsertSettings()
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

            // Serializes from items on every invocation, so the client's own retry (DataStreamWriter
            // onRetry) replays an identical body rather than resuming a half-written stream.
            try (InsertResponse response = clickHouseClient.insert(table, out -> {
                var buffered = new BufferedOutputStream(out);
                for (T item : items) {
                    buffered.write(rowMapper.apply(item).toString().getBytes(StandardCharsets.UTF_8));
                    buffered.write('\n');
                }
                buffered.flush();
            }, ClickHouseFormat.JSONEachRow, settings).get()) {
                return response.getMetrics().getMetric(ServerMetrics.NUM_ROWS_WRITTEN).getLong();
            }
        })
                // The v2 client call is blocking; keep it off the reactive event loop.
                .subscribeOn(Schedulers.boundedElastic())
                // Log the count only — never the payload, which holds customer trace data.
                .doOnError(err -> log.error("Failed JSONEachRow insert into '{}': rows='{}'", table, items.size(),
                        err));
    }
}
