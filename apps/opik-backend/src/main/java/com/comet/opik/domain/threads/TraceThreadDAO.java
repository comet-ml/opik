package com.comet.opik.domain.threads;

import com.comet.opik.api.TraceThreadSampling;
import com.comet.opik.api.TraceThreadStatus;
import com.comet.opik.api.TraceThreadUpdate;
import com.comet.opik.api.events.ProjectWithPendingClosureTraceThreads;
import com.comet.opik.domain.TagOperations;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
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
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.Segment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.endSegment;
import static com.comet.opik.infrastructure.instrumentation.InstrumentAsyncUtils.startSegment;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(TraceThreadDAOImpl.class)
public interface TraceThreadDAO {

    Mono<Long> save(List<TraceThreadModel> traceThreads);

    Mono<List<TraceThreadModel>> findThreadsByProject(int page, int size, TraceThreadCriteria criteria);

    Flux<ProjectWithPendingClosureTraceThreads> findProjectsWithPendingClosureThreads(Instant now,
            Duration defaultTimeoutToMarkThreadAsInactive, int limit);

    Mono<Long> openThread(UUID projectId, String threadId);

    Mono<Long> closeThread(UUID projectId, Set<String> threadId);

    Mono<TraceThreadModel> findByThreadModelId(UUID threadModelId, UUID projectId);

    Mono<UUID> getProjectIdFromThread(UUID id);

    Mono<Long> updateThreadSampledValues(UUID projectId, List<TraceThreadSampling> threadSamplingPerRules);

    Mono<Void> updateThread(UUID threadModelId, UUID projectId, TraceThreadUpdate threadUpdate);

    Mono<Long> setScoredAt(UUID projectId, List<String> threadIds, Instant scoredAt);

    Flux<List<TraceThreadModel>> streamPendingClosureThreads(UUID projectId, Instant lastUpdatedAt);

    record ThreadIdWithTagsAndMetadata(UUID id, Set<String> tags, UUID projectId) {
    }

