package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.ValueEntry;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.ClickhouseUtils;
import com.comet.opik.utils.TemplateUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
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

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(FeedbackScoreDAOImpl.class)
public interface FeedbackScoreDAO {

    Mono<Map<UUID, List<FeedbackScore>>> getScores(EntityType entityType, List<UUID> entityIds);

    Mono<Long> scoreEntity(EntityType entityType, UUID entityId, FeedbackScore score,
            UUID projectId);

    Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, String name);

    Mono<Void> deleteByEntityIds(EntityType entityType, Set<UUID> entityIds, UUID projectId);

    Mono<Long> deleteByEntityIdAndNames(EntityType entityType, UUID entityId, Set<String> names);

    Mono<Long> scoreBatchOf(EntityType entityType, List<? extends FeedbackScoreItem> scores);

    Mono<Long> scoreBatchOfThreads(List<FeedbackScoreBatchItemThread> scores);

    Mono<List<String>> getTraceFeedbackScoreNames(UUID projectId);

    Mono<List<String>> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type);

    Mono<List<String>> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds);

    Mono<List<String>> getProjectsFeedbackScoreNames(Set<UUID> projectIds);

    Mono<List<String>> getProjectsTraceThreadsFeedbackScoreNames(List<UUID> projectId);

    Mono<Long> deleteThreadManualScores(Set<UUID> threadModelIds, UUID projectId);

    Mono<Long> scoreAuthoredEntity(EntityType entityType, UUID entityId, FeedbackScore score, String author,
            UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class FeedbackScoreDAOImpl implements FeedbackScoreDAO {

    record FeedbackScoreDto(UUID entityId, FeedbackScore score) {
    }

    private static final String BULK_INSERT_FEEDBACK_SCORE = """
            INSERT INTO feedback_scores(
                entity_type,
                entity_id,
                project_id,
                workspace_id,
                name,
                category_name,
                value,
                reason,
                source,
                created_by,
                last_updated_by
            ) <settings_clause>
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
                         :user_name,
                         :user_name
                     )
                     <if(item.hasNext)>
                        ,
                     <endif>
                }>
            ;
            """;

    private static final String SELECT_FEEDBACK_SCORE_BY_ID = """
            WITH combined_scores AS (
                SELECT
                    *,
                    '' as author
                FROM feedback_scores
                WHERE entity_id in :entity_ids
                AND entity_type = :entity_type
                AND workspace_id = :workspace_id
                LIMIT 1 BY entity_id, name
                UNION ALL
                SELECT
                    *
                FROM authored_feedback_scores
                WHERE entity_id in :entity_ids
                AND entity_type = :entity_type
                AND workspace_id = :workspace_id
                LIMIT 1 BY entity_id, author, name
            )
            SELECT
                entity_id,
                name,
                category_name,
                avg(value) as value,
                reason,
                source,
                min(created_at) as created_at,
                max(last_updated_at) as last_updated_at,
                created_by,
                last_updated_by,
                mapFromArrays(
                    groupArray(author),
                    groupArray(tuple(value, category_name))
                ) as value_by_author
            FROM combined_scores
            GROUP BY entity_id, name, category_name, reason, source, created_by, last_updated_by
            ORDER BY entity_id DESC, last_updated_at DESC
            ;
            """;

    private static final String DELETE_FEEDBACK_SCORE = """
            DELETE FROM feedback_scores
            WHERE entity_id = :entity_id
            AND entity_type = :entity_type
            AND name = :name
            AND workspace_id = :workspace_id
            """;

    private static final String DELETE_AUTHORED_FEEDBACK_SCORE = """
            DELETE FROM authored_feedback_scores
            WHERE entity_id = :entity_id
            AND entity_type = :entity_type
            AND name = :name
            AND workspace_id = :workspace_id
            """;

    private static final String DELETE_SPANS_CASCADE_FEEDBACK_SCORE = """
            DELETE FROM feedback_scores
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

    private static final String DELETE_SPANS_CASCADE_AUTHORED_FEEDBACK_SCORE = """
            DELETE FROM authored_feedback_scores
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
            DELETE FROM feedback_scores
            WHERE entity_id IN :entity_ids
            AND entity_type = :entity_type
            AND workspace_id = :workspace_id
            <if(names)>AND name IN :names <endif>
            <if(project_id)>AND project_id = :project_id<endif>
            <if(sources)>AND source IN :sources<endif>
            """;

    private static final String DELETE_AUTHORED_FEEDBACK_SCORE_BY_ENTITY_IDS = """
            DELETE FROM authored_feedback_scores
            WHERE entity_id IN :entity_ids
            AND entity_type = :entity_type
            AND workspace_id = :workspace_id
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

    private static final String INSERT_AUTHORED_FEEDBACK_SCORE = """
            INSERT INTO authored_feedback_scores(
                entity_type,
                entity_id,
                project_id,
                workspace_id,
                author,
                name,
                category_name,
                value,
                reason,
                source,
                created_by,
                last_updated_by
            )
            VALUES (
                :entity_type,
                :entity_id,
                :project_id,
                :workspace_id,
                :author,
                :name,
                :category_name,
                :value,
                :reason,
                :source,
                :user_name,
                :user_name
            )
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull OpikConfiguration opikConfiguration;

    @Override
    @WithSpan
    public Mono<Map<UUID, List<FeedbackScore>>> getScores(@NonNull EntityType entityType,
            @NonNull List<UUID> entityIds) {
        return asyncTemplate.stream(connection -> fetchFeedbackScoresByEntityIds(entityType, entityIds, connection))
                .collectList()
                .map(this::groupByEntityId);
    }

    private Map<UUID, List<FeedbackScore>> groupByEntityId(List<FeedbackScoreDto> feedbackLogs) {
        return feedbackLogs.stream()
                .collect(Collectors.groupingBy(FeedbackScoreDto::entityId,
                        Collectors.mapping(FeedbackScoreDto::score, Collectors.toList())));
    }

    private Flux<FeedbackScoreDto> fetchFeedbackScoresByEntityIds(EntityType entityType,
            Collection<UUID> entityIds,
            Connection connection) {

        if (entityIds.isEmpty()) {
            return Flux.empty();
        }

        var statement = connection.createStatement(SELECT_FEEDBACK_SCORE_BY_ID);

        statement
                .bind("entity_ids", entityIds.toArray(UUID[]::new))
                .bind("entity_type", entityType.getType());

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                .flatMap(result -> result.map((row, rowMetadata) -> mapFeedback(row)));
    }

    private FeedbackScoreDto mapFeedback(Row row) {
        return new FeedbackScoreDto(
                row.get("entity_id", UUID.class),
                FeedbackScore.builder()
                        .name(row.get("name", String.class))
                        .categoryName(Optional.ofNullable(row.get("category_name", String.class))
                                .filter(it -> !it.isBlank())
                                .orElse(null))
                        .value(row.get("value", BigDecimal.class))
                        .reason(Optional.ofNullable(row.get("reason", String.class))
                                .filter(it -> !it.isBlank())
                                .orElse(null))
                        .source(ScoreSource.fromString(row.get("source", String.class)))
                        .createdAt(row.get("created_at", Instant.class))
                        .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                        .createdBy(row.get("created_by", String.class))
                        .lastUpdatedBy(row.get("last_updated_by", String.class))
                        .valueByAuthor(parseValueByAuthor(row.get("value_by_author")))
                        .build());
    }

    private Map<String, ValueEntry> parseValueByAuthor(Object valueByAuthorObj) {
        if (valueByAuthorObj == null) {
            return Map.of();
        }

        // ClickHouse returns maps as LinkedHashMap<String, List<Object>> where List<Object> represents a tuple
        @SuppressWarnings("unchecked")
        Map<String, List<Object>> valueByAuthorMap = (Map<String, List<Object>>) valueByAuthorObj;

        Map<String, ValueEntry> result = new HashMap<>();
        for (Map.Entry<String, List<Object>> entry : valueByAuthorMap.entrySet()) {
            String author = entry.getKey();
            List<Object> tuple = entry.getValue();

            // tuple contains: (value, reason, category_name, source, last_updated_at)
            ValueEntry valueEntry = ValueEntry.builder()
                    .value((BigDecimal) tuple.get(0))
                    .reason(Optional.ofNullable((String) tuple.get(1))
                            .filter(it -> !it.isBlank())
                            .orElse(null))
                    .categoryName(Optional.ofNullable((String) tuple.get(2))
                            .filter(it -> !it.isBlank())
                            .orElse(null))
                    .source(ScoreSource.fromString((String) tuple.get(3)))
                    .lastUpdatedAt(((OffsetDateTime) tuple.get(4)).toInstant())
                    .build();

            result.put(author, valueEntry);
        }

        return result;
    }

    @Override
    @WithSpan
    public Mono<Long> scoreEntity(@NonNull EntityType entityType,
            @NonNull UUID entityId,
            @NonNull FeedbackScore score,
            @NonNull UUID projectId) {

        FeedbackScoreItem item = FeedbackScoreMapper.INSTANCE.toFeedbackScore(entityId,
                projectId, score);

        return scoreBatchOf(entityType, List.of(item));
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
            @NonNull List<? extends FeedbackScoreItem> scores) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(scores), "Argument 'scores' must not be empty");

        return asyncTemplate.nonTransaction(connection -> {

            ST template = TemplateUtils.getBatchSql(BULK_INSERT_FEEDBACK_SCORE, scores.size());

            ClickhouseUtils.checkAsyncConfig(template, opikConfiguration.getAsyncInsert());

            var statement = connection.createStatement(template.render());

            bindParameters(entityType, scores, statement);

            return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                    .flatMap(Result::getRowsUpdated)
                    .reduce(Long::sum);
        });

    }

    @Override
    public Mono<Long> scoreBatchOfThreads(@NonNull List<FeedbackScoreBatchItemThread> scores) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(scores), "Argument 'scores' must not be empty");

        return scoreBatchOf(EntityType.THREAD, scores);
    }

    private void bindParameters(EntityType entityType, List<? extends FeedbackScoreItem> scores,
            Statement statement) {
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
        }
    }

    @Override
    @WithSpan
    public Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, String name) {

        return asyncTemplate.nonTransaction(connection -> {
            // Delete from feedback_scores table
            var statement1 = connection.createStatement(DELETE_FEEDBACK_SCORE);
            statement1
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", name);

            // Delete from authored_feedback_scores table
            var statement2 = connection.createStatement(DELETE_AUTHORED_FEEDBACK_SCORE);
            statement2
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", name);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
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
            @NonNull Set<String> names) {

        if (names.isEmpty()) {
            return Mono.just(0L);
        }

        return asyncTemplate.nonTransaction(connection -> {
            // Delete from feedback_scores table
            ST template1 = new ST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template1.add("names", names);

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", Set.of(entityId))
                    .bind("entity_type", entityType.getType())
                    .bind("names", names);

            // Delete from authored_feedback_scores table
            ST template2 = new ST(DELETE_AUTHORED_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template2.add("names", names);

            var statement2 = connection.createStatement(template2.render())
                    .bind("entity_ids", Set.of(entityId))
                    .bind("entity_type", entityType.getType())
                    .bind("names", names);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement1))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(makeMonoContextAware(bindWorkspaceIdToMono(statement2)))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    @WithSpan
    public Mono<List<String>> getTraceFeedbackScoreNames(@NonNull UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> {

            ST template = new ST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

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

            ST template = new ST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

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

            ST template = new ST(SELECT_PROJECTS_FEEDBACK_SCORE_NAMES);

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

            ST template = new ST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

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
            var template1 = new ST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template1.add("project_id", projectId);
            template1.add("sources", sources);

            var statement1 = connection.createStatement(template1.render())
                    .bind("entity_ids", threadModelIds)
                    .bind("entity_type", EntityType.THREAD.getType())
                    .bind("sources", sources)
                    .bind("project_id", projectId);

            // Delete from authored_feedback_scores table
            var template2 = new ST(DELETE_AUTHORED_FEEDBACK_SCORE_BY_ENTITY_IDS);
            template2.add("project_id", projectId);
            template2.add("sources", sources);

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
    public Mono<Long> scoreAuthoredEntity(@NonNull EntityType entityType,
            @NonNull UUID entityId,
            @NonNull FeedbackScore score,
            @NonNull String author,
            @NonNull UUID projectId) {

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(INSERT_AUTHORED_FEEDBACK_SCORE)
                    .bind("entity_type", entityType.getType())
                    .bind("entity_id", entityId)
                    .bind("project_id", projectId)
                    .bind("author", author)
                    .bind("name", score.name())
                    .bind("category_name", getValueOrDefault(score.categoryName()))
                    .bind("value", score.value())
                    .bind("reason", getValueOrDefault(score.reason()))
                    .bind("source", score.source().getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    @WithSpan
    public Mono<List<String>> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type) {
        return asyncTemplate.nonTransaction(connection -> {

            ST template = new ST(SELECT_SPAN_FEEDBACK_SCORE_NAMES);

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
        var template1 = new ST(DELETE_SPANS_CASCADE_FEEDBACK_SCORE);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template1.add("project_id", id));

        var statement1 = connection.createStatement(template1.render())
                .bind("trace_ids", traceIds.toArray(UUID[]::new));

        if (projectId != null) {
            statement1.bind("project_id", projectId);
        }

        // Delete from authored_feedback_scores table
        var template2 = new ST(DELETE_SPANS_CASCADE_AUTHORED_FEEDBACK_SCORE);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template2.add("project_id", id));

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
        var template1 = new ST(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template1.add("project_id", id));

        var statement1 = connection.createStatement(template1.render())
                .bind("entity_ids", entityIds.toArray(UUID[]::new))
                .bind("entity_type", entityType.getType());

        if (projectId != null) {
            statement1.bind("project_id", projectId);
        }

        // Delete from authored_feedback_scores table
        var template2 = new ST(DELETE_AUTHORED_FEEDBACK_SCORE_BY_ENTITY_IDS);
        Optional.ofNullable(projectId)
                .ifPresent(id -> template2.add("project_id", id));

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
