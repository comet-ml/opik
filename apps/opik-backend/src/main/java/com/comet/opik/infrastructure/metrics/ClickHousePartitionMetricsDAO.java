package com.comet.opik.infrastructure.metrics;

import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Pattern;

/**
 * Read-only ClickHouse DAO backing the partition-health observability metrics (OPIK-6904,
 * Section 11.1). Sources per-(table, partition) size, row, part and activity data from
 * {@code system.parts}, and lightweight-deleted (LWD-masked) row counts from the tables
 * themselves. Everything is {@code active}-parts-only and scoped to the analytics database.
 *
 * <p>The tiered-storage queries read {@code system.query_log} and {@code system.part_log} on every
 * replica, over a recent window, for reads of the cold-tier tables and failed part moves.
 *
 * <p>This is infrastructure observability, not application domain: it reports on the storage
 * engine's internal state rather than any business entity.
 */
@ImplementedBy(ClickHousePartitionMetricsDAOImpl.class)
public interface ClickHousePartitionMetricsDAO {

    @Builder(toBuilder = true)
    record PartitionStat(String table, String partition, long parts, long rows, long bytes,
            long maxPartBytes, long lastActivityEpochSeconds, long coldBytes, long ttlMoveDueParts) {
    }

    @Builder(toBuilder = true)
    record LwdStat(String table, String partition, long lwdRows) {
    }

    /** Finished SELECTs on a tiered table; {@code cold*} counts only those that read the cold tier. */
    @Builder(toBuilder = true)
    record TieredReadStat(String table, long queries, long p99Millis, long coldQueries, long coldP99Millis) {
    }

    /** {@code code} is the ClickHouse error name, e.g. {@code S3_ERROR}. */
    @Builder(toBuilder = true)
    record TieredReadFailureStat(String table, String code, long failedQueries) {
    }

    @Builder(toBuilder = true)
    record MoveErrorStat(String table, long moveErrors) {
    }

    Mono<List<PartitionStat>> getPartitionStats();

    /** LWD-masked row counts per partition. A table whose scan fails contributes no stats; the others are unaffected. */
    Mono<List<LwdStat>> getLwdRowCounts(List<String> tables);

    /**
     * Reads of tables whose storage policy has an object-storage disk, from {@code system.query_log} on every replica,
     * over the last {@code window}.
     */
    Mono<List<TieredReadStat>> getTieredReadStats(Duration window);

    /** Queries on tiered tables that failed with a cold-read error code, over the last {@code window}. */
    Mono<List<TieredReadFailureStat>> getTieredReadFailures(Duration window);

    /** Failed part moves (e.g. hot to cold) from {@code system.part_log} on every replica, over the last {@code window}. */
    Mono<List<MoveErrorStat>> getMoveErrors(Duration window);
}

