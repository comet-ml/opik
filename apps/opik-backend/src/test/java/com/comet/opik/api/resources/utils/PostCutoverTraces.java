package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import io.r2dbc.spi.ConnectionFactory;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.testcontainers.clickhouse.ClickHouseContainer;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;

/**
 * Migrates a ClickHouse container to the post-cutover {@code traces} topology production runs: the changelog through
 * the shadow-table migration, then the runbook's EXCHANGE and Distributed wrap, then the rest of the changelog. For
 * code that reads only the cut-over {@code traces} (ai-labels); {@code TracesSchemaParityPostCutoverTest} pins the
 * splice itself. Insert fixtures into {@link #SHARD}: inserts through the Distributed wrapper are asynchronous.
 */
@UtilityClass
public class PostCutoverTraces {

    public static final String SHARD = "traces_local";

    private static final String SPLICE_POINT = "000114_recreate_traces_local_v2_id_at_datetime64.sql";

    private static final String INSERT_TRACES = """
            INSERT INTO %s (id, workspace_id, project_id, name, start_time, created_at, source, visibility_mode)
            SELECT row.1, row.2, row.3, 'trace', fromUnixTimestamp64Micro(row.4, 'UTC'),
                fromUnixTimestamp64Micro(row.4, 'UTC'), row.5, row.6
            FROM (SELECT arrayJoin(arrayZip(:ids, :workspace_ids, :project_ids, :micros, :sources, :modes)) AS row)
            """.formatted(SHARD);

    /** One trace row; {@link #shown} is a trace the Logs page lists. */
    @Builder(toBuilder = true)
    public record TraceRow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID id,
            @NonNull Instant createdAt, @NonNull String source, @NonNull String visibilityMode) {

        /** {@code source = sdk}, {@code visibility_mode = default}: the default Logs filters keep it. */
        public static TraceRow shown(String workspaceId, UUID projectId, UUID id, Instant createdAt) {
            return new TraceRow(workspaceId, projectId, id, createdAt, Source.SDK.getValue(),
                    VisibilityMode.DEFAULT.getValue());
        }
    }

    public static void migrate(ClickHouseContainer clickHouse) {
        MigrationUtils.runClickhouseDbMigrationThrough(clickHouse, SPLICE_POINT);
        try (var connection = clickHouse.createConnection(""); var statement = connection.createStatement()) {
            statement.execute("EXCHANGE TABLES %1$s.traces AND %1$s.traces_local_v2 ON CLUSTER '{cluster}'"
                    .formatted(DATABASE_NAME));
            statement.execute(
                    "RENAME TABLE %1$s.traces_local_v2 TO %1$s.traces_pre_cutover_backup ON CLUSTER '{cluster}'"
                            .formatted(DATABASE_NAME));
            statement.execute("""
                    CREATE TABLE %1$s.traces_dist ON CLUSTER '{cluster}' AS %1$s.traces
                    ENGINE = Distributed('{cluster}', '%1$s', '%2$s', sipHash64(project_id))
                    """.formatted(DATABASE_NAME, SHARD));
            statement.execute("""
                    RENAME TABLE %1$s.traces TO %1$s.%2$s, %1$s.traces_dist TO %1$s.traces ON CLUSTER '{cluster}'
                    """.formatted(DATABASE_NAME, SHARD));
        } catch (SQLException exception) {
            throw new IllegalStateException("Failed to cut over and wrap ClickHouse traces", exception);
        }
        MigrationUtils.runClickhouseDbMigration(clickHouse);
    }

    /**
     * Writes the rows into {@link #SHARD}, readable as soon as this returns. For traces the API cannot create: it sets
     * {@code created_at} itself and takes no {@code visibility_mode} (both are read-only in {@code Trace}), so a test
     * that needs a trace on a past day or a hidden one writes it here. Every other test creates its traces through
     * {@code TraceResourceClient}, the write path the product uses.
     */
    public static void insertTraces(@NonNull ConnectionFactory connectionFactory, @NonNull Collection<TraceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(INSERT_TRACES)
                        .bind("ids", rows.stream().map(row -> row.id().toString()).toArray(String[]::new))
                        .bind("workspace_ids", rows.stream().map(TraceRow::workspaceId).toArray(String[]::new))
                        .bind("project_ids",
                                rows.stream().map(row -> row.projectId().toString()).toArray(String[]::new))
                        .bind("micros", rows.stream()
                                .map(row -> ChronoUnit.MICROS.between(Instant.EPOCH, row.createdAt()))
                                .toArray(Long[]::new))
                        .bind("sources", rows.stream().map(TraceRow::source).toArray(String[]::new))
                        .bind("modes", rows.stream().map(TraceRow::visibilityMode).toArray(String[]::new))
                        .execute())
                .flatMap(result -> Flux.from(result.getRowsUpdated()))
                .blockLast();
    }
}
