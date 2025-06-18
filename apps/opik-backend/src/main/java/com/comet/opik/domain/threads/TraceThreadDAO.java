package com.comet.opik.domain.threads;

import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(TraceThreadDAOImpl.class)
interface TraceThreadDAO {

    Mono<Long> save(List<TraceThreadModel> traceThreads);

    Mono<List<TraceThreadModel>> findThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> findProjectsWithPendingClosureThreads(Instant lastUpdatedUntil,
            int limit);

    Mono<Long> closeThreadWith(@NonNull UUID projectId, @NonNull Instant lastUpdatedUntil);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceThreadDAOImpl implements TraceThreadDAO {

    private static final String INSERT_THREADS_SQL = """
            INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at)
            VALUES
                <items:{item |
                    (
                         :workspace_id,
                         :project_id<item.index>,
                         :thread_id<item.index>,
                         :id<item.index>,
                         :status<item.index>,
                         :created_by<item.index>,
                         :last_updated_by<item.index>,
                         parseDateTime64BestEffort(:created_at<item.index> , 9),
                         parseDateTime64BestEffort(:last_updated_at<item.index> , 6)
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private static final String FIND_THREADS_BY_PROJECT_SQL = """
            SELECT *
            FROM trace_threads
            WHERE workspace_id = :workspace_id
            <if(project_ids)>AND project_id IN :project_ids<endif>
            <if(status)> AND status = :status <endif>
            <if(ids)> AND id IN :ids <endif>
            <if(threadIds)> AND thread_id IN :thread_ids <endif>
            ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            """;

    private static final String FIND_PENDING_CLOSURE_THREADS_SQL = """
            SELECT DISTINCT
                workspace_id,
                project_id,
            FROM trace_threads final
            WHERE status = 'active'
            AND last_updated_at < parseDateTime64BestEffort(:last_updated_at, 6)
            ORDER BY last_updated_at
            LIMIT :limit
            """;

    private static final String CLOSURE_THREADS_SQL = """
            INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at)
            SELECT
                workspace_id, project_id, thread_id, id, 'inactive' AS status, created_by, :user_name, created_at, now64(6)
            FROM trace_threads final
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND last_updated_at < parseDateTime64BestEffort(:last_updated_at, 6)
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    public Mono<Long> save(@NonNull List<TraceThreadModel> traceThreads) {
        if (traceThreads.isEmpty()) {
            return Mono.just(0L);
        }
        return asyncTemplate.nonTransaction(connection -> mapAndInsert(traceThreads, connection, INSERT_THREADS_SQL));
    }

    private Mono<Long> mapAndInsert(List<TraceThreadModel> items, Connection connection, String sqlTemplate) {

        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(items.size());
        var template = new ST(sqlTemplate).add("items", queryItems);

        String sql = template.render();
        var statement = connection.createStatement(sql);

        return makeMonoContextAware((userName, workspaceId) -> {

            statement.bind("workspace_id", workspaceId);

            int i = 0;
            for (TraceThreadModel item : items) {
                statement.bind("id" + i, item.id());
                statement.bind("thread_id" + i, item.threadId());
                statement.bind("project_id" + i, item.projectId());
                statement.bind("status" + i, item.status().getValue());
                statement.bind("created_by" + i, item.createdBy());
                statement.bind("last_updated_by" + i, userName);
                statement.bind("created_at" + i, item.createdAt().toString());
                statement.bind("last_updated_at" + i, item.lastUpdatedAt().toString());
                i++;
            }

            InstrumentAsyncUtils.Segment segment = startSegment("Trace Threads", "Clickhouse",
                    "insert_trace_thread_items");

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(0L, Long::sum)
                    .doFinally(signalType -> endSegment(segment));
        });
    }

    @Override
    public Mono<List<TraceThreadModel>> findThreadsByProject(int page, int size,
            @NonNull TraceThreadCriteria criteria) {

        ST template = new ST(FIND_THREADS_BY_PROJECT_SQL);
        bindTemplateParam(criteria, template);

        int offset = (page - 1) * size;
        template.add("limit", size);
        template.add("offset", offset);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(template.render())
                    .bind("limit", size)
                    .bind("offset", offset);

            bindStatementParam(criteria, statement);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE.mapFromRow(row)))
                    .collectList();
        });
    }

    private void bindTemplateParam(TraceThreadCriteria criteria, ST template) {
        if (CollectionUtils.isNotEmpty(criteria.ids())) {
            template.add("ids", criteria.ids());
        }

        if (CollectionUtils.isNotEmpty(criteria.threadIds())) {
            template.add("thread_ids", criteria.threadIds());
        }

        if (criteria.projectId() != null) {
            template.add("project_ids", List.of(criteria.projectId()));
        }

        if (criteria.status() != null) {
            template.add("status", criteria.status().getValue());
        }
    }

    private void bindStatementParam(TraceThreadCriteria criteria, Statement statement) {
        if (CollectionUtils.isNotEmpty(criteria.ids())) {
            statement.bind("ids", criteria.ids());
        }

        if (CollectionUtils.isNotEmpty(criteria.threadIds())) {
            statement.bind("thread_ids", criteria.threadIds());
        }

        if (criteria.projectId() != null) {
            statement.bind("project_ids", List.of(criteria.projectId()));
        }

        if (criteria.status() != null) {
            statement.bind("status", criteria.status().getValue());
        }
    }

    @Override
    public Flux<ProjectWithPendingClosureTraceThreads> findProjectsWithPendingClosureThreads(
            @NonNull Instant lastUpdatedUntil, int limit) {
        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(FIND_PENDING_CLOSURE_THREADS_SQL)
                    .bind("last_updated_at", lastUpdatedUntil.toString())
                    .bind("limit", limit);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE
                            .mapToProjectWithPendingClosureThreads(row)));
        });
    }

    @Override
    public Mono<Long> closeThreadWith(@NonNull UUID projectId, @NonNull Instant lastUpdatedUntil) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(CLOSURE_THREADS_SQL)
                    .bind("project_id", projectId)
                    .bind("last_updated_at", lastUpdatedUntil.toString());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

}
