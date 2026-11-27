package com.comet.opik.api.resources.utils;

import com.fasterxml.uuid.Generators;
import io.r2dbc.spi.ConnectionFactory;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * {@code trace_topics} rows no production write makes, for the topics tests; their traces come from
 * {@link PostCutoverTraces}. Ids are UUID v7, as Opik's.
 */
@UtilityClass
public class TopicsTraces {

    private static final String INSERT_TOMBSTONE = """
            INSERT INTO trace_topics (workspace_id, project_id, trace_id, trace_created_at, topic_id, source, run_id,
                last_updated_at, is_deleted)
            VALUES (:workspace_id, :project_id, :trace_id, fromUnixTimestamp64Micro(:created_micros, 'UTC'), :topic_id,
                'run', :run_id, now64(6) + INTERVAL 1 DAY, 1)
            """;

    /**
     * A {@code trace_topics} tombstone ({@code is_deleted = 1}) for the trace, a day newer than any row written now, so
     * it is the trace's latest row. No production write sets it; the readers must drop the trace.
     */
    public static void tombstone(@NonNull ConnectionFactory connectionFactory, @NonNull String workspaceId,
            @NonNull UUID projectId, @NonNull UUID traceId, @NonNull Instant traceCreatedAt, @NonNull UUID topicId) {
        Mono.from(connectionFactory.create())
                .flatMapMany(connection -> connection.createStatement(INSERT_TOMBSTONE)
                        .bind("workspace_id", workspaceId)
                        .bind("project_id", projectId.toString())
                        .bind("trace_id", traceId.toString())
                        .bind("created_micros", ChronoUnit.MICROS.between(Instant.EPOCH, traceCreatedAt))
                        .bind("topic_id", topicId.toString())
                        .bind("run_id", Generators.timeBasedEpochGenerator().generate().toString())
                        .execute())
                .flatMap(result -> Flux.from(result.getRowsUpdated()))
                .blockLast();
    }
}
