package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
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
import lombok.Getter;
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
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContextToStream;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(FeedbackScoreDAOImpl.class)
public interface FeedbackScoreDAO {

    @Getter
    @RequiredArgsConstructor
    enum EntityType {
        TRACE("trace", "traces"),
        SPAN("span", "spans");

        private final String type;
        private final String tableName;
    }

    Mono<Map<UUID, List<FeedbackScore>>> getScores(EntityType entityType, List<UUID> entityIds);

    Mono<Long> scoreEntity(EntityType entityType, UUID entityId, FeedbackScore score,
            UUID projectId);

    Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, String name);

    Mono<Void> deleteByEntityId(EntityType entityType, UUID entityId);

    Mono<Void> deleteByEntityIds(EntityType entityType, Set<UUID> entityIds);

    Mono<Long> scoreBatchOf(EntityType entityType, List<FeedbackScoreBatchItem> scores);

    Mono<List<String>> getTraceFeedbackScoreNames(UUID projectId);

    Mono<List<String>> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type);

    Mono<List<String>> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds);

    Mono<List<String>> getProjectsFeedbackScoreNames(Set<UUID> projectIds);
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
            SELECT
                *
            FROM feedback_scores
            WHERE entity_id in :entity_ids
            AND entity_type = :entity_type
            AND workspace_id = :workspace_id
            ORDER BY entity_id DESC, last_updated_at DESC
            LIMIT 1 BY entity_id, name
            ;
            """;

    private static final String DELETE_FEEDBACK_SCORE = """
            DELETE FROM feedback_scores
            WHERE entity_id = :entity_id
            AND entity_type = :entity_type
            AND name = :name
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String DELETE_SPANS_CASCADE_FEEDBACK_SCORE = """
            DELETE FROM feedback_scores
            WHERE entity_type = 'span'
            AND entity_id IN (
                SELECT id
                FROM spans
                WHERE trace_id IN :trace_ids
            )
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS = """
            DELETE FROM feedback_scores
            WHERE entity_id IN :entity_ids
            AND entity_type = :entity_type
            AND workspace_id = :workspace_id
            ;
            """;

    private static final String SELECT_TRACE_FEEDBACK_SCORE_NAMES = """
            SELECT
                distinct name
            FROM (
                SELECT
                    name
                FROM feedback_scores
                WHERE workspace_id = :workspace_id
                <if(project_id)>
                AND project_id = :project_id
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
                        ORDER BY id DESC, last_updated_at DESC
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
                        ORDER BY id DESC, last_updated_at DESC
                        LIMIT 1 BY id
                    ) ei ON e.id = ei.experiment_id
                )
                <endif>
                AND entity_type = 'trace'
                ORDER BY entity_id DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
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
                ORDER BY entity_id DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
            ) AS names
            ;
            """;

    private final static String SELECT_SPAN_FEEDBACK_SCORE_NAMES = """
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
                    ORDER BY id DESC, last_updated_at DESC
                    LIMIT 1 BY id
                )
                <endif>
                AND entity_type = 'span'
                ORDER BY entity_id DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
            ) AS names
            ;
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;

    @Override
    @WithSpan
    public Mono<Map<UUID, List<FeedbackScore>>> getScores(@NonNull EntityType entityType,
            @NonNull List<UUID> entityIds) {
        return asyncTemplate.stream(connection -> fetchFeedbackScoresByEntityIds(entityType, entityIds, connection))
                .collectList()
                .map(this::groupByTraceId);
    }

    private Map<UUID, List<FeedbackScore>> groupByTraceId(List<FeedbackScoreDto> feedbackLogs) {
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
                        .build());
    }

    @Override
    @WithSpan
    public Mono<Long> scoreEntity(@NonNull EntityType entityType,
            @NonNull UUID entityId,
            @NonNull FeedbackScore score,
            @NonNull UUID projectId) {

        FeedbackScoreBatchItem item = FeedbackScoreMapper.INSTANCE.toFeedbackScore(entityId,
                projectId, score);

        return scoreBatchOf(entityType, List.of(item));
    }

    private ST getBatchSql(String sql, int size) {
        var template = new ST(sql);
        List<TemplateUtils.QueryItem> queryItems = getQueryItemPlaceHolder(size);

        template.add("items", queryItems);

        return template;
    }

    private String getValueOrDefault(String value) {
        return Optional.ofNullable(value)
                .map(String::trim)
                .filter(StringUtils::isNotEmpty)
                .orElse("");
    }

    @Override
    @WithSpan
    public Mono<Long> scoreBatchOf(@NonNull EntityType entityType, @NonNull List<FeedbackScoreBatchItem> scores) {

        Preconditions.checkArgument(CollectionUtils.isNotEmpty(scores), "Argument 'scores' must not be empty");

        return asyncTemplate.nonTransaction(connection -> {

            ST template = getBatchSql(BULK_INSERT_FEEDBACK_SCORE, scores.size());

            var statement = connection.createStatement(template.render());

            bindParameters(entityType, scores, statement);

            return makeFluxContextAware(bindUserNameAndWorkspaceContextToStream(statement))
                    .flatMap(Result::getRowsUpdated)
                    .reduce(Long::sum);
        });

    }

    private void bindParameters(EntityType entityType, List<FeedbackScoreBatchItem> scores, Statement statement) {
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
            var statement = connection.createStatement(DELETE_FEEDBACK_SCORE);

            statement
                    .bind("entity_id", id)
                    .bind("entity_type", entityType.getType())
                    .bind("name", name);

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then();
        });
    }

    @Override
    @WithSpan
    public Mono<Void> deleteByEntityId(
            @NonNull EntityType entityType, @NonNull UUID entityId) {
        return deleteByEntityIds(entityType, Set.of(entityId));
    }

    @Override
    @WithSpan
    public Mono<Void> deleteByEntityIds(
            @NonNull EntityType entityType, Set<UUID> entityIds) {
        Preconditions.checkArgument(
                CollectionUtils.isNotEmpty(entityIds), "Argument 'entityIds' must not be empty");
        log.info("Deleting feedback scores for entityType '{}', entityIds count '{}'", entityType, entityIds.size());
        return switch (entityType) {
            case TRACE -> asyncTemplate.nonTransaction(connection -> cascadeSpanDelete(entityIds, connection))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.defer(() -> asyncTemplate
                            .nonTransaction(connection -> deleteScoresByEntityIds(entityType, entityIds, connection))))
                    .then();
            case SPAN ->
                asyncTemplate.nonTransaction(connection -> deleteScoresByEntityIds(entityType, entityIds, connection))
                        .then();
        };
    }

    @Override
    @WithSpan
    public Mono<List<String>> getTraceFeedbackScoreNames(@NonNull UUID projectId) {
        return asyncTemplate.nonTransaction(connection -> {

            ST template = new ST(SELECT_TRACE_FEEDBACK_SCORE_NAMES);

            bindTemplateParam(projectId, false, null, template);

            var statement = connection.createStatement(template.render());

            bindStatementParam(projectId, null, statement);

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

            bindStatementParam(null, experimentIds, statement);

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

    private void bindStatementParam(UUID projectId, Set<UUID> experimentIds, Statement statement) {
        if (projectId != null) {
            statement.bind("project_id", projectId);
        }

        if (CollectionUtils.isNotEmpty(experimentIds)) {
            statement.bind("experiment_ids", experimentIds);
        }
    }

    private void bindTemplateParam(UUID projectId, boolean withExperimentsOnly, Set<UUID> experimentIds, ST template) {
        if (projectId != null) {
            template.add("project_id", projectId);
        }

        template.add("with_experiments_only", withExperimentsOnly);

        if (CollectionUtils.isNotEmpty(experimentIds)) {
            template.add("experiment_ids", experimentIds);
        }
    }

    private Mono<? extends Result> cascadeSpanDelete(Set<UUID> traceIds, Connection connection) {
        log.info("Deleting feedback scores by span entityId, traceIds count '{}'", traceIds.size());
        var statement = connection.createStatement(DELETE_SPANS_CASCADE_FEEDBACK_SCORE)
                .bind("trace_ids", traceIds);
        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Mono<Long> deleteScoresByEntityIds(EntityType entityType, Set<UUID> entityIds, Connection connection) {
        log.info("Deleting feedback scores by entityType '{}', entityIds count '{}'", entityType, entityIds.size());
        var statement = connection.createStatement(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS)
                .bind("entity_ids", entityIds)
                .bind("entity_type", entityType.getType());
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }
}
