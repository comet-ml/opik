package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueInfo;
import com.comet.opik.api.AnnotationQueueReviewer;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.sorting.AnnotationQueueSortingFactory;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.utils.template.TemplateUtils;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.domain.ExperimentDAO.getFeedbackScores;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.template.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(AnnotationQueueDAOImpl.class)
public interface AnnotationQueueDAO {

    Mono<Void> createBatch(List<AnnotationQueue> annotationQueues);

    Mono<AnnotationQueue> findById(UUID id);

    Mono<AnnotationQueueInfo> findQueueInfoById(UUID id);

    Mono<Void> update(UUID id, AnnotationQueueUpdate update);

    Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size, AnnotationQueueSearchCriteria searchCriteria);

    Mono<Long> deleteBatch(Set<UUID> ids);

    Mono<Long> addItems(UUID queueId, Set<UUID> itemIds, UUID projectId);

    Mono<Long> removeItems(UUID queueId, Set<UUID> itemIds, UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueDAOImpl implements AnnotationQueueDAO {

    private static final String BATCH_INSERT = """
            INSERT INTO annotation_queues (
                id,
                workspace_id,
                project_id,
                name,
                description,
                instructions,
                scope,
                comments_enabled,
                feedback_definitions,
                created_by,
                last_updated_by
            ) VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :workspace_id,
                        :project_id<item.index>,
                        :name<item.index>,
                        :description<item.index>,
                        :instructions<item.index>,
                        :scope<item.index>,
                        :comments_enabled<item.index>,
                        :feedback_definitions<item.index>,
                        :user_name,
                        :user_name
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private static final String UPDATE = """
            INSERT INTO annotation_queues (
            	id,
                workspace_id,
                project_id,
                name,
                description,
                instructions,
                scope,
                comments_enabled,
                feedback_definitions,
                created_at,
            	created_by,
            	last_updated_by
            )
            SELECT
            	id,
                workspace_id,
                project_id,
                <if(name)> :name <else> name <endif> as name,
                <if(description)> :description <else> description <endif> as description,
                <if(instructions)> :instructions <else> instructions <endif> as instructions,
                scope,
                <if(comments_enabled)> :comments_enabled <else> comments_enabled <endif> as comments_enabled,
                <if(has_feedback_definitions)> :feedback_definitions <else> feedback_definitions <endif> as feedback_definitions,
                created_at,
            	created_by,
                :user_name as last_updated_by
            FROM annotation_queues
            WHERE id = :id
            AND workspace_id = :workspace_id
            ORDER BY id DESC, last_updated_at DESC
            LIMIT 1
            ;
            """;

    public static final String BATCH_ITEMS_INSERT = """
            INSERT INTO annotation_queue_items (
                queue_id,
                item_id,
                project_id,
                workspace_id,
                created_by,
                last_updated_by
            )
            VALUES
                <items:{item |
                    (
                        :queue_id,
                        :item_id<item.index>,
                        :project_id,
                        :workspace_id,
                        :user_name,
                        :user_name
                    )
                    <if(item.hasNext)>,<endif>
                }>
            """;

    public static final String DELETE_ITEMS_BY_IDS = """
            DELETE FROM annotation_queue_items
            WHERE workspace_id = :workspace_id
            AND project_id = :project_id
            AND queue_id = :queue_id
            AND item_id IN :item_ids
            """;

    private static final String DELETE_BATCH = """
            DELETE FROM annotation_queues
            WHERE workspace_id = :workspace_id
            AND id IN :ids
            """;

    private static final String SELECT_QUEUE_INFO_BY_ID = """
            SELECT
                id,
                project_id
            FROM annotation_queues
            WHERE workspace_id = :workspace_id
            AND id = :id
            ORDER BY last_updated_at DESC
            LIMIT 1 BY id
            """;

    private static final String FIND = """
            WITH queues_final AS
            (
                SELECT
                    id,
                    project_id,
                    name,
                    description,
                    instructions,
                    scope,
                    comments_enabled,
                    feedback_definitions,
                    created_by,
                    created_at,
                    last_updated_by,
                    last_updated_at
                FROM annotation_queues
                WHERE workspace_id = :workspace_id
                <if(id)> AND id = :id <endif>
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(filters)> AND (<filters>) <endif>
                ORDER BY id DESC, last_updated_at DESC
                LIMIT 1 BY id
            ), queue_items_final AS
            (
                SELECT aqi.queue_id, aqi.item_id, q.feedback_definitions
                FROM (
                    SELECT DISTINCT queue_id, item_id
                    FROM annotation_queue_items
                    WHERE workspace_id = :workspace_id
                ) AS aqi
                INNER JOIN queues_final AS q ON aqi.queue_id = q.id
            ), queue_items_count AS (
                SELECT queue_id, count(1) AS items_count
                FROM queue_items_final
                GROUP BY queue_id
            ), feedback_scores_combined_raw AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       created_by,
                       last_updated_at,
                       last_updated_by AS author
                FROM feedback_scores FINAL
                WHERE workspace_id = :workspace_id
                    AND project_id IN (SELECT project_id FROM queues_final)
                    AND entity_id IN (SELECT item_id FROM queue_items_final)
                UNION ALL
                SELECT
                    workspace_id,
                    project_id,
                    entity_id,
                    name,
                    value,
                    created_by,
                    last_updated_at,
                    author
                FROM authored_feedback_scores FINAL
                WHERE workspace_id = :workspace_id
                   AND project_id IN (SELECT project_id FROM queues_final)
                   AND entity_id IN (SELECT item_id FROM queue_items_final)
            ), feedback_scores_with_ranking AS (
                SELECT workspace_id,
                       project_id,
                       entity_id,
                       name,
                       value,
                       created_by,
                       last_updated_at,
                       author,
                       ROW_NUMBER() OVER (
                           PARTITION BY workspace_id, project_id, entity_id, name, author
                           ORDER BY last_updated_at DESC
                       ) as rn
                FROM feedback_scores_combined_raw
            ), feedback_scores_combined AS (
                SELECT entity_id,
                       name,
                       value,
                       created_by
                FROM feedback_scores_with_ranking
                WHERE rn = 1
            ), feedback_scores_combined_grouped AS (
                SELECT
                    entity_id,
                    name,
                    groupArray(value) AS values
                FROM feedback_scores_combined
                GROUP BY entity_id, name
            ), feedback_scores_final AS (
                SELECT
                    entity_id,
                    name,
                    IF(length(values) = 1, arrayElement(values, 1), toDecimal64(arrayAvg(values), 9)) AS value
                FROM feedback_scores_combined_grouped
            ), feedback_scores_agg AS (
                SELECT
                    fs_avg.queue_id,
                    mapFromArrays(
                        groupArray(fs_avg.name),
                        groupArray(fs_avg.avg_value)
                    ) AS feedback_scores
                FROM (
                    SELECT
                        qi.queue_id,
                        fs.name,
                        avg(fs.value) AS avg_value
                    FROM queue_items_final AS qi
                    INNER JOIN feedback_scores_final AS fs
                      ON fs.entity_id = qi.item_id
                    WHERE length(fs.name) > 0
                      AND has(qi.feedback_definitions, fs.name)  -- only names defined for this queue
                    GROUP BY qi.queue_id, fs.name
                ) as fs_avg
                GROUP BY queue_id
            ), comments_combined AS (
                SELECT DISTINCT
                    entity_id,
                    created_by
                FROM comments FINAL
                WHERE workspace_id = :workspace_id
                    AND project_id IN (SELECT project_id FROM queues_final)
                    AND entity_id IN (SELECT item_id FROM queue_items_final)
            ), queue_items_with_feedback_reviewers AS (
                SELECT DISTINCT
                    qi.queue_id,
                    qi.item_id,
                    fsc.created_by AS username
                FROM queue_items_final AS qi
                INNER JOIN feedback_scores_combined AS fsc
                 ON fsc.entity_id = qi.item_id
                WHERE length(qi.feedback_definitions) > 0
                  AND has(qi.feedback_definitions, fsc.name)  -- only names defined for this queue
            ), queue_items_with_comment_reviewers AS (
                SELECT DISTINCT
                    qi.queue_id,
                    qi.item_id,
                    c.created_by AS username
                FROM queue_items_final AS qi
                INNER JOIN comments_combined AS c
                 ON c.entity_id = qi.item_id
                WHERE length(c.created_by) > 0
            ), queue_items_with_reviewers AS (
                SELECT queue_id, item_id, username
                FROM queue_items_with_feedback_reviewers
                UNION DISTINCT
                SELECT queue_id, item_id, username
                FROM queue_items_with_comment_reviewers
            ), feedback_scores_reviewers_agg AS (
                SELECT
                    qir_sum.queue_id,
                    mapFromArrays(
                        groupArray(qir_sum.username),
                        groupArray(qir_sum.cnt)
                    ) AS reviewers
                FROM (
                    SELECT
                        queue_id,
                        username,
                        COUNT(1) AS cnt
                    FROM queue_items_with_reviewers
                    GROUP BY queue_id, username
                ) as qir_sum
                GROUP BY queue_id
            )
            SELECT
                q.project_id,
                q.id,
                q.name,
                q.description,
                q.instructions,
                q.comments_enabled,
                q.feedback_definitions,
                q.scope,
                q.created_at,
                q.created_by,
                q.last_updated_at,
                q.last_updated_by,
                qic.items_count as items_count,
                fs.feedback_scores as feedback_scores,
                fsra.reviewers as reviewers
            FROM queues_final AS q
            LEFT JOIN queue_items_count AS qic ON q.id = qic.queue_id
            LEFT JOIN feedback_scores_agg AS fs ON q.id = fs.queue_id
            LEFT JOIN feedback_scores_reviewers_agg AS fsra ON q.id = fsra.queue_id
            ORDER BY <if(sort_fields)><sort_fields>,<endif> q.id DESC
            <if(limit)> LIMIT :limit <endif>
            <if(offset)> OFFSET :offset <endif>
            """;

    private static final String COUNT = """
            WITH queues_final AS
            (
                SELECT id
                FROM annotation_queues
                WHERE workspace_id = :workspace_id
                <if(name)> AND ilike(name, CONCAT('%', :name, '%')) <endif>
                <if(filters)> AND (<filters>) <endif>
                ORDER BY last_updated_at DESC
                LIMIT 1 BY id
            )
            SELECT count(id) as count
            FROM queues_final
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull AnnotationQueueSortingFactory sortingFactory;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;

    @Override
    public Mono<Void> createBatch(@NonNull List<AnnotationQueue> annotationQueues) {
        log.info("Creating annotation queue batch with '{}' items", annotationQueues.size());

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> createBatch(annotationQueues, connection))
                .then()
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Created annotation queue batch with '{}' items", annotationQueues.size());
                    }
                });
    }

    @Override
    public Mono<AnnotationQueue> findById(@NonNull UUID id) {
        log.debug("Finding annotation queue by id '{}'", id);

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> findById(id, connection))
                .flatMap(this::mapAnnotationQueue)
                .singleOrEmpty();
    }

    @Override
    public Mono<AnnotationQueueInfo> findQueueInfoById(UUID id) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> findQueueInfoById(id, connection))
                .flatMap(result -> result.map((row, rowMetadata) -> AnnotationQueueInfo.builder()
                        .id(row.get("id", UUID.class))
                        .projectId(row.get("project_id", UUID.class))
                        .build()))
                .singleOrEmpty();
    }

    @Override
    public Mono<Void> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate update) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> update(id, update, connection))
                .then();
    }

    @Override
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds, @NonNull UUID projectId) {
        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> createItems(queueId, itemIds, projectId, connection))
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds, @NonNull UUID projectId) {
        if (itemIds.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var template = TemplateUtils.newST(DELETE_ITEMS_BY_IDS);

                    var statement = connection.createStatement(template.render())
                            .bind("project_id", projectId.toString())
                            .bind("queue_id", queueId.toString())
                            .bind("item_ids", itemIds.toArray(UUID[]::new));

                    return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    @Override
    public Mono<Long> deleteBatch(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Mono.just(0L);
        }

        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> {
                    var statement = connection.createStatement(DELETE_BATCH)
                            .bind("ids", ids.toArray(UUID[]::new));

                    return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Flux<? extends Result> findById(UUID id, Connection connection) {
        var template = TemplateUtils.newST(FIND);
        template.add("id", id.toString());

        var statement = connection
                .createStatement(template.render())
                .bind("id", id);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Flux<? extends Result> findQueueInfoById(UUID id, Connection connection) {
        var statement = connection
                .createStatement(SELECT_QUEUE_INFO_BY_ID)
                .bind("id", id);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Mono<? extends Result> createBatch(List<AnnotationQueue> annotationQueues, Connection connection) {
        var queryItems = getQueryItemPlaceHolder(annotationQueues.size());
        var template = TemplateUtils.newST(BATCH_INSERT).add("items", queryItems);

        Statement statement = connection.createStatement(template.render());

        int i = 0;
        for (AnnotationQueue annotationQueue : annotationQueues) {
            statement.bind("id" + i, annotationQueue.id())
                    .bind("project_id" + i, annotationQueue.projectId())
                    .bind("name" + i, annotationQueue.name())
                    .bind("description" + i, annotationQueue.description() != null ? annotationQueue.description() : "")
                    .bind("instructions" + i,
                            annotationQueue.instructions() != null ? annotationQueue.instructions() : "")
                    .bind("scope" + i, annotationQueue.scope().getValue())
                    .bind("comments_enabled" + i, annotationQueue.commentsEnabled())
                    .bind("feedback_definitions" + i,
                            annotationQueue.feedbackDefinitionNames() != null
                                    ? annotationQueue.feedbackDefinitionNames().toArray(String[]::new)
                                    : new String[]{});
            i++;
        }

        return makeMonoContextAware(
                bindUserNameAndWorkspaceContext(statement));
    }

    private Publisher<? extends Result> createItems(UUID queueId, Set<UUID> itemIds, UUID projectId,
            Connection connection) {
        var queryItems = getQueryItemPlaceHolder(itemIds.size());
        var template = TemplateUtils.newST(BATCH_ITEMS_INSERT).add("items", queryItems);

        var statement = connection.createStatement(template.render());
        statement.bind("queue_id", queueId.toString())
                .bind("project_id", projectId.toString());

        int index = 0;
        for (UUID itemId : itemIds) {
            statement.bind("item_id" + index, itemId.toString());
            index++;
        }

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement));
    }

    private Publisher<? extends Result> update(UUID id, AnnotationQueueUpdate update, Connection connection) {

        var template = newUpdateTemplate(update, UPDATE);

        var statement = connection.createStatement(template.render());
        statement.bind("id", id);

        bindUpdateParams(update, statement);

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement));
    }

    private Publisher<AnnotationQueue> mapAnnotationQueue(Result result) {
        return result.map((row, rowMetadata) -> AnnotationQueue.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .description(row.get("description", String.class))
                .instructions(row.get("instructions", String.class))
                .commentsEnabled(row.get("comments_enabled", Integer.class) == 1)
                .feedbackDefinitionNames(Arrays.stream(row.get("feedback_definitions", String[].class))
                        .toList())
                .scope(AnnotationQueue.AnnotationScope.fromString(row.get("scope", String.class)))
                .itemsCount(row.get("items_count", Long.class))
                .reviewers(mapReviewers(row))
                .feedbackScores(getFeedbackScores(row, "feedback_scores"))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build());
    }

    private List<AnnotationQueueReviewer> mapReviewers(Row row) {
        List<AnnotationQueueReviewer> reviewers = Optional
                .ofNullable(row.get("reviewers", Map.class))
                .map(map -> (Map<String, ? extends Number>) map)
                .orElse(Map.of())
                .entrySet()
                .stream()
                .map(reviewer -> AnnotationQueueReviewer.builder()
                        .username(reviewer.getKey())
                        .status(reviewer.getValue().longValue())
                        .build())
                .toList();

        return reviewers.isEmpty() ? null : reviewers;
    }

    @Override
    public Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size,
            AnnotationQueueSearchCriteria searchCriteria) {
        return countTotal(searchCriteria).flatMap(total -> find(page, size, searchCriteria, total));
    }

    private Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size,
            AnnotationQueueSearchCriteria searchCriteria, Long total) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> find(page, size, searchCriteria, connection))
                .flatMap(this::mapAnnotationQueue)
                .collectList()
                .map(annotationQueues -> new AnnotationQueue.AnnotationQueuePage(page, annotationQueues.size(), total,
                        annotationQueues,
                        sortingFactory.getSortableFields()));
    }

    private Publisher<? extends Result> find(int page, int size, AnnotationQueueSearchCriteria searchCriteria,
            Connection connection) {
        log.info("Finding annotation queues by '{}', page '{}', size '{}'", searchCriteria, page, size);

        var sorting = sortingQueryBuilder.toOrderBySql(searchCriteria.sortingFields());

        int offset = (page - 1) * size;

        var template = newFindTemplate(FIND, searchCriteria);

        template.add("sort_fields", sorting);
        template.add("limit", size);
        template.add("offset", offset);

        var statement = connection.createStatement(template.render())
                .bind("limit", size)
                .bind("offset", offset);

        bindSearchCriteria(statement, searchCriteria);
        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Mono<Long> countTotal(AnnotationQueueSearchCriteria searchCriteria) {
        return Mono.from(connectionFactory.create())
                .flatMapMany(connection -> countTotal(searchCriteria, connection))
                .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                .reduce(0L, Long::sum);
    }

    private Publisher<? extends Result> countTotal(AnnotationQueueSearchCriteria searchCriteria,
            Connection connection) {
        var template = newFindTemplate(COUNT, searchCriteria);

        var statement = connection.createStatement(template.render());
        bindSearchCriteria(statement, searchCriteria);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private ST newFindTemplate(String query, AnnotationQueueSearchCriteria searchCriteria) {
        var template = TemplateUtils.newST(query);

        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> template.add("name", name));
        Optional.ofNullable(searchCriteria.filters())
                .flatMap(filters -> filterQueryBuilder.toAnalyticsDbFilters(filters, FilterStrategy.ANNOTATION_QUEUE))
                .ifPresent(annotationQueueFilters -> template.add("filters", annotationQueueFilters));

        return template;
    }

    private ST newUpdateTemplate(AnnotationQueueUpdate update, String sql) {
        var template = TemplateUtils.newST(sql);

        Optional.ofNullable(update.name())
                .ifPresent(name -> template.add("name", update.name()));
        Optional.ofNullable(update.description())
                .ifPresent(description -> template.add("description", update.description()));
        Optional.ofNullable(update.instructions())
                .ifPresent(instructions -> template.add("instructions", update.instructions()));
        Optional.ofNullable(update.commentsEnabled())
                .ifPresent(commentsEnabled -> template.add("comments_enabled", true));
        Optional.ofNullable(update.feedbackDefinitionNames())
                .ifPresent(feedbackDefinitionNames -> {
                    template.add("has_feedback_definitions", true);
                    template.add("feedback_definitions", feedbackDefinitionNames.toArray(String[]::new));
                });

        return template;
    }

    private void bindUpdateParams(AnnotationQueueUpdate update, Statement statement) {

        Optional.ofNullable(update.name())
                .ifPresent(name -> statement.bind("name", update.name()));
        Optional.ofNullable(update.description())
                .ifPresent(description -> statement.bind("description", update.description()));
        Optional.ofNullable(update.instructions())
                .ifPresent(instructions -> statement.bind("instructions", update.instructions()));
        Optional.ofNullable(update.commentsEnabled())
                .ifPresent(commentsEnabled -> statement.bind("comments_enabled", update.commentsEnabled()));
        Optional.ofNullable(update.feedbackDefinitionNames())
                .ifPresent(feedbackDefinitionNames -> statement.bind("feedback_definitions",
                        feedbackDefinitionNames.toArray(String[]::new)));
    }

    private void bindSearchCriteria(Statement statement, AnnotationQueueSearchCriteria searchCriteria) {
        Optional.ofNullable(searchCriteria.name())
                .ifPresent(name -> statement.bind("name", name));
        Optional.ofNullable(searchCriteria.filters())
                .ifPresent(filters -> filterQueryBuilder.bind(statement, filters, FilterStrategy.ANNOTATION_QUEUE));
    }
}
