package com.comet.opik.domain;

import com.google.inject.ImplementedBy;
import io.r2dbc.spi.ConnectionFactory;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.NonNull;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Read-only ClickHouse DAO backing the partition-health observability metrics (OPIK-6904,
 * Section 11.1). Sources per-(table, partition) size, row, part and activity data from
 * {@code system.parts}, and lightweight-deleted (LWD-masked) row counts from the tables
 * themselves. Everything is {@code active}-parts-only and scoped to the analytics database.
 */
@ImplementedBy(ClickHousePartitionMetricsDAOImpl.class)
public interface ClickHousePartitionMetricsDAO {

    @Builder(toBuilder = true)
    record PartitionStat(String table, String partition, long parts, long rows, long bytes,
            long maxPartBytes, long lastActivityEpochSeconds) {
    }

    @Builder(toBuilder = true)
    record LwdStat(String table, String partition, long lwdRows) {
    }

    Mono<List<PartitionStat>> getPartitionStats();

    Mono<List<LwdStat>> getLwdRowCounts(List<String> tables);
}

@Singleton
class ClickHousePartitionMetricsDAOImpl implements ClickHousePartitionMetricsDAO {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z0-9_]+");

    /**
     * Per-(table, partition) aggregate over active parts. All aggregates are cast to Int64 so the
     * r2dbc driver maps them to {@code Long} (raw {@code sum}/{@code count} yield UInt64). The
     * partition key uses {@code partition_id} so it aligns with the {@code _partition_id} virtual
     * column used by the LWD query.
     */
    private static final String PARTITION_STATS_SQL = """
            SELECT
                table AS table_name,
                partition_id AS partition_id,
                toInt64(count()) AS parts,
                toInt64(sum(rows)) AS rows,
                toInt64(sum(bytes_on_disk)) AS bytes,
                toInt64(max(bytes_on_disk)) AS max_part_bytes,
                toInt64(toUnixTimestamp(max(modification_time))) AS last_activity
            FROM system.parts
            WHERE database = :database_name AND active
            GROUP BY table, partition_id
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
     * customer query load, even on a pathologically large future partition.
     */
    private static final String LWD_ROWS_SQL = """
            SELECT
                _partition_id AS partition_id,
                toInt64(count()) AS lwd_rows
            FROM %s
            WHERE _row_exists = 0
            GROUP BY _partition_id
            SETTINGS apply_deleted_mask = 0, max_execution_time = 30, priority = 100
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
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(PARTITION_STATS_SQL)
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
                        .build()))
                .collectList();
    }

    @Override
    public Mono<List<LwdStat>> getLwdRowCounts(@NonNull List<String> tables) {
        return Flux.fromIterable(tables)
                .filter(table -> IDENTIFIER.matcher(table).matches())
                .flatMap(table -> Mono.from(connectionFactory.create())
                        .flatMapMany(connection -> connection.createStatement(LWD_ROWS_SQL.formatted(table))
                                .execute())
                        .flatMap(result -> result.map((row, rowMetadata) -> LwdStat.builder()
                                .table(table)
                                .partition(row.get("partition_id", String.class))
                                .lwdRows(row.get("lwd_rows", Long.class))
                                .build())))
                .collectList();
    }
}
