package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Source;
import com.comet.opik.api.VisibilityMode;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.UUID;

/**
 * Trace rows for the topics tests. The topics API and a saved run count only the traces Logs shows, so a test that
 * writes {@code trace_topics} rows or vectors writes their traces too. Straight into {@code table}: the shard
 * ({@link PostCutoverTraces#SHARD}) after the cut-over, {@code traces} otherwise.
 */
@UtilityClass
public class TopicsTraces {

    private static final String INSERT = """
            INSERT INTO %s (id, workspace_id, project_id, name, start_time, created_at, source, visibility_mode)
            SELECT row.1, row.2, row.3, 'topics', fromUnixTimestamp64Micro(row.4, 'UTC'),
                fromUnixTimestamp64Micro(row.4, 'UTC'), row.5, row.6
            FROM (SELECT arrayJoin(arrayZip(:ids, :workspace_ids, :project_ids, :micros, :sources, :modes)) AS row)
            """;

    private static final String INSERT_TOMBSTONE = """
            INSERT INTO trace_topics (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id,
                last_updated_at, is_deleted)
            VALUES (:workspace_id, :project_id, :trace_id, fromUnixTimestamp64Micro(:created_micros, 'UTC'), :topic_id,
                'run', :run_id, now64(6) + INTERVAL 1 DAY, 1)
            """;

    /** One trace; {@link #shown} is a trace Logs lists. */
    @Builder(toBuilder = true)
    public record TraceRow(@NonNull String workspaceId, @NonNull UUID projectId, @NonNull UUID id,
            @NonNull Instant createdAt, @NonNull String source, @NonNull String visibilityMode) {

        /** {@code source = sdk}, {@code visibility_mode = default}: the default Logs filters keep it. */
        public static TraceRow shown(String workspaceId, UUID projectId, UUID id, Instant createdAt) {
            return new TraceRow(workspaceId, projectId, id, createdAt, Source.SDK.getValue(),
                    VisibilityMode.DEFAULT.getValue());
        }
    }

    public static void insert(@NonNull ConnectionFactory connectionFactory, @NonNull String table,
            @NonNull Collection<TraceRow> rows) {
        if (rows.isEmpty()) {
            return;
        }
        // usingWhen closes the connection on completion, error and cancellation.
        Flux.usingWhen(connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(INSERT.formatted(table))
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
                        .flatMap(result -> Flux.from(result.getRowsUpdated())),
                Connection::close)
                .blockLast();
    }

    /**
     * A {@code trace_topics} tombstone ({@code is_deleted = 1}) for the trace, a day newer than any row written now, so
     * it is the trace's latest row. No production write sets it; the readers must drop the trace.
     */
    public static void tombstone(@NonNull ConnectionFactory connectionFactory, @NonNull String workspaceId,
            @NonNull UUID projectId, @NonNull UUID traceId, @NonNull Instant traceCreatedAt, @NonNull UUID topicId) {
        Flux.usingWhen(connectionFactory.create(),
                connection -> Flux.from(connection.createStatement(INSERT_TOMBSTONE)
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("trace_id", traceId.toString())
                        .bind("created_micros", ChronoUnit.MICROS.between(Instant.EPOCH, traceCreatedAt))
                        .bind("topic_id", topicId.toString())
                        .bind("run_id", UUID.randomUUID().toString())
                        .execute())
                        .flatMap(result -> Flux.from(result.getRowsUpdated())),
                Connection::close)
                .blockLast();
    }
}
