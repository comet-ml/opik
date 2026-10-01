package com.comet.opik.api.resources.utils.spans;

import com.comet.opik.api.Span;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import reactor.core.publisher.Mono;

public class SpanDBUtils {

    /** Inserts a span directly, for ids the ingestion window would reject (e.g. a far-future UUIDv7). */
    public static void createSpanViaDB(Span span, String workspaceId, TransactionTemplateAsync templateAsync) {
        String sql = """
                INSERT INTO spans (id, project_id, workspace_id, trace_id, name, type, start_time, end_time,
                    total_estimated_cost, created_by, last_updated_by)
                SELECT :id, :project_id, :workspace_id, :trace_id, :name, :type,
                    parseDateTime64BestEffort(:start_time, 9), parseDateTime64BestEffort(:end_time, 9),
                    toDecimal128(:total_estimated_cost, 12), :created_by, :last_updated_by
                """;
        templateAsync.nonTransaction(connection -> Mono.from(connection.createStatement(sql)
                .bind("id", span.id())
                .bind("project_id", span.projectId())
                .bind("workspace_id", workspaceId)
                .bind("trace_id", span.traceId())
                .bind("name", span.name())
                .bind("type", span.type().toString())
                .bind("start_time", span.startTime().toString())
                .bind("end_time", span.endTime().toString())
                .bind("total_estimated_cost", span.totalEstimatedCost().toString())
                .bind("created_by", span.createdBy())
                .bind("last_updated_by", span.lastUpdatedBy())
                .execute())
                .flatMap(result -> Mono.from(result.getRowsUpdated())))
                .block();
    }
}
