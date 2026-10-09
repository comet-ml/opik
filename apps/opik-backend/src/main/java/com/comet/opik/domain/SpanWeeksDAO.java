package com.comet.opik.domain;

import com.comet.opik.utils.template.TemplateUtils;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.FilterUtils.getSTWithLogComment;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

/**
 * Writes and reads span_weeks, the index of which weekly partitions of the spans table each trace's spans occupy.
 * Plain {@code FORMAT Values} inserts, so they take the async-insert path like every other listener DAO; repeated
 * registrations of the same (trace, week) are collapsed by the ReplacingMergeTree.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class SpanWeeksDAO {

    private static final String INSERT = """
            INSERT INTO span_weeks (workspace_id, project_id, trace_id, id_week)
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
                <items:{item |
                    (:workspace_id, :project_id<item.index>, :trace_id<item.index>, :id_week<item.index>)
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private static final String FIND_BY_TRACE_IDS = """
            SELECT DISTINCT project_id, trace_id, id_week
            FROM span_weeks
            WHERE workspace_id = :workspace_id
            AND trace_id IN :trace_ids
            SETTINGS log_comment = '<log_comment>'
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    public Mono<Long> insert(@NonNull List<SpanWeek> rows) {
        if (rows.isEmpty()) {
            return Mono.just(0L);
        }
        return makeMonoContextAware((userName, workspaceId) -> Mono.from(connectionFactory.create())
                .flatMapMany(connection -> insert(rows, workspaceId, userName, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum));
    }

    private Publisher<? extends Result> insert(List<SpanWeek> rows, String workspaceId, String userName,
            Connection connection) {
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(rows.size());
        ST template = getSTWithLogComment(INSERT, "insert_span_weeks", workspaceId, userName, rows.size());
        template.add("items", queryItems);
        Statement statement = connection.createStatement(template.render());

        // Positional binds, as in CipxTraceIdentityDAO: workspace_id once at 0, then three per row in template order.
        statement.bind(0, workspaceId);
        int index = 1;
        for (SpanWeek row : rows) {
            statement.bind(index++, row.projectId().toString())
                    .bind(index++, row.traceId().toString())
                    .bind(index++, row.idWeek());
        }
        return statement.execute();
    }

    public Mono<List<SpanWeek>> findByTraceIds(@NonNull Collection<UUID> traceIds) {
        return makeMonoContextAware((userName, workspaceId) -> Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    ST template = getSTWithLogComment(FIND_BY_TRACE_IDS, "find_span_weeks_by_trace_ids", workspaceId,
                            userName, traceIds.size());
                    return Flux.from(connection.createStatement(template.render())
                            .bind("workspace_id", workspaceId)
                            .bind("trace_ids", traceIds.stream().map(UUID::toString).toArray(String[]::new))
                            .execute());
                })
                .flatMap(result -> result.map((row, metadata) -> SpanWeek.builder()
                        .projectId(row.get("project_id", UUID.class))
                        .traceId(row.get("trace_id", UUID.class))
                        .idWeek(row.get("id_week", Long.class))
                        .build()))
                .collectList());
    }
}