    Mono<Void> bulkUpdate(@NonNull List<UUID> ids, @NonNull TraceThreadUpdate update, boolean mergeTags);
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
            )
            SELECT
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
            <if(thread_ids)> AND thread_id IN :thread_ids <endif>
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
            LEFT JOIN workspace_configurations wc final ON tt.workspace_id = wc.workspace_id
            WHERE tt.status = 'active'
            AND tt.last_updated_at < parseDateTime64BestEffort(:now, 6) - INTERVAL IF(wc.timeout_mark_thread_as_inactive > 0 , wc.timeout_mark_thread_as_inactive, :default_timeout_seconds) SECOND
            ORDER BY tt.last_updated_at
            LIMIT :limit
            """;

    private static final String OPEN_CLOSURE_THREADS_SQL = """
            INSERT INTO trace_threads(workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at, tags, sampling_per_rule, scored_at)
            SELECT
                workspace_id, project_id, thread_id, id, :status AS new_status, created_by, :user_name, created_at, now64(6), tags, sampling_per_rule, NULL
            FROM trace_threads tt final
            WHERE tt.workspace_id = :workspace_id
            AND tt.project_id = :project_id
            AND tt.status != :status
            <if(thread_ids)>AND tt.thread_id IN :thread_ids<endif>
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
                new_tt.workspace_id,
                new_tt.project_id,
                new_tt.thread_id,
                new_tt.id,
                if(empty(tt.thread_id), new_tt.status, tt.status) AS status,
                if(empty(tt.thread_id), new_tt.created_by, tt.created_by) AS created_by,
                :user_name AS last_updated_by,
                if(empty(tt.thread_id), new_tt.created_at, tt.created_at) AS created_at,
                now64(6) AS last_updated_at,
                if(empty(tt.thread_id), new_tt.tags, tt.tags) AS tags,
                new_tt.sampling_per_rule AS sampling_per_rule,
                if(empty(tt.thread_id), new_tt.scored_at, tt.scored_at) AS scored_at
            FROM (
                <items:{item |
                    SELECT
                        :workspace_id AS workspace_id,
                        :project_id<item.index> AS project_id,
                        :thread_id<item.index> AS thread_id,
                        :thread_model_id<item.index> AS id,
                        :status<item.index> AS status,
                        :created_by<item.index> AS created_by,
                        :user_name AS last_updated_by,
                        parseDateTime64BestEffort(:created_at<item.index>, 9) AS created_at,
                        now64(6) AS last_updated_at,
                        :tags<item.index> AS tags,
                        mapFromArrays(:rule_ids<item.index>, :sampling<item.index>) AS sampling_per_rule,
                        :scored_at<item.index> AS scored_at
                    <if(item.hasNext)>UNION ALL<endif>
                }>
            ) as new_tt
            LEFT JOIN (
                SELECT
                    tt.workspace_id AS workspace_id,
                    tt.project_id AS project_id,
                    tt.thread_id AS thread_id,
                    tt.id AS id,
                    tt.status AS status,
                    tt.created_by AS created_by,
                    :user_name AS last_updated_by,
                    tt.created_at AS created_at,
                    now64(6) AS last_updated_at,
                    tt.tags AS tags,
                    tt.sampling_per_rule AS sampling_per_rule,
                    tt.scored_at AS scored_at
                FROM trace_threads tt final
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                AND id IN :ids
            ) AS tt
            ON new_tt.id = tt.id
            AND new_tt.workspace_id = tt.workspace_id
            AND new_tt.project_id = tt.project_id
            AND new_tt.thread_id = tt.thread_id
            ;
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
                workspace_id, project_id, thread_id, id, status, created_by, last_updated_by, created_at, last_updated_at, tags, sampling_per_rule, scored_at
            FROM trace_threads final
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND status = :status
            AND last_updated_at < parseDateTime64BestEffort(:last_updated_at, 6)
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull ConnectionFactory connectionFactory;
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

        var template = TemplateUtils.newST(sqlTemplate).add("items", queryItems);

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

        var template = TemplateUtils.newST(FIND_THREADS_BY_PROJECT_SQL);
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
            @NonNull Instant now, @NonNull Duration defaultTimeoutToMarkThreadAsInactive, int limit) {
        return asyncTemplate.stream(connection -> {
            var statement = connection.createStatement(FIND_PENDING_CLOSURE_THREADS_SQL)
                    .bind("now", now.truncatedTo(ChronoUnit.MICROS).toString())
                    .bind("default_timeout_seconds", defaultTimeoutToMarkThreadAsInactive.toSeconds())
                    .bind("limit", limit);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE
                            .mapToProjectWithPendingClosureThreads(row)));
        });
    }

    @Override
    public Mono<Long> openThread(@NonNull UUID projectId, @NonNull String threadId) {
        return asyncTemplate.nonTransaction(connection -> {
            var openThreadsSql = TemplateUtils.newST(OPEN_CLOSURE_THREADS_SQL);
            List<String> threadIds = List.of(threadId);

            openThreadsSql.add("thread_ids", threadIds);

            var statement = connection.createStatement(openThreadsSql.render())
                    .bind("project_id", projectId)
                    .bind("thread_ids", threadIds.toArray(String[]::new))
                    .bind("status", TraceThreadStatus.ACTIVE.getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<Long> closeThread(@NonNull UUID projectId, @NonNull Set<String> threadIds) {
        if (CollectionUtils.isEmpty(threadIds)) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> {
            var closureThreadsSql = TemplateUtils.newST(OPEN_CLOSURE_THREADS_SQL);
            closureThreadsSql.add("thread_ids", threadIds);

            var statement = connection.createStatement(closureThreadsSql.render())
                    .bind("project_id", projectId)
                    .bind("thread_ids", threadIds.toArray(String[]::new))
                    .bind("status", TraceThreadStatus.INACTIVE.getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    public Mono<TraceThreadModel> findByThreadModelId(@NonNull UUID threadModelId, @NonNull UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> {
            var template = TemplateUtils.newST(FIND_THREADS_BY_PROJECT_SQL);

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
            var updateSamplingSql = TemplateUtils.newST(UPDATE_THREAD_SAMPLING_PER_RULE);

            updateSamplingSql.add("items", queryItems);

            var statement = connection.createStatement(updateSamplingSql.render())
                    .bind("project_id", projectId)
                    .bind("ids", threadSamplingPerRules.stream().map(TraceThreadSampling::threadModelId).toList());

            int i = 0;
            for (TraceThreadSampling sampling : threadSamplingPerRules) {
                UUID threadModelId = sampling.threadModelId();
                TraceThreadModel traceThreadModel = sampling.traceThread();

                UUID[] ruleIds = sampling.samplingPerRule().keySet()
                        .toArray(UUID[]::new);

                Boolean[] samplingValues = Arrays.stream(ruleIds)
                        .map(ruleId -> sampling.samplingPerRule().get(ruleId))
                        .toArray(Boolean[]::new);

                statement.bind("thread_model_id" + i, threadModelId);
                statement.bind("rule_ids" + i, ruleIds);
                statement.bind("sampling" + i, samplingValues);
                statement.bind("project_id" + i, traceThreadModel.projectId());
                statement.bind("thread_id" + i, traceThreadModel.threadId());
                statement.bind("status" + i, traceThreadModel.status().getValue());
                statement.bind("created_by" + i, traceThreadModel.createdBy());
                statement.bind("created_at" + i, traceThreadModel.createdAt().toString());
                statement.bind("tags" + i, traceThreadModel.tags() != null
                        ? traceThreadModel.tags().toArray(String[]::new)
                        : new String[]{});

                if (traceThreadModel.scoredAt() != null) {
                    statement.bind("scored_at" + i, traceThreadModel.scoredAt().toString());
                } else {
                    statement.bindNull("scored_at" + i, Instant.class);
                }

                i++;
            }

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    public Mono<Void> updateThread(@NonNull UUID threadModelId, @NonNull UUID projectId,
            @NonNull TraceThreadUpdate threadUpdate) {
        return asyncTemplate.nonTransaction(connection -> {

            var template = TemplateUtils.newST(UPDATE_THREAD_SQL);

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
    public Flux<List<TraceThreadModel>> streamPendingClosureThreads(@NonNull UUID projectId,
            @NonNull Instant lastUpdatedAt) {
        return asyncTemplate.stream(connection -> {

            var statement = connection.createStatement(GET_RECENT_CLOSED_THREADS_PER_PROJECT)
                    .bind("project_id", projectId)
                    .bind("last_updated_at", lastUpdatedAt.truncatedTo(ChronoUnit.MICROS).toString())
                    .bind("status", TraceThreadStatus.ACTIVE.getValue());

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, rowMetadata) -> TraceThreadMapper.INSTANCE.mapFromRow(row)));
        }).buffer(1000);
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
            statement.bind("ids", criteria.ids().toArray(UUID[]::new));
        }

        if (CollectionUtils.isNotEmpty(criteria.threadIds())) {
            statement.bind("thread_ids", criteria.threadIds().toArray(String[]::new));
        }

        if (criteria.projectId() != null) {
            statement.bind("project_ids", List.of(criteria.projectId()).toArray(UUID[]::new));
        }

        if (criteria.status() != null) {
            statement.bind("status", criteria.status().getValue());
        }
    }

    private static final String BULK_UPDATE = """
            INSERT INTO trace_threads (
                workspace_id,
                project_id,
                thread_id,
                id,
                status,
                created_by,
                last_updated_by,
                created_at,
                last_updated_at,
                tags,
                sampling_per_rule,
                scored_at
            )
            SELECT
                tt.workspace_id,
                tt.project_id,
                tt.thread_id,
                tt.id,
                tt.status,
                tt.created_by,
                tt.last_updated_by,
                tt.created_at,
                now64(6) as last_updated_at,
                """ + TagOperations.tagUpdateFragment("tt.tags") + """
            as tags,
                           tt.sampling_per_rule,
                           tt.scored_at
                       FROM trace_threads tt
                       WHERE tt.id IN :ids AND tt.workspace_id = :workspace_id
                       ORDER BY (tt.workspace_id, tt.project_id, tt.thread_id, tt.id) DESC, tt.last_updated_at DESC
                       LIMIT 1 BY tt.id
                       SETTINGS short_circuit_function_evaluation = 'force_enable';
                       """;

    @Override
    public Mono<Void> bulkUpdate(@NonNull List<UUID> ids, @NonNull TraceThreadUpdate update, boolean mergeTags) {
        Preconditions.checkArgument(!ids.isEmpty(), "ids must not be empty");
        log.info("Bulk updating '{}' thread models", ids.size());

        var template = newBulkUpdateTemplate(update, BULK_UPDATE, mergeTags);
        var query = template.render();

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(query)
                            .bind("ids", ids);

                    bindBulkUpdateParams(update, statement);

                    Segment segment = startSegment("trace_threads", "Clickhouse", "bulk_update");

                    return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                            .doFinally(signalType -> endSegment(segment));
                })
                .then()
                .doOnSuccess(__ -> log.info("Completed bulk update for '{}' thread models", ids.size()));
    }

    private ST newBulkUpdateTemplate(TraceThreadUpdate update, String sql, boolean mergeTags) {
        var template = TemplateUtils.newST(sql);

        TagOperations.configureTagTemplate(template, update, mergeTags);

        return template;
    }

    private void bindBulkUpdateParams(TraceThreadUpdate update, Statement statement) {
        TagOperations.bindTagParams(statement, update);
    }
}