@Slf4j
@Singleton
class ClickHousePartitionMetricsDAOImpl implements ClickHousePartitionMetricsDAO {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9_]+");

    /**
     * Per-(table, partition) aggregate over active parts. All aggregates are cast to Int64 so the
     * r2dbc driver maps them to {@code Long} (raw {@code sum}/{@code count} yield UInt64). The
     * partition key uses {@code partition_id} so it aligns with the {@code _partition_id} virtual
     * column used by the LWD query.
     *
     * <p>{@code ttl_move_due_parts} counts only past {@code YYYYMMDD}/{@code YYYYMM} partitions:
     * {@code system.parts} shows TTL times as 32-bit {@code DateTime}, so an {@code id_at} past 2106
     * looks expired there while ClickHouse never moves it.
     */
    private static final String PARTITION_STATS_SQL = """
            WITH (SELECT groupArray(name) FROM system.disks WHERE type = 'ObjectStorage') AS object_storage_disks
            SELECT
                table AS table_name,
                partition_id AS partition_id,
                toInt64(count()) AS parts,
                toInt64(sum(rows)) AS rows,
                toInt64(sum(bytes_on_disk)) AS bytes,
                toInt64(max(bytes_on_disk)) AS max_part_bytes,
                toInt64(toUnixTimestamp(max(modification_time))) AS last_activity,
                toInt64(sumIf(bytes_on_disk, has(object_storage_disks, disk_name))) AS cold_bytes,
                toInt64(countIf(NOT has(object_storage_disks, disk_name)
                    AND notEmpty(move_ttl_info.max) AND now() >= arrayMax(move_ttl_info.max)
                    AND match(partition_id, '^[0-9]{6}([0-9]{2})?$')
                    AND substring(formatDateTime(now(), '%Y%m%d'), 1, length(partition_id)) >= partition_id
                )) AS ttl_move_due_parts
            FROM system.parts
            WHERE database = :database_name AND active
            GROUP BY table, partition_id
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Count of LWD-masked rows per partition for a single table. {@code apply_deleted_mask = 0}
     * disables the implicit filter so masked rows are visible to the count; partitions with no
     * masked rows are absent from the result (a 0 series). Reading only the near-constant
     * {@code _row_exists} column keeps the full-table scan cheap despite touching every row
     * (~1.3s on the largest prod table, run once per interval by a single distributed-lock owner).
     *
     * <p>This must execute as the backend's read-write ClickHouse user. {@code apply_deleted_mask}
     * is a per-query setting, and a {@code readonly = 1} user cannot change it — the query then
     * fails with {@code Code 164 (READONLY)}. The setting is mandatory, not optional: without it
     * the masked rows are filtered out and the count is always 0.
     *
     * <p>{@code max_execution_time} and {@code priority} bound the scan so it can never contend with
     * customer query load, even on a pathologically large future partition. The table name is
     * rendered by the template engine after being validated as a plain identifier, so it is never
     * an injection vector.
     */
    private static final String LWD_ROWS_SQL = """
            SELECT
                _partition_id AS partition_id,
                toInt64(count()) AS lwd_rows
            FROM <table>
            WHERE _row_exists = 0
            GROUP BY _partition_id
            SETTINGS apply_deleted_mask = 0, max_execution_time = 30, priority = 100, log_comment = '<log_comment>'
            """;

    /**
     * Tables in the analytics database whose storage policy includes an object-storage (cold tier) disk, qualified as
     * {@code database.table} the way {@code system.query_log.tables} lists them. Evaluated once on the node that
     * answers, then used as a constant by the replica fan-out.
     */
    private static final String TIERED_TABLES_CTE = """
            WITH
                (SELECT groupArray(name) FROM system.disks WHERE type = 'ObjectStorage') AS object_storage_disks,
                (SELECT groupArray(policy_name) FROM system.storage_policies
                    WHERE hasAny(disks, object_storage_disks)) AS tiered_policies,
                (SELECT groupArray(concat(database, '.', name)) FROM system.tables
                    WHERE database = :database_name AND has(tiered_policies, storage_policy)) AS tiered_tables
            """;

    /**
     * The window ends {@code LOG_FLUSH_LAG} in the past so rows still buffered for the system log (7.5 s flush by
     * default) land in the next window instead of being skipped. The end is exclusive so consecutive polls tile time
     * without gaps or overlap; {@code less()} because the templates use {@code <>} as delimiters.
     */
    private static final String WINDOW = """
            event_date >= toDate(now() - INTERVAL <window_start> SECOND)
                AND event_time >= now() - INTERVAL <window_start> SECOND
                AND less(event_time, now() - INTERVAL <window_end> SECOND)
            """;

    private static final Duration LOG_FLUSH_LAG = Duration.ofSeconds(15);

    /**
     * A read through a Distributed table logs a secondary query on the local table, and that secondary query is the
     * one carrying the read's cache counters; the initial query names only the Distributed table, which has no storage
     * policy, so each read is counted once. A read touched the cold tier when the cold disk's cache served any bytes,
     * from the cache or from object storage.
     */
    private static final String TIERED_READ_STATS_SQL = TIERED_TABLES_CTE + """
            SELECT
                qualified_table,
                toInt64(count()) AS queries,
                toInt64(quantile(0.99)(query_duration_ms)) AS p99_ms,
                toInt64(countIf(cold)) AS cold_queries,
                toInt64(if(countIf(cold) = 0, 0, quantileIf(0.99)(query_duration_ms, cold))) AS cold_p99_ms
            FROM (
                SELECT
                    arrayJoin(arrayIntersect(tables, tiered_tables)) AS qualified_table,
                    query_duration_ms,
                    ProfileEvents['CachedReadBufferReadFromSourceBytes']
                        + ProfileEvents['CachedReadBufferReadFromCacheBytes'] > 0 AS cold
                FROM clusterAllReplicas('{cluster}', system.query_log)
                WHERE <window> AND type = 'QueryFinish' AND query_kind = 'Select' AND hasAny(tables, tiered_tables)
            )
            GROUP BY qualified_table
            SETTINGS log_comment = '<log_comment>'
            """;

    /**
     * Error codes a failed cold read surfaces as: CANNOT_READ_ALL_DATA (33), CHECKSUM_DOESNT_MATCH (40),
     * RECEIVED_ERROR_FROM_REMOTE_IO_SERVER (86), CORRUPTED_DATA (246), S3_ERROR (499) and POCO_EXCEPTION (1000).
     * Client disconnects and cancellations are routine on these tables, so the codes are an allowlist.
     */
    private static final String TIERED_READ_FAILURES_SQL = TIERED_TABLES_CTE + """
            SELECT
                qualified_table,
                errorCodeToName(exception_code) AS code,
                toInt64(count()) AS failed_queries
            FROM (
                SELECT arrayJoin(arrayIntersect(tables, tiered_tables)) AS qualified_table, exception_code
                FROM clusterAllReplicas('{cluster}', system.query_log)
                WHERE <window> AND type = 'ExceptionWhileProcessing' AND query_kind = 'Select'
                    AND exception_code IN (33, 40, 86, 246, 499, 1000) AND hasAny(tables, tiered_tables)
            )
            GROUP BY qualified_table, code
            SETTINGS log_comment = '<log_comment>'
            """;

    private static final String MOVE_ERRORS_SQL = """
            SELECT table AS table_name, toInt64(count()) AS move_errors
            FROM clusterAllReplicas('{cluster}', system.part_log)
            WHERE <window> AND event_type = 'MovePart' AND error != 0 AND database = :database_name
            GROUP BY table
            SETTINGS log_comment = '<log_comment>'
            """;

    private final ConnectionFactory connectionFactory;
    private final String databaseName;

    @Inject
    public ClickHousePartitionMetricsDAOImpl(
            @NonNull ConnectionFactory connectionFactory,
            @NonNull @Named("Database Analytics Database Name") String databaseName) {
        this.connectionFactory = connectionFactory;
        this.databaseName = databaseName;
    }

    @Override
    public Mono<List<PartitionStat>> getPartitionStats() {
        String query = TemplateUtils.newST(PARTITION_STATS_SQL)
                .add("log_comment", "partition_metrics_partition_stats")
                .render();
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(query)
                        .bind("database_name", databaseName)
                        .execute())
                .flatMap(result -> result.map((row, rowMetadata) -> PartitionStat.builder()
                        .table(row.get("table_name", String.class))
                        .partition(row.get("partition_id", String.class))
                        .parts(row.get("parts", Long.class))
                        .rows(row.get("rows", Long.class))
                        .bytes(row.get("bytes", Long.class))
                        .maxPartBytes(row.get("max_part_bytes", Long.class))
                        .lastActivityEpochSeconds(row.get("last_activity", Long.class))
                        .coldBytes(row.get("cold_bytes", Long.class))
                        .ttlMoveDueParts(row.get("ttl_move_due_parts", Long.class))
                        .build()))
                .collectList();
    }

    @Override
    public Mono<List<LwdStat>> getLwdRowCounts(@NonNull List<String> tables) {
        return Flux.fromIterable(tables)
                .filter(this::isValidTable)
                .flatMap(table -> {
                    String query = TemplateUtils.newST(LWD_ROWS_SQL)
                            .add("table", table)
                            .add("log_comment", "partition_metrics_lwd_rows")
                            .render();
                    return Mono.from(connectionFactory.create())
                            .flatMapMany(connection -> connection.createStatement(query).execute())
                            .flatMap(result -> result.map((row, rowMetadata) -> LwdStat.builder()
                                    .table(table)
                                    .partition(row.get("partition_id", String.class))
                                    .lwdRows(row.get("lwd_rows", Long.class))
                                    .build()))
                            // Collected per table so a failure (missing table, timeout) drops only this
                            // table's series, never a partial result or the other tables' series.
                            .collectList()
                            .onErrorResume(exception -> {
                                log.warn("ClickHouse partition metrics: LWD row-count scan failed for table '{}'",
                                        table, exception);
                                return Mono.just(List.of());
                            })
                            .flatMapIterable(stats -> stats);
                })
                .collectList();
    }

    @Override
    public Mono<List<TieredReadStat>> getTieredReadStats(@NonNull Duration window) {
        return queryWindow(TIERED_READ_STATS_SQL, "partition_metrics_tiered_read_stats", window,
                (row, rowMetadata) -> TieredReadStat.builder()
                        .table(unqualified(row.get("qualified_table", String.class)))
                        .queries(row.get("queries", Long.class))
                        .p99Millis(row.get("p99_ms", Long.class))
                        .coldQueries(row.get("cold_queries", Long.class))
                        .coldP99Millis(row.get("cold_p99_ms", Long.class))
                        .build());
    }

    @Override
    public Mono<List<TieredReadFailureStat>> getTieredReadFailures(@NonNull Duration window) {
        return queryWindow(TIERED_READ_FAILURES_SQL, "partition_metrics_tiered_read_failures", window,
                (row, rowMetadata) -> TieredReadFailureStat.builder()
                        .table(unqualified(row.get("qualified_table", String.class)))
                        .code(row.get("code", String.class))
                        .failedQueries(row.get("failed_queries", Long.class))
                        .build());
    }

    @Override
    public Mono<List<MoveErrorStat>> getMoveErrors(@NonNull Duration window) {
        return queryWindow(MOVE_ERRORS_SQL, "partition_metrics_move_errors", window,
                (row, rowMetadata) -> MoveErrorStat.builder()
                        .table(row.get("table_name", String.class))
                        .moveErrors(row.get("move_errors", Long.class))
                        .build());
    }

    private <T> Mono<List<T>> queryWindow(String sql, String logComment, Duration window,
            BiFunction<Row, RowMetadata, T> mapper) {
        String query = TemplateUtils.newST(sql)
                .add("window", TemplateUtils.newST(WINDOW)
                        .add("window_start", window.plus(LOG_FLUSH_LAG).toSeconds())
                        .add("window_end", LOG_FLUSH_LAG.toSeconds())
                        .render())
                .add("log_comment", logComment)
                .render();
        return Flux.usingWhen(connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(query)
                        .bind("database_name", databaseName)
                        .execute())
                        .flatMap(result -> result.map(mapper)),
                Connection::close)
                .collectList();
    }

    /** {@code system.query_log} qualifies table names; the gauges use the bare name, as {@code system.parts} does. */
    private static String unqualified(String qualifiedTable) {
        return qualifiedTable.substring(qualifiedTable.indexOf('.') + 1);
    }

    private boolean isValidTable(String table) {
        if (table != null && IDENTIFIER.matcher(table).matches()) {
            return true;
        }
        log.warn("Skipping invalid LWD table name: '{}'", table);
        return false;
    }
}
