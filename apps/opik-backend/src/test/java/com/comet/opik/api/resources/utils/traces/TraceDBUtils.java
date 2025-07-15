package com.comet.opik.api.resources.utils.traces;

import com.comet.opik.api.Trace;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import reactor.core.publisher.Mono;

public class TraceDBUtils {

    public static void createTraceViaDB(Trace trace, String workspaceId, TransactionTemplateAsync templateAsync) {

        String sql = """
                INSERT INTO traces (
                    id,
                    project_id,
                    workspace_id,
                    name,
                    start_time,
                    end_time,
                    input,
                    output,
                    metadata,
                    tags,
                    created_at,
                    last_updated_at,
                    created_by,
                    last_updated_by,
                    thread_id
                )
                SELECT
                    :id,
                    :project_id,
                    :workspace_id,
                    :name,
                    parseDateTime64BestEffort(:start_time, 9),
                    parseDateTime64BestEffort(:end_time, 9),
                    :input,
                    :output,
                    :metadata,
                    :tags,
                    if(:created_at IS NULL, now(), parseDateTime64BestEffort(:created_at, 9)),
                    if(:last_updated_at IS NULL, NULL, parseDateTime64BestEffort(:last_updated_at, 6)),
                    if(:created_by IS NULL, toString(generateUUIDv4()), :created_by),
                    if(:last_updated_by IS NULL, toString(generateUUIDv4()), :last_updated_by),
                    :thread_id
                ;
                """;
        templateAsync.nonTransaction(connection -> {
            Statement statement = connection.createStatement(sql);

            statement.bind("id", trace.id())
                    .bind("project_id", trace.projectId())
                    .bind("name", trace.name())
                    .bind("start_time", trace.startTime().toString())
                    .bind("end_time", trace.endTime().toString())
                    .bind("input", trace.input().toString())
                    .bind("output", trace.output().toString())
                    .bind("metadata", trace.metadata().toString())
                    .bind("tags", trace.tags().toArray())
                    .bind("thread_id", trace.threadId());

            if (trace.createdAt() != null) {
                statement.bind("created_at", trace.createdAt().toString());
            } else {
                statement.bindNull("created_at", String.class);
            }

            if (trace.lastUpdatedAt() != null) {
                statement.bind("last_updated_at", trace.lastUpdatedAt().toString());
            } else {
                statement.bindNull("last_updated_at", String.class);
            }

            if (trace.createdBy() != null) {
                statement.bind("created_by", trace.createdBy());
            } else {
                statement.bindNull("created_by", String.class);
            }

            if (trace.lastUpdatedBy() != null) {
                statement.bind("last_updated_by", trace.lastUpdatedBy());
            } else {
                statement.bindNull("last_updated_by", String.class);
            }

            Mono<? extends Result> from = Mono.from(statement
                    .bind("workspace_id", workspaceId)
                    .execute());
            return from;
        }).block();
    }
}
