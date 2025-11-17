package com.comet.opik.domain;

import com.comet.opik.api.AlertEventType;
import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.AlertEventType.TRACE_FEEDBACK_SCORE;
import static com.comet.opik.api.AlertEventType.TRACE_THREAD_FEEDBACK_SCORE;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(FeedbackScoreDAOImpl.class)
public interface FeedbackScoreDAO {

    Mono<Long> scoreEntity(EntityType entityType, UUID entityId, FeedbackScore score, UUID projectId,
            @Nullable String author);

    Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, DeleteFeedbackScore score);

    Mono<Void> deleteByEntityIds(EntityType entityType, Set<UUID> entityIds, UUID projectId);

    Mono<Long> deleteByEntityIdAndNames(EntityType entityType, UUID entityId, Set<String> names, String author);

    Mono<Long> scoreBatchOf(EntityType entityType, List<? extends FeedbackScoreItem> scores, @Nullable String author);

    Mono<Long> scoreBatchOfThreads(List<FeedbackScoreBatchItemThread> scores, @Nullable String author);

    Mono<List<String>> getTraceFeedbackScoreNames(UUID projectId);

    Mono<List<String>> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type);

    Mono<List<String>> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds);

    Mono<List<String>> getProjectsFeedbackScoreNames(Set<UUID> projectIds);

    Mono<List<String>> getProjectsTraceThreadsFeedbackScoreNames(List<UUID> projectId);

    Mono<Long> deleteThreadManualScores(Set<UUID> threadModelIds, UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class FeedbackScoreDAOImpl implements FeedbackScoreDAO {

    private static final String BULK_INSERT_FEEDBACK_SCORE = """
            INSERT INTO <if(author)>authored_feedback_scores<else>feedback_scores<endif>(
                entity_type,
                entity_id,
                project_id,
                workspace_id,
                name,
                category_name,
                value,
                reason,
                source,
                <if(author)>author,<endif>
                created_by,
                last_updated_by
            )
            VALUES
                <items:{item |
                    (
                         :entity_type<item.index>,
                         :entity_id<item.index>,
                         :project_id<item.index>,
                         :workspace_id,
                         :name<item.index>,
                         :category_name<item.index>,
                         :value<item.index>,
                         :reason<item.index>,
                         :source<item.index>,
                         <if(author)>:author<item.index>,<endif>
                         :user_name,
                         :user_name
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private static final String DELETE_FEEDBACK_SCORE = """
            DELETE FROM <table_name>
            WHERE entity_id = :entity_id
            AND entity_type = :entity_type
            AND name = :name
            AND workspace_id = :workspace_id
            <if(author)>AND <author> = :author<endif>
            """;

    private static final String DELETE_SPANS_CASCADE_FEEDBACK_SCORE = """
            DELETE FROM <table_name>
            WHERE entity_type = 'span'
            AND entity_id IN (
                SELECT id
                FROM spans
                WHERE trace_id IN :trace_ids
                AND workspace_id = :workspace_id
                <if(project_id)>AND project_id = :project_id<endif>
            )
            AND workspace_id = :workspace_id
            <if(project_id)>AND project_id = :project_id<endif>
            SETTINGS allow_nondeterministic_mutations = 1
            """;

    private static final String DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS = """
            DELETE FROM <table_name>
            WHERE entity_id IN :entity_ids
            AND entity_type = :entity_type
            AND workspace_id = :workspace_id
            <if(author)>AND <author> = :author<endif>
            <if(names)>AND name IN :names <endif>
            <if(project_id)>AND project_id = :project_id<endif>
            <if(sources)>AND source IN :sources<endif>
            """;

    private static final String SELECT_TRACE_FEEDBACK_SCORE_NAMES = """
            SELECT
                distinct name
            FROM (
                SELECT
                    name
                FROM feedback_scores
                WHERE workspace_id = :workspace_id
                <if(project_ids)>
                AND project_id IN :project_ids
                <endif>
                <if(with_experiments_only)>
                AND entity_id IN (
                    SELECT
                        trace_id
                    FROM (
                        SELECT
                            id
                        FROM experiments
                        WHERE workspace_id = :workspace_id
                        ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) AS e
                    INNER JOIN (
                        SELECT
                            experiment_id,
                            trace_id
                        FROM experiment_items
                        WHERE workspace_id = :workspace_id
                        <if(experiment_ids)>
                        AND experiment_id IN :experiment_ids
                        <endif>
                        ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) ei ON e.id = ei.experiment_id
                )
                <endif>
                AND entity_type = :entity_type
                ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
                UNION ALL
                SELECT
                    name
                FROM authored_feedback_scores
                WHERE workspace_id = :workspace_id
                <if(project_ids)>
                AND project_id IN :project_ids
                <endif>
                <if(with_experiments_only)>
                AND entity_id IN (
                    SELECT
                        trace_id
                    FROM (
                        SELECT
                            id
                        FROM experiments
                        WHERE workspace_id = :workspace_id
                        ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) AS e
                    INNER JOIN (
                        SELECT
                            experiment_id,
                            trace_id
                        FROM experiment_items
                        WHERE workspace_id = :workspace_id
                        <if(experiment_ids)>
                        AND experiment_id IN :experiment_ids
                        <endif>
                        ORDER BY (workspace_id, experiment_id, dataset_item_id, trace_id, id) DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) ei ON e.id = ei.experiment_id
                )
                <endif>
                AND entity_type = :entity_type
                ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, author, name
            ) AS names
            ;
            """;

    private static final String SELECT_PROJECTS_FEEDBACK_SCORE_NAMES = """
            SELECT
                distinct name
            FROM (
                SELECT
                    name
                FROM feedback_scores
                WHERE workspace_id = :workspace_id
                <if(project_ids)>
                AND project_id IN :project_ids
                <endif>
                ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
                UNION ALL
                SELECT
                    name
                FROM authored_feedback_scores
                WHERE workspace_id = :workspace_id
                <if(project_ids)>
                AND project_id IN :project_ids
                <endif>
                ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, author, name
            ) AS names
            ;
            """;

    private static final String SELECT_SPAN_FEEDBACK_SCORE_NAMES = """
            SELECT
                distinct name
            FROM (
                SELECT
                    name
                FROM feedback_scores
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(type)>
                AND entity_id IN (
                    SELECT
                        id
                    FROM spans
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND type = :type
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
                AND entity_type = 'span'
                ORDER BY (workspace_id, project_id, entity_type, entity_id, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
                UNION ALL
                SELECT
                    name
                FROM authored_feedback_scores
                WHERE workspace_id = :workspace_id
                AND project_id = :project_id
                <if(type)>
                AND entity_id IN (
                    SELECT
                        id
                    FROM spans
                    WHERE workspace_id = :workspace_id
                    AND project_id = :project_id
                    AND type = :type
                    ORDER BY (workspace_id, project_id, trace_id, parent_span_id, id) DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
                AND entity_type = 'span'
                ORDER BY (workspace_id, project_id, entity_type, entity_id, author, name) DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, author, name
            ) AS names
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull EventBus eventBus;

    @Override
    @WithSpan
    public Mono<Long> scoreEntity(@NonNull EntityType entityType,
            @NonNull UUID entityId,
            @NonNull FeedbackScore score,
            @NonNull UUID projectId, @Nullable String author) {

        FeedbackScoreItem item = FeedbackScoreMapper.INSTANCE.toFeedbackScore(entityId,
                projectId, score);

        return scoreBatchOf(entityType, List.of(item), author);
    }

    private String getValueOrDefault(String value) {
        return Optional.ofNullable(value)
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .orElse("");
    }

    @Override
    @WithSpan
    public Mono<Long> scoreBatchOf(@NonNull EntityType entityType,
            @NonNull List<? extends FeedbackScoreItem> scores, @Nullable String author) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(scores), "Argument 'scores' must not be empty");

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, "");
            String userName = ctx.get(RequestContext.USER_NAME);

            return asyncTemplate.nonTransaction(connection -> {

                var template = TemplateUtils.getBatchSql(BULK_INSERT_FEEDBACK_SCORE, scores.size());
                template.add("author", author);

                var statement = connection.createStatement(template.render());

                bindParameters(entityType, scores, statement, author);

                return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                        .flatMap(Result::getRowsUpdated)
                        .reduce(Long::sum);
            })
                    .doOnSuccess(cnt -> {
                        switch (entityType) {
                            case TRACE ->
                                publishAlertEvent(scores, author, TRACE_FEEDBACK_SCORE, workspaceId, workspaceName,
                                        userName);
                            case THREAD ->
                                publishAlertEvent(scores, author, TRACE_THREAD_FEEDBACK_SCORE, workspaceId,
                                        workspaceName, userName);
                            default -> {
                                // no-op
                            }
                        }
                    });
        });
    }

    private void publishAlertEvent(List<? extends FeedbackScoreItem> scores, String author, AlertEventType eventType,
            String workspaceId, String workspaceName, String userName) {
        if (CollectionUtils.isEmpty(scores)) {
            return;
        }

        var scoresWithAuthor = scores.stream()
                .map(item -> switch (item) {
                    case FeedbackScoreItem.FeedbackScoreBatchItem tracingItem -> tracingItem.toBuilder()
                            .author(author)
                            .build();
                    case FeedbackScoreBatchItemThread threadItem -> threadItem.toBuilder()
                            .author(author)
                            .build();
                }).toList();

        eventBus.post(AlertEvent.builder()
                .eventType(eventType)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .userName(userName)
                .projectId(scores.getFirst().projectId())
                .payload(scoresWithAuthor)
                .build());
    }

    @Override
    public Mono<Long> scoreBatchOfThreads(@NonNull List<FeedbackScoreBatchItemThread> scores, @Nullable String author) {
        return scoreBatchOf(EntityType.THREAD, scores, author);
    }

    private void bindParameters(EntityType entityType, List<? extends FeedbackScoreItem> scores,
            Statement statement, String author) {
        for (var i = 0; i < scores.size(); i++) {

            var feedbackScoreBatchItem = scores.get(i);

            statement.bind("entity_type" + i, entityType.getType())
                    .bind("entity_id" + i, feedbackScoreBatchItem.id())
                    .bind("project_id" + i, feedbackScoreBatchItem.projectId())
                    .bind("name" + i, feedbackScoreBatchItem.name())
                    .bind("value" + i, feedbackScoreBatchItem.value().toString())
                    .bind("source" + i, feedbackScoreBatchItem.source().getValue())
                    .bind("reason" + i, getValueOrDefault(feedbackScoreBatchItem.reason()))
                    .bind("category_name" + i, getValueOrDefault(feedbackScoreBatchItem.categoryName()));

            if (author != null) {
                statement.bind("author" + i, getValueOrDefault(author));
            }
        }
    }

    @Override
    @WithSpan
    public Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, DeleteFeedbackScore score) {

        return asyncTemplate.nonTransaction(connection -> {

            // Delete from feedback_scores table
            var deleteFeedbackScore = TemplateUtils.newST(DELETE_FEEDBACK_SCORE)
                    .add("table_name", "feedback_scores");

            if (StringUtils.isNotBlank(score.author())) {
                deleteFeedbackScore.add("author", "last_updated_by");
            }

            var statement1 = connection.createStatement(deleteFeedbackScore.render());
            statement1
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", score.name());

            if (StringUtils.isNotBlank(score.author())) {
                statement1.bind("author", score.author());
            }

            var deleteNonAuthoredOperation = makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));

            // Delete from authored_feedback_scores table
            var deleteAuthoredFeedbackScore = TemplateUtils.newST(DELETE_FEEDBACK_SCORE)
                    .add("table_name", "authored_feedback_scores");
            Optional.ofNullable(score.author())
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(author -> deleteAuthoredFeedbackScore.add("author", "author"));

            var statement2 = connection.createStatement(deleteAuthoredFeedbackScore.render());
            statement2
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", score.name());
            Optional.ofNullable(score.author())
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(author -> statement2.bind("author", author));

            return deleteNonAuthoredOperation
                    .then(makeMonoContextAware(bindWorkspaceIdToMono(statement2)))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then();
        });
    }

    @Override
    @WithSpan
    public Mono<Void> deleteByEntityIds(
            @NonNull EntityType entityType, Set<UUID> entityIds, UUID projectId) {
        Preconditions.checkArgument(
                CollectionUtils.isNotEmpty(entityIds), "Argument 'entityIds' must not be empty");
        log.info("Deleting feedback scores for entityType '{}', entityIds count '{}'", entityType, entityIds.size());
        return switch (entityType) {
            case TRACE ->
                asyncTemplate.nonTransaction(connection -> cascadeSpanDelete(entityIds, projectId, connection))
                        .flatMap(result -> Mono.from(result.getRowsUpdated()))
                        .then(Mono.defer(() -> asyncTemplate
                                .nonTransaction(connection -> deleteScoresByEntityIds(entityType, entityIds, projectId,
                                        connection))))
                        .then();
            case SPAN, THREAD ->
                asyncTemplate
                        .nonTransaction(
                                connection -> deleteScoresByEntityIds(entityType, entityIds, projectId, connection))
                        .then();
        };
    }

    @Override
    public Mono<Long> deleteByEntityIdAndNames(@NonNull EntityType entityType, @NonNull UUID entityId,
            @NonNull Set<String> names, String author) {

        if (names.isEmpty()) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> {

            // Delete from feedback_scores table
            var template1 = TemplateUtils.newST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template1.add("names", names);
            template1.add("table_name", "feedback_scores");

            if (StringUtils.isNotBlank(author)) {
                template1.add("author", "last_updated_by");
            }

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", Set.of(entityId))
                    .bind("entity_type", entityType.getType())
                    .bind("names", names);

            if (StringUtils.isNotBlank(author)) {
                statement1.bind("author", author);
            }

            var deleteNonAuthoredOperation = makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));

            // Delete from authored_feedback_scores table
            var template2 = TemplateUtils.newST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template2.add("names", names);
            template2.add("table_name", "authored_feedback_scores");
            Optional.ofNullable(author)
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(a -> template2.add("author", "author"));

            var statement2 = connection.createStatement(template2.render())
                    .bind("entity_ids", Set.of(entityId))
                    .bind("entity_type", entityType.getType())
                    .bind("names", names);
            Optional.ofNullable(author)
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(a -> statement2.bind("author", a));

            return deleteNonAuthoredOperation
                    .then(makeMonoContextAware(bindWorkspaceIdToMono(statement2)))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    @WithSpan
    public Mono<List<String>> getTraceFeedbackScoreNames(@NonNull UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> {

            var template = TemplateUtils.newST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

            List<UUID> projectIds = List.of(projectId);

            bindTemplateParam(projectIds, false, null, template);

            var statement = connection.createStatement(template.render());

            bindStatementParam(projectIds, null, statement, EntityType.TRACE);

            return getNames(statement);
        });
    }

    @Override
    @WithSpan
    public Mono<List<String>> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds) {
        return asyncTemplate.nonTransaction(connection -> {

            var template = TemplateUtils.newST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

            bindTemplateParam(null, true, experimentIds, template);

            var statement = connection.createStatement(template.render());

            bindStatementParam(null, experimentIds, statement, EntityType.TRACE);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                    .distinct()
                    .collect(Collectors.toList());
        });
    }

    @Override
    @WithSpan
    public Mono<List<String>> getProjectsFeedbackScoreNames(Set<UUID> projectIds) {
        return asyncTemplate.nonTransaction(connection -> {

            var template = TemplateUtils.newST(SELECT_PROJECTS_FEEDBACK_SCORE_NAMES);

            if (CollectionUtils.isNotEmpty(projectIds)) {
                template.add("project_ids", projectIds);
            }

            var statement = connection.createStatement(template.render());

            if (CollectionUtils.isNotEmpty(projectIds)) {
                statement.bind("project_ids", projectIds);
            }

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                    .collect(Collectors.toList());
        });
    }

    @Override
    public Mono<List<String>> getProjectsTraceThreadsFeedbackScoreNames(@NonNull List<UUID> projectIds) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(projectIds), "Argument 'projectId' must not be empty");

        return asyncTemplate.nonTransaction(connection -> {

            var template = TemplateUtils.newST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

            bindTemplateParam(projectIds, false, null, template);

            var statement = connection.createStatement(template.render());

            bindStatementParam(projectIds, null, statement, EntityType.THREAD);

            return getNames(statement);
        });
    }

    @Override
    public Mono<Long> deleteThreadManualScores(@NonNull Set<UUID> threadModelIds, @NonNull UUID projectId) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(threadModelIds),
                "Argument 'threadModelIds' must not be empty");

        return asyncTemplate.nonTransaction(connection -> {

            List<String> sources = List.of(ScoreSource.UI.getValue(), ScoreSource.SDK.getValue());

            // Delete from feedback_scores table
            var template1 = TemplateUtils.newST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template1.add("project_id", projectId);
            template1.add("sources", sources);
            template1.add("table_name", "feedback_scores");

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", threadModelIds)
                    .bind("entity_type", EntityType.THREAD.getType())
                    .bind("sources", sources)
                    .bind("project_id", projectId);

            // Delete from authored_feedback_scores table
            var template2 = TemplateUtils.newST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template2.add("project_id", projectId);
            template2.add("sources", sources);
            template2.add("table_name", "authored_feedback_scores");

            var statement2 = connection.createStatement(template2.render())
                    .bind("entity_ids", threadModelIds)
                    .bind("entity_type", EntityType.THREAD.getType())
                    .bind("sources", sources)
                    .bind("project_id", projectId);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(makeMonoContextAware(bindWorkspaceIdToMono(statement2)))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    @WithSpan
    public Mono<List<String>> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type) {
        return asyncTemplate.nonTransaction(connection -> {

            var template = TemplateUtils.newST(SELECT_SPAN_FEEDBACK_SCORE_NAMES);

            if (type != null) {
                template.add("type", type.name());
            }

            var statement = connection.createStatement(template.render());

            statement.bind("project_id", projectId);

            if (type != null) {
                statement.bind("type", type.name());
            }

            return getNames(statement);
        });
    }

    private Mono<List<String>> getNames(Statement statement) {
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .flatMapMany(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                .distinct()
                .collect(Collectors.toList());
    }

    private void bindStatementParam(List<UUID> projectIds, Set<UUID> experimentIds, Statement statement,
            EntityType entityType) {
        if (CollectionUtils.isNotEmpty(projectIds)) {
            statement.bind("project_ids", projectIds);
        }

        if (CollectionUtils.isNotEmpty(experimentIds)) {
            statement.bind("experiment_ids", experimentIds);
        }

        statement.bind("entity_type", entityType.getType());
    }

    private void bindTemplateParam(List<UUID> projectIds, boolean withExperimentsOnly, Set<UUID> experimentIds,
            ST template) {
        if (CollectionUtils.isNotEmpty(projectIds)) {
            template.add("project_ids", projectIds);
        }

        template.add("with_experiments_only", withExperimentsOnly);

        if (CollectionUtils.isNotEmpty(experimentIds)) {
            template.add("experiment_ids", experimentIds);
        }
    }

    private Mono<? extends Result> cascadeSpanDelete(Set<UUID> traceIds, UUID projectId, Connection connection) {
        log.info("Deleting feedback scores by span entityId, traceIds count '{}'", traceIds.size());

        // Delete from feedback_scores table
        var template1 = TemplateUtils.newST(DELETE_SPANS_CASCADE_FEEDBACK_SCORE);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template1.add("project_id", id));
        template1.add("table_name", "feedback_scores");

        var statement1 = connection.createStatement(template1.render())
                .bind("trace_ids", traceIds.toArray(UUID[]::new));

        if (projectId != null) {
            statement1.bind("project_id", projectId);
        }

        // Delete from authored_feedback_scores table
        var template2 = TemplateUtils.newST(DELETE_SPANS_CASCADE_FEEDBACK_SCORE);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template2.add("project_id", id));
        template2.add("table_name", "authored_feedback_scores");

        var statement2 = connection.createStatement(template2.render())
                .bind("trace_ids", traceIds.toArray(UUID[]::new));

        if (projectId != null) {
            statement2.bind("project_id", projectId);
        }

        return makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                .then(makeMonoContextAware(bindWorkspaceIdToMono(statement2)));
    }

    private Mono<Long> deleteScoresByEntityIds(EntityType entityType, Set<UUID> entityIds, UUID projectId,
            Connection connection) {
        log.info("Deleting feedback scores by entityType '{}', entityIds count '{}'", entityType, entityIds.size());

        // Delete from feedback_scores table
        var template1 = TemplateUtils.newST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template1.add("project_id", id));
        template1.add("table_name", "feedback_scores");

        var statement1 = connection.createStatement(template1.render())
                .bind("entity_ids", entityIds.toArray(UUID[]::new))
                .bind("entity_type", entityType.getType());

        if (projectId != null) {
            statement1.bind("project_id", projectId);
        }

        // Delete from authored_feedback_scores table
        var template2 = TemplateUtils.newST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template2.add("project_id", id));
        template2.add("table_name", "authored_feedback_scores");

        var statement2 = connection.createStatement(template2.render())
                .bind("entity_ids", entityIds.toArray(UUID[]::new))
                .bind("entity_type", entityType.getType());

        if (projectId != null) {
            statement2.bind("project_id", projectId);
        }

        return makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .then(makeMonoContextAware(bindWorkspaceIdToMono(statement2)))
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }
}
