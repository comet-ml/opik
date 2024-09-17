package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
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

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

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

    Mono<Map<UUID, List<FeedbackScore>>> getScores(EntityType entityType, List<UUID> entityIds, Connection connection);

    Mono<Long> scoreEntity(EntityType entityType, UUID entityId, FeedbackScore score, Connection connection);

    Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, String name, Connection connection);

    Mono<Void> deleteByEntityId(EntityType entityType, UUID entityId, Connection connection);

    Mono<Void> deleteByEntityIds(EntityType entityType, Set<UUID> entityIds, Connection connection);

    Mono<Long> scoreBatchOf(EntityType entityType, List<FeedbackScoreBatchItem> scores, Connection connection);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class FeedbackScoreDAOImpl implements FeedbackScoreDAO {

    record FeedbackScoreDto(UUID entityId, FeedbackScore score) {
    }

    private static final String INSERT_FEEDBACK_SCORE = """
            INSERT INTO feedback_scores(
                entity_type,
                entity_id,
                project_id,
                workspace_id,
                name,
                <if(categoryName)> category_name, <endif>
                value,
                <if(reason)> reason, <endif>
                source,
                created_at,
                created_by,
                last_updated_by
            )
            SELECT
                :entity_type,
                id as trace_id,
                project_id,
                workspace_id,
                :name,
                <if(categoryName)> :categoryName, <endif>
                :value,
                <if(reason)> :reason, <endif>
                :source,
                created_at,
                :user_name as created_by,
                :user_name as last_updated_by
            FROM <entity_table>
            WHERE id = :entity_id
            AND workspace_id = :workspace_id
            ORDER BY last_updated_at DESC
            LIMIT 1
            ;
            """;

    /*
     * This is a complex query that inserts feedback. Despite the complexity, is offers us some benefits:
     * 1. If the span or trace doesn't exist it creates the feedback score.
     * 2. If the feedback score already exists, it updates the feedback score.
     * 3. It uses a multiIf function to determine the project_id and created_at values. That way, we validate the project_id and created_at if the traces/spans exists.
     * 4. It fails if there is a mismatch between the project_id of the score and the trace/span project_id
     *
     * The query is complex because it has to handle all these cases. Also, there is some complexity related to clickhouse and the way it does joins. Like:
     * LENGTH(CAST(entity.project_id AS Nullable(String))) this is because clickhouse uses default values for joins instead of nulls.
     */
    private static final String BULK_INSERT_FEEDBACK_SCORE = """
            INSERT INTO feedback_scores(
                entity_type,
                entity_id,
                project_id,
                workspace_id,
                name,
                <if(categoryName)> category_name, <endif>
                value,
                <if(reason)> reason, <endif>
                source,
                created_at,
                created_by,
                last_updated_by
            )
            SELECT
                new.entity_type,
                new.entity_id,
                multiIf(
                    LENGTH(CAST(entity.project_id AS Nullable(String))) > 0 AND notEquals(entity.project_id, new.project_id), leftPad('', 40, '*'),
                    LENGTH(CAST(entity.project_id AS Nullable(String))) > 0, entity.project_id,
                    new.project_id
                ) as project_id,
                multiIf(
                    LENGTH(CAST(feedback.workspace_id AS Nullable(String))) > 0 AND notEquals(feedback.workspace_id, new.workspace_id), CAST(leftPad(new.workspace_id, 40, '*') as FixedString(19)),
                    LENGTH(CAST(feedback.workspace_id AS Nullable(String))) > 0, feedback.workspace_id,
                    new.workspace_id
                ) as workspace_id,
                new.name,
                <if(categoryName)> new.category_name, <endif>
                new.value,
                <if(reason)> new.reason, <endif>
                new.source,
                multiIf(
                    notEquals(feedback.created_at, toDateTime64('1970-01-01 00:00:00.000', 9)), feedback.created_at,
                    new.created_at
                ) as created_at,
                multiIf(
                    LENGTH(feedback.created_by) > 0, feedback.created_by,
                    new.created_by
                ) as created_by,
                new.last_updated_by as last_updated_by
            FROM (
                    SELECT
                        :entity_type as entity_type,
                        :entity_id as entity_id,
                        :project_id as project_id,
                        :workspace_id as workspace_id,
                        :name as name,
                        <if(categoryName)> :categoryName as category_name, <endif>
                        :value as value,
                        <if(reason)> :reason as reason, <endif>
                        :source as  source,
                        now64(9) as created_at,
                        :user_name as created_by,
                        :user_name as last_updated_by
            ) new
            LEFT JOIN (
                SELECT
                    :entity_type as entity_type,
                    id as entity_id,
                    project_id as project_id,
                    workspace_id as workspace_id,
                    :name as name,
                    <if(categoryName)> :categoryName as category_name, <endif>
                    :value as value,
                    <if(reason)> :reason as reason, <endif>
                    :source as source,
                    now64(9) as created_at,
                    :user_name as created_by,
                    :user_name as last_updated_by
                FROM <entity_table>
                WHERE id = :entity_id
                ORDER BY last_updated_at DESC
                LIMIT 1
            ) entity ON new.entity_id = entity.entity_id AND new.entity_type = entity.entity_type AND new.name = entity.name
            LEFT JOIN (
                SELECT
                    entity_id,
                    :entity_type as entity_type,
                    project_id,
                    workspace_id,
                    name,
                    value,
                    category_name,
                    reason,
                    source,
                    created_at,
                    created_by,
                    last_updated_by
                FROM feedback_scores
                WHERE entity_id = :entity_id AND entity_type = :entity_type AND name = :name
                ORDER BY entity_id DESC, last_updated_at DESC
                LIMIT 1 BY entity_id, name
            ) feedback ON new.entity_id = feedback.entity_id AND new.entity_type = feedback.entity_type AND new.name = feedback.name
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

    @Override
    public Mono<Map<UUID, List<FeedbackScore>>> getScores(@NonNull EntityType entityType,
            @NonNull List<UUID> entityIds,
            @NonNull Connection connection) {
        return fetchFeedbackScoresByEntityIds(entityType, entityIds, connection)
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
    public Mono<Long> scoreEntity(@NonNull EntityType entityType,
            @NonNull UUID entityId,
            @NonNull FeedbackScore score,
            @NonNull Connection connection) {

        var template = new ST(INSERT_FEEDBACK_SCORE);

        Optional.ofNullable(score.categoryName())
                .ifPresent(category -> template.add("categoryName", category));

        Optional.ofNullable(score.reason())
                .ifPresent(comment -> template.add("reason", comment));

        template
                .add("entity_table", entityType.getTableName());

        var statement = connection.createStatement(template.render())
                .bind("entity_type", entityType.getType())
                .bind("name", score.name())
                .bind("value", score.value().toString())
                .bind("entity_id", entityId)
                .bind("source", score.source().getValue());

        Optional.ofNullable(score.reason())
                .ifPresent(comment -> statement.bind("reason", comment));

        Optional.ofNullable(score.categoryName())
                .ifPresent(category -> statement.bind("categoryName", category));

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }

    @Override
    public Mono<Long> scoreBatchOf(@NonNull EntityType entityType,
            @NonNull List<FeedbackScoreBatchItem> scores,
            @NonNull Connection connection) {

        if (scores.isEmpty()) {
            return Mono.empty();
        }

        var template = new ST(BULK_INSERT_FEEDBACK_SCORE);

        template
                .add("reason", "reason")
                .add("categoryName", "categoryName")
                .add("entity_table", entityType.getTableName());

        var statement = connection.createStatement(template.render());

        return makeFluxContextAware((userName, workspaceName, workspaceId) -> {

            for (var iterator = scores.iterator(); iterator.hasNext();) {
                var feedbackScoreBatchItem = iterator.next();

                statement.bind("entity_id", feedbackScoreBatchItem.id())
                        .bind("name", feedbackScoreBatchItem.name())
                        .bind("value", feedbackScoreBatchItem.value().toString())
                        .bind("entity_type", entityType.getType())
                        .bind("source", feedbackScoreBatchItem.source().getValue())
                        .bind("project_id", feedbackScoreBatchItem.projectId())
                        .bind("workspace_id", workspaceId)
                        .bind("user_name", userName);

                if (StringUtils.isNotEmpty(feedbackScoreBatchItem.reason())) {
                    statement.bind("reason", feedbackScoreBatchItem.reason());
                } else {
                    statement.bind("reason", "");
                }

                if (StringUtils.isNotEmpty(feedbackScoreBatchItem.categoryName())) {
                    statement.bind("categoryName", feedbackScoreBatchItem.categoryName());
                } else {
                    statement.bind("categoryName", "");
                }

                if (iterator.hasNext()) {
                    statement.add();
                }
            }

            statement.fetchSize(scores.size());

            statement.bind("user_name", userName);

            return Flux.from(statement.execute())
                    .flatMap(Result::getRowsUpdated);
        }).reduce(0L, Long::sum);
    }

    @Override
    public Mono<Void> deleteScoreFrom(EntityType entityType, UUID id, String name, Connection connection) {
        var statement = connection.createStatement(DELETE_FEEDBACK_SCORE);

        statement
                .bind("entity_id", id)
                .bind("entity_type", entityType.getType())
                .bind("name", name);

        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                .then();
    }

    @Override
    public Mono<Void> deleteByEntityId(
            @NonNull EntityType entityType, @NonNull UUID entityId, @NonNull Connection connection) {
        return deleteByEntityIds(entityType, Set.of(entityId), connection);
    }

    @Override
    public Mono<Void> deleteByEntityIds(
            @NonNull EntityType entityType, Set<UUID> entityIds, @NonNull Connection connection) {
        Preconditions.checkArgument(
                CollectionUtils.isNotEmpty(entityIds), "Argument 'entityIds' must not be empty");
        log.info("Deleting feedback scores for entityType '{}', entityIds count '{}'", entityType, entityIds.size());
        return switch (entityType) {
            case TRACE -> cascadeSpanDelete(entityIds, connection)
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.defer(() -> deleteScoresByEntityIds(entityType, entityIds, connection)))
                    .then();
            case SPAN -> deleteScoresByEntityIds(entityType, entityIds, connection)
                    .then();
        };
    }

    private Mono<? extends Result> cascadeSpanDelete(Set<UUID> traceIds, Connection connection) {
        log.info("Deleting feedback scores by span entityId, traceIds count '{}'", traceIds.size());
        var statement = connection.createStatement(DELETE_SPANS_CASCADE_FEEDBACK_SCORE)
                .bind("trace_ids", traceIds.toArray(UUID[]::new));
        return makeMonoContextAware(bindWorkspaceIdToMono(statement));
    }

    private Mono<Long> deleteScoresByEntityIds(EntityType entityType, Set<UUID> entityIds, Connection connection) {
        log.info("Deleting feedback scores by entityType '{}', entityIds count '{}'", entityType, entityIds.size());
        var statement = connection.createStatement(DELETE_FEEDBACK_SCORE_BY_ENTITY_IDS)
                .bind("entity_ids", entityIds.toArray(UUID[]::new))
                .bind("entity_type", entityType.getType());
        return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                .flatMap(result -> Mono.from(result.getRowsUpdated()));
    }
}
