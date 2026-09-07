package com.comet.opik.infrastructure.db;

import com.clickhouse.client.api.Client;
import com.clickhouse.client.api.insert.InsertResponse;
import com.clickhouse.client.api.insert.InsertSettings;
import com.clickhouse.client.api.metrics.ServerMetrics;
import com.clickhouse.data.ClickHouseFormat;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.function.BiConsumer;

/**
 * Bulk insert through the ClickHouse Java client v2 using {@link ClickHouseFormat#JSONEachRow}.
 *
 * <p>Why this exists: the R2DBC bulk path renders one placeholder per column per row and binds each
 * by name, and the driver resolves every {@code bind(name, value)} with a linear scan over the
 * statement's parameter names. A 1000-row trace batch carries ~22k names, so binding alone is
 * O(n²) and dominates the insert. Streaming JSONEachRow removes parameter binding from the picture
 * entirely: one HTTP body, compressed once by the client, parsed server-side in ClickHouse's fast
 * path. {@code ExperimentAggregatesDAOImpl} established this pattern for experiment aggregates —
 * this class is the same approach, factored out so the trace / span / experiment-item write paths
 * can share it rather than each growing its own copy of the plumbing.
 *
 * <p>The client is the shared Dropwizard-managed singleton from
 * {@link DatabaseAnalyticsModule#getClickHouseClient()}; this class never closes it.
 *
 * <p>Callers own row serialization: the {@code rowWriter} appends exactly one JSON object plus a
 * newline per item. Build rows with Jackson rather than string concatenation — the payload carries
 * user-supplied trace input/output, so escaping has to be correct by construction.
 */
@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class JsonEachRowBulkInsert {

    private static final String ENABLED_PROPERTY = "opik.v2BulkInsert";

    private final @NonNull Client clickHouseClient;

    /**
     * Whether the bulk write paths use this insert or the R2DBC bulk bind they replace.
     *
     * <p>Enabled by default; only the literal {@code -Dopik.v2BulkInsert=false} restores R2DBC, so a
     * typo cannot silently take a deployment back to the slow path. Keeping both paths in one image
     * is deliberate: the A/B measurement then varies a JVM flag rather than the build, which removes
     * image-build differences as a confounder.
     */
    public static boolean isEnabled() {
        return !"false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, "true"));
    }

    /**
     * Inserts {@code items} into {@code table}, returning the row count ClickHouse reports as
     * written — the server's own {@code NUM_ROWS_WRITTEN}, not {@code items.size()}, so that
     * engine-side deduplication or truncation is visible to the caller rather than silently
     * assumed away.
     */
    public <T> Mono<Long> insert(@NonNull String table,
            @NonNull String logComment,
            @NonNull Collection<T> items,
            @NonNull BiConsumer<StringBuilder, T> rowWriter) {

        if (items.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.fromCallable(() -> {
            var body = new StringBuilder();
            items.forEach(item -> rowWriter.accept(body, item));
            byte[] payload = body.toString().getBytes(StandardCharsets.UTF_8);

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

            try (InsertResponse response = clickHouseClient
                    .insert(table, new ByteArrayInputStream(payload), ClickHouseFormat.JSONEachRow, settings)
                    .get()) {
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
