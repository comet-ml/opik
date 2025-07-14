package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.infrastructure.OpikConfiguration;
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

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(TraceThreadDAOImpl.class)
public interface TraceThreadDAO {

    Mono<Long> save(List<TraceThreadModel> traceThreads);

    Mono<List<TraceThreadModel>> findThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> findProjectsWithPendingClosureThreads(Instant now,
            Duration timeoutToMarkThreadAsInactive,
            int limit);

    Mono<Long> closeThreadWith(UUID projectId, Instant now, Duration timeoutToMarkThreadAsInactive);

    Mono<Long> openThread(UUID projectId, String threadId);

    Mono<Long> closeThread(UUID projectId, String threadId);

    Mono<TraceThreadModel> findByThreadModelId(UUID threadModelId, UUID projectId);

    Mono<UUID> getProjectIdFromThread(UUID id);

    Mono<Long> updateThreadSampledValues(UUID projectId, List<TraceThreadSampling> threadSamplingPerRules);

    Mono<Void> updateThread(UUID threadModelId, UUID projectId, TraceThreadUpdate threadUpdate);

    Mono<Long> setScoredAt(UUID projectId, List<String> threadIds, Instant scoredAt);

    Flux<TraceThreadModel> streamClosedThreads(UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceThreadDAOImpl implements TraceThreadDAO {

    private static final String INSERT_THREADS_SQL = """
            INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at, tags, sampling_per_rule, scored_at)
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
                         parseDateTime64BestEffort(:last_updated_at<item.index> , 6),
                         :tags<item.index>,
                         mapFromArrays(:rule_ids<item.index>, :sampling<item.index>),
                         :scored_at<item.index>
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private static final String UPDATE_THREAD_SQL = """
            INSERT INTO trace_threads (
            	workspace_id, project_id, thread_id, id, status, tags, created_by, last_updated_by, created_at, sampling_per_rule, scored_at
            ) SELECT
                workspace_id,
                project_id,
                thread_id,
                id,
                status,
                <if(tags)> :tags <else> tags <endif> as tags,
                created_by,
                :user_name as last_updated_by,
                created_at,
                sampling_per_rule,
                scored_at
            FROM trace_threads final
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND id = :id
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
            <if(scored_at_empty)> AND scored_at IS NULL <endif>
            ORDER BY (workspace_id, project_id, thread_id, id) DESC, last_updated_at DESC
            LIMIT 1 BY id
            <if(limit)> LIMIT :limit <endif> <if(offset)> OFFSET :offset <endif>
            """;

    private static final String FIND_PENDING_CLOSURE_THREADS_SQL = """
            SELECT DISTINCT
                tt.workspace_id,
                tt.project_id
            FROM trace_threads tt final
            LEFT JOIN project_configurations pc ON tt.workspace_id = pc.workspace_id AND tt.project_id = pc.project_id
            WHERE tt.status = 'active'
            AND (
                (pc.timeout_mark_thread_as_inactive > 0 AND tt.last_updated_at < timestamp_sub(parseDateTime64BestEffort(:now, 6), toIntervalSecond(pc.timeout_mark_thread_as_inactive)))
            OR
                (tt.last_updated_at < parseDateTime64BestEffort(:last_updated_at, 6))
            )
            ORDER BY tt.last_updated_at
            LIMIT :limit
            """;

    private static final String OPEN_CLOSURE_THREADS_SQL = """
            INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at, tags, sampling_per_rule, scored_at)
            SELECT
                workspace_id, project_id, thread_id, id, :status AS new_status, created_by, :user_name, created_at, now64(6), tags, sampling_per_rule, NULL
            FROM trace_threads tt final
            LEFT JOIN project_configurations pc ON tt.workspace_id = pc.workspace_id AND tt.project_id = pc.project_id
            WHERE tt.workspace_id = :workspace_id
            AND tt.project_id = :project_id
            AND tt.status != :status
            <if(last_updated_at)>
            AND (
                (pc.timeout_mark_thread_as_inactive > 0 AND tt.last_updated_at \\< timestamp_sub(parseDateTime64BestEffort(:now, 6), pc.timeout_mark_thread_as_inactive))
            OR
                (tt.last_updated_at \\< parseDateTime64BestEffort(:last_updated_at, 6))
            )
            <endif>
            <if(thread_id)>AND tt.thread_id = :thread_id<endif>
            <if(enforce_consistent_read)>
            SETTINGS
                insert_quorum_parallel = 0,
                insert_quorum = 'auto'
            <endif>
            ;
            """;

    private static final String SELECT_PROJECT_ID_FROM_THREAD = """
            SELECT
                DISTINCT project_id
            FROM trace_threads
            WHERE id = :id
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String UPDATE_THREAD_SAMPLING_PER_RULE = """
                INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at, tags, sampling_per_rule, scored_at)
                SELECT
                    tt.workspace_id,
                    tt.project_id,
                    tt.thread_id,
                    tt.id,
                    tt.status,
                    tt.created_by,
                    :user_name,
                    tt.created_at,
                    now64(6),
                    tt.tags,
                    sd.sampling_per_rule,
                    tt.scored_at
                FROM trace_threads tt final
                JOIN (
                    SELECT
                        thread_model_id,
                        sampling_per_rule
                    FROM (
                        <items:{item |
                            SELECT
                                :thread_model_id<item.index> AS thread_model_id,
                                mapFromArrays(:rule_ids<item.index>, :sampling<item.index>) AS sampling_per_rule
                            <if(item.hasNext)>UNION ALL<endif>
                        }>
                    )
                ) AS sd ON tt.id = sd.thread_model_id
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN :ids
            """;

    private static final String UPDATE_THREAD_SCORED_AT = """
            INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at, tags, sampling_per_rule, scored_at)
            SELECT
                workspace_id,
                project_id,
                thread_id,
                id,
                status,
                created_by,
                :user_name,
                created_at,
                now64(6),
                tags,
                sampling_per_rule,
                parseDateTime64BestEffort(:scored_at, 9)
            FROM trace_threads final
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND thread_id IN :thread_ids
            """;

    public static final String GET_RECENT_CLOSED_THREADS_PER_PROJECT = """
                SELECT
                    *
                FROM trace_threads final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND status = 'inactive'
                AND scored_at IS NULL
                <if(enforce_consistent_read)>
                SETTINGS select_sequential_consistency = 1
                <endif>
                ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull OpikConfiguration configuration;

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

                if (item.tags() != null) {
                    statement.bind("tags" + i, item.tags().toArray(String[]::new));
                } else {
                    statement.bind("tags" + i, new String[]{});
                }

                if (item.sampling() != null) {
                    UUID[] ruleIds = item.sampling().keySet().toArray(UUID[]::new);
                    statement.bind("rule_ids" + i, ruleIds);
                    statement.bind("sampling" + i,
                            Arrays.stream(ruleIds).map(ruleId -> item.sampling().get(ruleId)).toArray(Boolean[]::new));
                } else {
                    statement.bind("rule_ids" + i, new UUID[]{});
                    statement.bind("sampling" + i, new Boolean[]{});
                }

                if (item.scoredAt() != null) {
                    statement.bind("scored_at" + i, item.scoredAt().toString());
                } else {
                    statement.bindNull("scored_at" + i, Instant.class);
                }

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

    @Override
    public Flux<ProjectWithPendingClosureTraceThreads> findProjectsWithPendingClosureThreads(
            @NonNull Instant now, @NonNull Duration timeoutToMarkThreadAsInactive, int limit) {
        return asyncTemplate.stream(connection -> {
            Instant lastUpdatedUntil = now.minus(timeoutToMarkThreadAsInactive).truncatedTo(ChronoUnit.MICROS);
            var statement = connection.createStatement(FIND_PENDING_CLOSURE_THREADS_SQL)
                    .bind("last_updated_at",
                            lastUpdatedUntil.toString())
                    .bind("now", now.truncatedTo(ChronoUnit.MICROS).toString())
                    .bind("limit", limit);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE
                            .mapToProjectWithPendingClosureThreads(row)));
        });
    }

    @Override
    public Mono<Long> closeThreadWith(@NonNull UUID projectId, @NonNull Instant now,
            @NonNull Duration timeoutToMarkThreadAsInactive) {
        return asyncTemplate.nonTransaction(connection -> {
            ST closureThreadsSql = new ST(OPEN_CLOSURE_THREADS_SQL);

            if (shouldEnforceConsistentRead()) {
                closureThreadsSql.add("enforce_consistent_read", true);
            }

            closureThreadsSql.add("last_updated_at", true);
            var statement = connection.createStatement(closureThreadsSql.render())
                    .bind("project_id", projectId)
                    .bind("last_updated_at",
                            now.minus(timeoutToMarkThreadAsInactive).truncatedTo(ChronoUnit.MICROS).toString())
                    .bind("now", now.truncatedTo(ChronoUnit.MICROS).toString())
                    .bind("status", TraceThreadStatus.INACTIVE.getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    private boolean shouldEnforceConsistentRead() {
        return configuration.getDatabaseAnalytics().hasReplicationEnabled();
    }

    @Override
    public Mono<Long> openThread(@NonNull UUID projectId, @NonNull String threadId) {
        return asyncTemplate.nonTransaction(connection -> {
            ST openThreadsSql = new ST(OPEN_CLOSURE_THREADS_SQL);
            openThreadsSql.add("thread_id", threadId);

            if (shouldEnforceConsistentRead()) {
                openThreadsSql.add("enforce_consistent_read", true);
            }

            var statement = connection.createStatement(openThreadsSql.render())
                    .bind("project_id", projectId)
                    .bind("thread_id", threadId)
                    .bind("status", TraceThreadStatus.ACTIVE.getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<Long> closeThread(@NonNull UUID projectId, @NonNull String threadId) {
        return asyncTemplate.nonTransaction(connection -> {
            ST closureThreadsSql = new ST(OPEN_CLOSURE_THREADS_SQL);
            closureThreadsSql.add("thread_id", threadId);

            if (shouldEnforceConsistentRead()) {
                closureThreadsSql.add("enforce_consistent_read", true);
            }

            var statement = connection.createStatement(closureThreadsSql.render())
                    .bind("project_id", projectId)
                    .bind("thread_id", threadId)
                    .bind("status", TraceThreadStatus.INACTIVE.getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<TraceThreadModel> findByThreadModelId(@NonNull UUID threadModelId, @NonNull UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = new ST(FIND_THREADS_BY_PROJECT_SQL);

            List<UUID> threadModelIds = List.of(threadModelId);
            List<UUID> projectIds = List.of(projectId);

            template.add("ids", threadModelIds);
            template.add("project_ids", projectIds);

            var statement = connection.createStatement(template.render())
                    .bind("ids", threadModelIds)
                    .bind("project_ids", projectIds);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono
                            .from(result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE.mapFromRow(row))));
        });
    }

    @Override
    public Mono<UUID> getProjectIdFromThread(@NonNull UUID id) {

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_PROJECT_ID_FROM_THREAD)
                    .bind("id", id);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("project_id", UUID.class)))
                    .singleOrEmpty();
        });
    }

    @Override
    public Mono<Long> updateThreadSampledValues(@NonNull UUID projectId,
            @NonNull List<TraceThreadSampling> threadSamplingPerRules) {
        return asyncTemplate.nonTransaction(connection -> {

            if (threadSamplingPerRules.isEmpty()) {
                return Mono.just(0L);
            }

            List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(threadSamplingPerRules.size());
            ST updateSamplingSql = new ST(UPDATE_THREAD_SAMPLING_PER_RULE);

            updateSamplingSql.add("items", queryItems);

            var statement = connection.createStatement(updateSamplingSql.render())
                    .bind("project_id", projectId)
                    .bind("ids", threadSamplingPerRules.stream().map(TraceThreadSampling::threadModelId).toList());

            int i = 0;
            for (TraceThreadSampling sampling : threadSamplingPerRules) {
                UUID threadModelId = sampling.threadModelId();
                UUID[] ruleIds = sampling.samplingPerRule().keySet()
                        .toArray(UUID[]::new);

                Boolean[] samplingValues = Arrays.stream(ruleIds)
                        .map(ruleId -> sampling.samplingPerRule().get(ruleId))
                        .toArray(Boolean[]::new);

                statement.bind("thread_model_id" + i, threadModelId);
                statement.bind("rule_ids" + i, ruleIds);
                statement.bind("sampling" + i, samplingValues);
                i++;
            }

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    public Mono<Void> updateThread(@NonNull UUID threadModelId, @NonNull UUID projectId,
            @NonNull TraceThreadUpdate threadUpdate) {
        return asyncTemplate.nonTransaction(connection -> {

            var template = new ST(UPDATE_THREAD_SQL);

            Optional.ofNullable(threadUpdate.tags())
                    .ifPresent(tags -> template.add("tags", tags.toString()));

            var statement = connection.createStatement(template.render())
                    .bind("id", threadModelId)
                    .bind("project_id", projectId);

            Optional.ofNullable(threadUpdate.tags())
                    .ifPresent(tags -> statement.bind("tags", tags.toArray(String[]::new)));

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .then();
        });
    }

    @Override
    public Mono<Long> setScoredAt(@NonNull UUID projectId, @NonNull List<String> threadIds, @NonNull Instant scoredAt) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(UPDATE_THREAD_SCORED_AT)
                    .bind("project_id", projectId)
                    .bind("thread_ids", threadIds)
                    .bind("scored_at", scoredAt.toString());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Flux<TraceThreadModel> streamClosedThreads(@NonNull UUID projectId) {
        return asyncTemplate.stream(connection -> {
            ST closedThreadsPerProject = new ST(GET_RECENT_CLOSED_THREADS_PER_PROJECT);

            if (shouldEnforceConsistentRead()) {
                closedThreadsPerProject.add("enforce_consistent_read", true);
            }

            var statement = connection.createStatement(closedThreadsPerProject.render())
                    .bind("project_id", projectId);
            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE.mapFromRow(row)));
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

        template.add("scored_at_empty", criteria.scoredAtEmpty());
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
}
