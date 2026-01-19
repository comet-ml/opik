package com.comet.opik.domain;

import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.common.base.Preconditions;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspace;
import static com.comet.opik.infrastructure.DatabaseUtils.getLogComment;
import static com.comet.opik.infrastructure.DatabaseUtils.getSTWithLogComment;
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

    Mono<List<FeedbackScoreNames.ScoreName>> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds);

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
            SETTINGS log_comment = '<log_comment>'
            FORMAT Values
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
            SETTINGS log_comment = '<log_comment>'
            ;
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
            SETTINGS allow_nondeterministic_mutations = 1, log_comment = '<log_comment>'
            ;
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
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private static final String SELECT_FEEDBACK_SCORE_NAMES = """
            <if(experiment_ids)>
            WITH experiment_trace_ids AS (
                SELECT
                    DISTINCT trace_id
                FROM experiment_items
                WHERE workspace_id = :workspace_id
                AND experiment_id IN :experiment_ids
            )
            <endif>
            SELECT DISTINCT
                name,
                'feedback_scores' AS type
            FROM feedback_scores
            WHERE workspace_id = :workspace_id
            <if(project_ids)>
            AND project_id IN :project_ids
            <endif>
            AND entity_type = :entity_type
            <if(experiment_ids)>
            AND entity_id IN (SELECT trace_id FROM experiment_trace_ids)
            <endif>
            UNION DISTINCT
            SELECT DISTINCT
                name,
                'feedback_scores' AS type
            FROM authored_feedback_scores
            WHERE workspace_id = :workspace_id
            <if(project_ids)>
            AND project_id IN :project_ids
            <endif>
            AND entity_type = :entity_type
            <if(experiment_ids)>
            AND entity_id IN (SELECT trace_id FROM experiment_trace_ids)
            <endif>
            <if(experiment_ids)>
            UNION DISTINCT
            SELECT DISTINCT
                JSON_VALUE(score, '$.name') AS name,
                'experiment_scores' AS type
            FROM (
                SELECT id, experiment_scores
                FROM experiments
                WHERE workspace_id = :workspace_id
                AND id IN :experiment_ids
                ORDER BY (workspace_id, dataset_id, id) DESC, last_updated_at DESC
                LIMIT 1 BY id
            ) AS e
            ARRAY JOIN JSONExtractArrayRaw(e.experiment_scores) AS score
            WHERE length(e.experiment_scores) > 2
            AND length(JSON_VALUE(score, '$.name')) > 0
            <endif>
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
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
            SETTINGS log_comment = '<log_comment>'
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

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

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var logComment = getLogComment("bulk_insert_feedback_score", workspaceId, scores.size());
            var template = TemplateUtils.getBatchSql(BULK_INSERT_FEEDBACK_SCORE, scores.size());
            template.add("author", author);
            template.add("log_comment", logComment);

            var statement = connection.createStatement(template.render());

            bindParameters(entityType, scores, statement, author);
            bindUserNameAndWorkspace(statement, userName, workspaceId);

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated)
                    .reduce(Long::sum);
        }));
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

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            // Delete from feedback_scores table
            var deleteFeedbackScore = getSTWithLogComment(DELETE_FEEDBACK_SCORE, "delete_feedback_score", workspaceId,
                    "")
                    .add("table_name", "feedback_scores");

            if (StringUtils.isNotBlank(score.author())) {
                deleteFeedbackScore.add("author", "last_updated_by");
            }

            var statement1 = connection.createStatement(deleteFeedbackScore.render());
            statement1
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", score.name())
                    .bind("workspace_id", workspaceId);

            if (StringUtils.isNotBlank(score.author())) {
                statement1.bind("author", score.author());
            }

            var deleteNonAuthoredOperation = Mono.from(statement1.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));

            // Delete from authored_feedback_scores table
            var deleteAuthoredFeedbackScore = getSTWithLogComment(DELETE_FEEDBACK_SCORE,
                    "delete_authored_feedback_score", workspaceId, "")
                    .add("table_name", "authored_feedback_scores");
            Optional.ofNullable(score.author())
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(author -> deleteAuthoredFeedbackScore.add("author", "author"));

            var statement2 = connection.createStatement(deleteAuthoredFeedbackScore.render());
            statement2
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", score.name())
                    .bind("workspace_id", workspaceId);
            Optional.ofNullable(score.author())
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(author -> statement2.bind("author", author));

            return deleteNonAuthoredOperation
                    .then(Mono.from(statement2.execute()))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then();
        }));
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

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            // Delete from feedback_scores table
            var template1 = getSTWithLogComment(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS,
                    "delete_feedback_scores_by_entity_ids", workspaceId, names.size());
            template1.add("names", names);
            template1.add("table_name", "feedback_scores");

            if (StringUtils.isNotBlank(author)) {
                template1.add("author", "last_updated_by");
            }

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", Set.of(entityId))
                    .bind("entity_type", entityType.getType())
                    .bind("names", names)
                    .bind("workspace_id", workspaceId);

            if (StringUtils.isNotBlank(author)) {
                statement1.bind("author", author);
            }

            var deleteNonAuthoredOperation = Mono.from(statement1.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));

            // Delete from authored_feedback_scores table
            var template2 = getSTWithLogComment(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS,
                    "delete_authored_feedback_scores_by_entity_ids", workspaceId, names.size());
            template2.add("names", names);
            template2.add("table_name", "authored_feedback_scores");
            Optional.ofNullable(author)
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(a -> template2.add("author", "author"));

            var statement2 = connection.createStatement(template2.render())
                    .bind("entity_ids", Set.of(entityId))
                    .bind("entity_type", entityType.getType())
                    .bind("names", names)
                    .bind("workspace_id", workspaceId);
            Optional.ofNullable(author)
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(a -> statement2.bind("author", a));

            return deleteNonAuthoredOperation
                    .then(Mono.from(statement2.execute()))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        }));
    }

    @Override
    @WithSpan
    public Mono<List<String>> getTraceFeedbackScoreNames(UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var template = getSTWithLogComment(SELECT_FEEDBACK_SCORE_NAMES, "get_trace_feedback_score_names",
                    workspaceId, "");

            List<UUID> projectIds = projectId == null ? List.of() : List.of(projectId);

            bindTemplateParam(projectIds, null, template);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            bindStatementParam(projectIds, null, statement, EntityType.TRACE);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                    .collect(Collectors.toList());
        }));
    }

    @Override
    @WithSpan
    public Mono<List<FeedbackScoreNames.ScoreName>> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds) {
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {
            var template = getSTWithLogComment(SELECT_FEEDBACK_SCORE_NAMES, "get_experiments_feedback_score_names",
                    workspaceId, experimentIds.size());
            bindTemplateParam(null, experimentIds, template);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);
            bindStatementParam(null, experimentIds, statement, EntityType.TRACE);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> FeedbackScoreNames.ScoreName.builder()
                            .name(row.get("name", String.class))
                            .type(row.get("type", String.class))
                            .build()))
                    .collect(Collectors.toList());
        }));
    }

    @Override
    @WithSpan
    public Mono<List<String>> getProjectsFeedbackScoreNames(Set<UUID> projectIds) {
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var template = getSTWithLogComment(SELECT_PROJECTS_FEEDBACK_SCORE_NAMES,
                    "get_projects_feedback_score_names", workspaceId, projectIds != null ? projectIds.size() : 0);

            if (CollectionUtils.isNotEmpty(projectIds)) {
                template.add("project_ids", projectIds);
            }

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            if (CollectionUtils.isNotEmpty(projectIds)) {
                statement.bind("project_ids", projectIds);
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                    .collect(Collectors.toList());
        }));
    }

    @Override
    public Mono<List<String>> getProjectsTraceThreadsFeedbackScoreNames(@NonNull List<UUID> projectIds) {

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var template = getSTWithLogComment(SELECT_FEEDBACK_SCORE_NAMES,
                    "get_projects_trace_threads_feedback_score_names", workspaceId, projectIds.size());

            bindTemplateParam(projectIds, null, template);

            var statement = connection.createStatement(template.render())
                    .bind("workspace_id", workspaceId);

            bindStatementParam(projectIds, null, statement, EntityType.THREAD);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                    .collect(Collectors.toList());
        }));
    }

    @Override
    public Mono<Long> deleteThreadManualScores(@NonNull Set<UUID> threadModelIds, @NonNull UUID projectId) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(threadModelIds),
                "Argument 'threadModelIds' must not be empty");

        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            List<String> sources = List.of(ScoreSource.UI.getValue(), ScoreSource.SDK.getValue());

            // Delete from feedback_scores table
            var template1 = getSTWithLogComment(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS, "delete_thread_manual_scores",
                    workspaceId, threadModelIds.size());
            template1.add("project_id", projectId);
            template1.add("sources", sources);
            template1.add("table_name", "feedback_scores");

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", threadModelIds)
                    .bind("entity_type", EntityType.THREAD.getType())
                    .bind("sources", sources)
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId);

            // Delete from authored_feedback_scores table
            var template2 = getSTWithLogComment(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS,
                    "delete_thread_manual_scores_authored", workspaceId, threadModelIds.size());
            template2.add("project_id", projectId);
            template2.add("sources", sources);
            template2.add("table_name", "authored_feedback_scores");

            var statement2 = connection.createStatement(template2.render())
                    .bind("entity_ids", threadModelIds)
                    .bind("entity_type", EntityType.THREAD.getType())
                    .bind("sources", sources)
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId);

            return Mono.from(statement1.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.from(statement2.execute()))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        }));
    }

    @Override
    @WithSpan
    public Mono<List<String>> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type) {
        return asyncTemplate.nonTransaction(connection -> makeMonoContextAware((userName, workspaceId) -> {

            var template = getSTWithLogComment(SELECT_SPAN_FEEDBACK_SCORE_NAMES, "get_span_feedback_score_names",
                    workspaceId, type != null ? type.name() : "");

            if (type != null) {
                template.add("type", type.name());
            }

            var statement = connection.createStatement(template.render())
                    .bind("project_id", projectId)
                    .bind("workspace_id", workspaceId);

            if (type != null) {
                statement.bind("type", type.name());
            }

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, rowMetadata) -> row.get("name", String.class)))
                    .distinct()
                    .collect(Collectors.toList());
        }));
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

    private void bindTemplateParam(List<UUID> projectIds, Set<UUID> experimentIds, ST template) {
        if (CollectionUtils.isNotEmpty(projectIds)) {
            template.add("project_ids", projectIds);
        }
        if (CollectionUtils.isNotEmpty(experimentIds)) {
            template.add("experiment_ids", experimentIds);
        }
    }

    private Mono<? extends Result> cascadeSpanDelete(Set<UUID> traceIds, UUID projectId, Connection connection) {
        log.info("Deleting feedback scores by span entityId, traceIds count '{}'", traceIds.size());

        return makeMonoContextAware((userName, workspaceId) -> {
            // Delete from feedback_scores table
            var template1 = getSTWithLogComment(DELETE_SPANS_CASCADE_FEEDBACK_SCORE, "cascade_span_delete", workspaceId,
                    traceIds.size());
            Optional.ofNullable(projectId)
                    .ifPresent(id -> template1.add("project_id", id));
            template1.add("table_name", "feedback_scores");

            var statement1 = connection.createStatement(template1.render())
                    .bind("trace_ids", traceIds.toArray(UUID[]::new))
                    .bind("workspace_id", workspaceId);

            if (projectId != null) {
                statement1.bind("project_id", projectId);
            }

            // Delete from authored_feedback_scores table
            var template2 = getSTWithLogComment(DELETE_SPANS_CASCADE_FEEDBACK_SCORE, "cascade_span_delete_authored",
                    workspaceId, traceIds.size());
            Optional.ofNullable(projectId)
                    .ifPresent(id -> template2.add("project_id", id));
            template2.add("table_name", "authored_feedback_scores");

            var statement2 = connection.createStatement(template2.render())
                    .bind("trace_ids", traceIds.toArray(UUID[]::new))
                    .bind("workspace_id", workspaceId);

            if (projectId != null) {
                statement2.bind("project_id", projectId);
            }

            return Mono.from(statement1.execute())
                    .then(Mono.from(statement2.execute()));
        });
    }

    private Mono<Long> deleteScoresByEntityIds(EntityType entityType, Set<UUID> entityIds, UUID projectId,
            Connection connection) {
        log.info("Deleting feedback scores by entityType '{}', entityIds count '{}'", entityType, entityIds.size());

        return makeMonoContextAware((userName, workspaceId) -> {
            // Delete from feedback_scores table
            var template1 = getSTWithLogComment(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS, "delete_scores_by_entity_ids",
                    workspaceId, entityIds.size());
            Optional.ofNullable(projectId)
                    .ifPresent(id -> template1.add("project_id", id));
            template1.add("table_name", "feedback_scores");

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", entityIds.toArray(UUID[]::new))
                    .bind("entity_type", entityType.getType())
                    .bind("workspace_id", workspaceId);

            if (projectId != null) {
                statement1.bind("project_id", projectId);
            }

            // Delete from authored_feedback_scores table
            var template2 = getSTWithLogComment(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS,
                    "delete_scores_by_entity_ids_authored", workspaceId, entityIds.size());
            Optional.ofNullable(projectId)
                    .ifPresent(id -> template2.add("project_id", id));
            template2.add("table_name", "authored_feedback_scores");

            var statement2 = connection.createStatement(template2.render())
                    .bind("entity_ids", entityIds.toArray(UUID[]::new))
                    .bind("entity_type", entityType.getType())
                    .bind("workspace_id", workspaceId);

            if (projectId != null) {
                statement2.bind("project_id", projectId);
            }

            return Mono.from(statement1.execute())
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.from(statement2.execute()))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }
}
