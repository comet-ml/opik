package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemType;
import com.comet.opik.api.AnnotationQueueScope;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Row;
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
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToFlux;
import static com.comet.opik.domain.AsyncContextUtils.bindWorkspaceIdToMono;
import static com.comet.opik.utils.AsyncUtils.makeFluxContextAware;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(AnnotationQueueDAOImpl.class)
public interface AnnotationQueueDAO {

    Mono<AnnotationQueue> save(AnnotationQueue annotationQueue);

    Mono<AnnotationQueue> findById(UUID id);

    Mono<AnnotationQueue.AnnotationQueuePage> find(
            int page,
            int size,
            String search,
            AnnotationQueueScope scope,
            List<SortingField> sortingFields);

    Mono<Long> update(UUID id, AnnotationQueueUpdate annotationQueueUpdate);

    Mono<Long> delete(Set<UUID> ids);

    Mono<Long> addItems(UUID queueId, Set<UUID> itemIds, AnnotationQueueItemType itemType);

    Mono<Long> removeItems(UUID queueId, Set<UUID> itemIds);

    Mono<Long> getItemsCount(UUID queueId);

    Mono<List<Map<String, Object>>> getItems(UUID queueId, int page, int size);

    Mono<AnnotationQueue> generateShareToken(UUID id);

    Mono<AnnotationQueue> findByShareToken(UUID shareToken);

    Mono<Long> getItemsCountByQueueId(UUID queueId, String workspaceId);

    Mono<List<Map<String, Object>>> getItemsByQueueId(UUID queueId, String workspaceId, int page, int size);

    Mono<Void> storeSMEAnnotation(UUID queueId, String workspaceId, UUID itemId, String smeId,
            List<FeedbackScore> feedbackScores, String comment, AnnotationQueueScope scope);

    Mono<Long> countCompletedAnnotationsBySME(UUID queueId, String workspaceId, String smeId);

    Mono<Long> countCompletedAnnotationsForQueue(UUID queueId, String workspaceId);

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueDAOImpl implements AnnotationQueueDAO {

    private static final String INSERT_ANNOTATION_QUEUE = """
            INSERT INTO annotation_queues (
                workspace_id, project_id, id, name, description, instructions,
                comments_enabled, feedback_definitions, scope,
                created_by, last_updated_by
            ) VALUES (
                :workspace_id, :project_id, :id, :name, :description, :instructions,
                :comments_enabled, :feedback_definitions, :scope,
                :user_name, :user_name
            )
            """;

    private static final String SELECT_BY_ID = """
            SELECT
                workspace_id,
                project_id,
                id,
                name,
                description,
                instructions,
                comments_enabled,
                feedback_definitions,
                scope,
                share_token,
                is_public,
                created_at,
                created_by,
                last_updated_at,
                last_updated_by
            FROM annotation_queues
            WHERE workspace_id = :workspace_id
            AND id = :id
            ORDER BY last_updated_at DESC
            LIMIT 1
            """;

    private static final String DELETE_ANNOTATION_QUEUES = """
            DELETE FROM annotation_queues
            WHERE workspace_id = :workspace_id
            AND id IN :ids
            """;

    private static final String DELETE_ANNOTATION_QUEUE_ITEMS = """
            DELETE FROM annotation_queue_items
            WHERE workspace_id = :workspace_id
            AND queue_id IN :queue_ids
            """;

    private final @NonNull TransactionTemplateAsync asyncTemplate;
    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull IdGenerator idGenerator;

    @Override
    @WithSpan
    public Mono<AnnotationQueue> save(AnnotationQueue annotationQueue) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(INSERT_ANNOTATION_QUEUE);

            statement.bind("project_id", annotationQueue.projectId())
                    .bind("id", annotationQueue.id())
                    .bind("name", annotationQueue.name())
                    .bind("description", annotationQueue.description())
                    .bind("instructions", annotationQueue.instructions())
                    .bind("comments_enabled", annotationQueue.commentsEnabled() ? 1 : 0)
                    .bind("feedback_definitions", annotationQueue.feedbackDefinitions().toArray(new UUID[0]))
                    .bind("scope", annotationQueue.scope().getValue());

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .thenReturn(annotationQueue);
        });
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue> findById(UUID id) {
        log.debug("Finding annotation queue by id '{}'", id);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(SELECT_BY_ID)
                    .bind("id", id);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map(this::mapAnnotationQueue))
                    .collectList()
                    .flatMap(queues -> {
                        if (queues.isEmpty()) {
                            return Mono.empty();
                        }
                        // Get the latest version (first in the list due to ORDER BY last_updated_at DESC)
                        var latestQueue = queues.get(0);
                        // Get items count for this specific queue
                        return getItemsCount(latestQueue.id())
                                .map(itemsCount -> latestQueue.toBuilder()
                                        .itemsCount(itemsCount)
                                        .build());
                    });
        });
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue.AnnotationQueuePage> find(
            int page,
            int size,
            String search,
            AnnotationQueueScope scope,
            List<SortingField> sortingFields) {

        log.debug("Finding annotation queues with page '{}', size '{}', search '{}', scope '{}'",
                page, size, search, scope);

        return getCount().flatMap(total -> find(page, size, total));
    }

    @Override
    @WithSpan
    public Mono<Long> update(UUID id, AnnotationQueueUpdate annotationQueueUpdate) {
        // TODO: Implement update functionality
        log.warn("Update annotation queue functionality not yet implemented for id '{}'", id);
        return Mono.just(1L);
    }

    @Override
    @WithSpan
    public Mono<Long> delete(Set<UUID> ids) {
        if (CollectionUtils.isEmpty(ids)) {
            return Mono.just(0L);
        }

        log.info("Deleting annotation queues, count '{}'", ids.size());

        return asyncTemplate.nonTransaction(connection -> {
            // First delete all items from the queues
            var deleteItemsStatement = connection.createStatement(DELETE_ANNOTATION_QUEUE_ITEMS)
                    .bind("queue_ids", ids.toArray(UUID[]::new));

            return makeMonoContextAware(bindWorkspaceIdToMono(deleteItemsStatement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()))
                    .then(Mono.defer(() -> {
                        // Then delete the queues themselves
                        var deleteQueuesStatement = connection.createStatement(DELETE_ANNOTATION_QUEUES)
                                .bind("ids", ids.toArray(UUID[]::new));

                        return makeMonoContextAware(bindWorkspaceIdToMono(deleteQueuesStatement))
                                .flatMap(result -> Mono.from(result.getRowsUpdated()));
                    }));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> addItems(UUID queueId, Set<UUID> itemIds, AnnotationQueueItemType itemType) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return Mono.just(0L);
        }

        log.debug("Adding '{}' items to annotation queue with id '{}'", itemIds.size(), queueId);

        return asyncTemplate.nonTransaction(connection -> {
            var template = new ST("""
                    INSERT INTO annotation_queue_items (
                        queue_id,
                        item_id,
                        item_type,
                        workspace_id,
                        created_at,
                        created_by,
                        last_updated_at,
                        last_updated_by
                    )
                    VALUES
                        <items:{item |
                            (
                                :queue_id,
                                :item_id<item.index>,
                                :item_type,
                                :workspace_id,
                                now64(9),
                                :user_name,
                                        now64(9),
                                :user_name
                            )
                            <if(item.hasNext)>,<endif>
                        }>
                    """);

            var queryItems = getQueryItemPlaceHolder(itemIds.size());
            template.add("items", queryItems);

            var statement = connection.createStatement(template.render());
            statement.bind("queue_id", queueId.toString())
                    .bind("item_type", itemType.getValue());

            int index = 0;
            for (UUID itemId : itemIds) {
                statement.bind("item_id" + index, itemId.toString());
                index++;
            }

            return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> removeItems(UUID queueId, Set<UUID> itemIds) {
        if (CollectionUtils.isEmpty(itemIds)) {
            return Mono.just(0L);
        }

        log.info("Removing '{}' items from annotation queue with id '{}'", itemIds.size(), queueId);

        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    DELETE FROM annotation_queue_items
                    WHERE workspace_id = :workspace_id
                    AND queue_id = :queue_id
                    AND item_id IN :item_ids
                    """)
                    .bind("queue_id", queueId.toString())
                    .bind("item_ids", itemIds.toArray(UUID[]::new));

            return makeMonoContextAware(bindWorkspaceIdToMono(statement))
                    .flatMap(result -> Mono.from(result.getRowsUpdated()));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> getItemsCount(UUID queueId) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT COUNT(*) as count
                    FROM annotation_queue_items
                    WHERE queue_id = :queue_id
                    AND workspace_id = :workspace_id
                    """)
                    .bind("queue_id", queueId.toString());

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L);
        });
    }

    @Override
    @WithSpan
    public Mono<Long> getItemsCountByQueueId(UUID queueId, String workspaceId) {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT COUNT(*) as count
                    FROM annotation_queue_items
                    WHERE queue_id = :queue_id
                    AND workspace_id = :workspace_id
                    """)
                    .bind("queue_id", queueId.toString())
                    .bind("workspace_id", workspaceId);

            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("count", Long.class))))
                    .defaultIfEmpty(0L);
        });
    }

    @Override
    @WithSpan
    public Mono<List<Map<String, Object>>> getItems(UUID queueId, int page, int size) {
        log.debug("Getting items for annotation queue with id '{}', page '{}', size '{}'", queueId, page, size);

        return asyncTemplate.nonTransaction(connection -> {
            var offset = (page - 1) * size;
            var sql = String.format("""
                    SELECT
                        item_id,
                        item_type,
                        created_at,
                        created_by
                    FROM annotation_queue_items
                    WHERE queue_id = :queue_id
                    AND workspace_id = :workspace_id
                    ORDER BY created_at DESC
                    LIMIT %d OFFSET %d
                    """, size, offset);

            var statement = connection.createStatement(sql)
                    .bind("queue_id", queueId.toString());

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map((row, metadata) -> Map.<String, Object>of(
                            "id", row.get("item_id", String.class),
                            "type", row.get("item_type", String.class),
                            "created_at", row.get("created_at", String.class),
                            "created_by", row.get("created_by", String.class))))
                    .collectList();
        });
    }

    @Override
    @WithSpan
    public Mono<List<Map<String, Object>>> getItemsByQueueId(UUID queueId, String workspaceId, int page, int size) {
        log.debug("Getting items for annotation queue with id '{}', page '{}', size '{}' (public access)", queueId,
                page, size);

        return asyncTemplate.nonTransaction(connection -> {
            var offset = (page - 1) * size;
            var sql = String.format("""
                    SELECT
                        item_id,
                        item_type,
                        created_at,
                        created_by
                    FROM annotation_queue_items
                    WHERE queue_id = :queue_id
                    AND workspace_id = :workspace_id
                    ORDER BY created_at DESC
                    LIMIT %d OFFSET %d
                    """, size, offset);

            var statement = connection.createStatement(sql)
                    .bind("queue_id", queueId.toString())
                    .bind("workspace_id", workspaceId);

            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, metadata) -> Map.<String, Object>of(
                            "id", row.get("item_id", String.class),
                            "type", row.get("item_type", String.class),
                            "created_at", row.get("created_at", String.class),
                            "created_by", row.get("created_by", String.class))))
                    .collectList();
        });
    }

    private Mono<Long> getCount() {
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT COUNT(*) as count
                    FROM annotation_queues
                    WHERE workspace_id = :workspace_id
                    """);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map(row -> row.get("count", Long.class)))
                    .reduce(Long::sum);
        });
    }

    private Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size, long total) {
        return asyncTemplate.nonTransaction(connection -> {
            var offset = (page - 1) * size;
            var sql = String.format(
                    """
                            SELECT
                                workspace_id,
                                project_id,
                                id,
                                name,
                                description,
                                instructions,
                                comments_enabled,
                                feedback_definitions,
                                scope,
                                share_token,
                                is_public,
                                created_at,
                                created_by,
                                last_updated_at,
                                last_updated_by
                            FROM (
                                SELECT *,
                                       ROW_NUMBER() OVER (PARTITION BY id ORDER BY last_updated_at DESC) as rn
                                FROM annotation_queues
                                WHERE workspace_id = :workspace_id
                            ) ranked_queues
                            WHERE rn = 1
                            ORDER BY created_at DESC
                            LIMIT %d OFFSET %d
                            """,
                    size, offset);

            var statement = connection.createStatement(sql);

            return makeFluxContextAware(bindWorkspaceIdToFlux(statement))
                    .flatMap(result -> result.map(this::mapAnnotationQueue))
                    .collectList()
                    .flatMap(queues -> {
                        // Get items count for each queue
                        return Flux.fromIterable(queues)
                                .flatMap(queue -> getItemsCount(queue.id())
                                        .map(itemsCount -> queue.toBuilder()
                                                .itemsCount(itemsCount)
                                                .build()))
                                .collectList();
                    })
                    .map(queues -> new AnnotationQueue.AnnotationQueuePage(
                            page,
                            queues.size(),
                            total,
                            queues,
                            List.of("id", "name", "scope", "created_at", "last_updated_at")));
        });
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue> generateShareToken(UUID id) {
        var shareToken = idGenerator.generateId();

        return findById(id)
                .flatMap(existingQueue -> {
                    // Check if share token already exists
                    if (existingQueue.shareToken() != null && !existingQueue.shareToken().toString().isEmpty()) {
                        log.info("Share token already exists for annotation queue with id '{}'", existingQueue.id());
                        return Mono.just(existingQueue);
                    }

                    return asyncTemplate.nonTransaction(connection -> {
                        // Insert a new version of the record with share_token and is_public updated
                        // This is the correct approach for ClickHouse ReplacingMergeTree
                        var statement = connection.createStatement("""
                                INSERT INTO annotation_queues (
                                    workspace_id, project_id, id, name, description, instructions,
                                    comments_enabled, feedback_definitions, scope,
                                    share_token, is_public,
                                    created_by, last_updated_by
                                ) VALUES (
                                    :workspace_id, :project_id, :id, :name, :description, :instructions,
                                    :comments_enabled, :feedback_definitions, :scope,
                                    :share_token, 1,
                                    :created_by, :user_name
                                )
                                """)
                                .bind("project_id", existingQueue.projectId())
                                .bind("id", existingQueue.id())
                                .bind("name", existingQueue.name())
                                .bind("description", existingQueue.description())
                                .bind("instructions", existingQueue.instructions())
                                .bind("comments_enabled", existingQueue.commentsEnabled() ? 1 : 0)
                                .bind("feedback_definitions", existingQueue.feedbackDefinitions().toArray(new UUID[0]))
                                .bind("scope", existingQueue.scope().getValue())
                                .bind("share_token", shareToken)
                                .bind("created_by", existingQueue.createdBy());

                        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement))
                                .flatMap(result -> Mono.from(result.getRowsUpdated()))
                                .thenReturn(existingQueue.toBuilder()
                                        .shareToken(shareToken)
                                        .isPublic(true)
                                        .build());
                    });
                });
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue> findByShareToken(UUID shareToken) {
        log.info("DAO: Finding annotation queue by share token: '{}'", shareToken);
        return asyncTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement("""
                    SELECT *
                    FROM annotation_queues
                    WHERE share_token = :share_token AND is_public = 1
                    """)
                    .bind("share_token", shareToken);

            return Mono.from(statement.execute())
                    .doOnNext(result -> log.info("DAO: Query executed, checking results"))
                    .flatMap(result -> {
                        log.info("DAO: Processing result set");
                        return Mono.from(result.map(this::mapAnnotationQueue))
                                .doOnNext(queue -> log.info("DAO: Mapped annotation queue: '{}'", queue.id()))
                                .doOnError(error -> log.error("DAO: Error mapping annotation queue", error));
                    })
                    .doOnError(
                            error -> log.error("DAO: Error executing query for share token: '{}'", shareToken, error));
        });
    }

    private AnnotationQueue mapAnnotationQueue(Row row, io.r2dbc.spi.RowMetadata metadata) {
        var feedbackDefinitionsArray = row.get("feedback_definitions", String[].class);
        var feedbackDefinitions = feedbackDefinitionsArray != null
                ? Arrays.stream(feedbackDefinitionsArray)
                        .map(UUID::fromString)
                        .toList()
                : List.<UUID>of();

        return AnnotationQueue.builder()
                .id(row.get("id", UUID.class))
                .workspaceId(row.get("workspace_id", String.class))
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .description(row.get("description", String.class))
                .instructions(row.get("instructions", String.class))
                .commentsEnabled(row.get("comments_enabled", Integer.class) == 1)
                .feedbackDefinitions(feedbackDefinitions)
                .scope(AnnotationQueueScope.fromValue(row.get("scope", String.class)))
                .shareToken(row.get("share_token", UUID.class))
                .isPublic(row.get("is_public", Integer.class) == 1)
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }

    @Override
    @WithSpan
    public Mono<Void> storeSMEAnnotation(UUID queueId, String workspaceId, UUID itemId, String smeId,
            List<FeedbackScore> feedbackScores, String comment, AnnotationQueueScope scope) {
        log.info("Storing SME annotation: queue '{}', item '{}', SME '{}'", queueId, itemId, smeId);

        return asyncTemplate.nonTransaction(connection -> {
            // Store annotation in sme_annotation_progress table
            var sql = """
                    INSERT INTO opik.sme_annotation_progress
                    (workspace_id, queue_id, sme_identifier, item_id, item_type, status, feedback_scores, comment, created_at, last_updated_at)
                    VALUES (:workspace_id, :queue_id, :sme_identifier, :item_id, :item_type, :status, :feedback_scores, :comment, :created_at, :last_updated_at)
                    """;

            // Convert feedback scores to JSON string (simpler approach)
            var feedbackScoresJson = feedbackScores.stream()
                    .map(score -> String.format("{\"name\":\"%s\",\"value\":%s}", score.name(), score.value()))
                    .reduce("[", (acc, item) -> acc.equals("[") ? acc + item : acc + "," + item) + "]";

            var itemType = switch (scope) {
                case TRACE -> "trace";
                case THREAD -> "thread";
            };

            var statement = connection.createStatement(sql);
            statement.bind("workspace_id", workspaceId);
            statement.bind("queue_id", queueId.toString());
            statement.bind("sme_identifier", smeId);
            statement.bind("item_id", itemId.toString());
            statement.bind("item_type", itemType);
            statement.bind("status", "completed");
            statement.bind("feedback_scores", feedbackScoresJson);
            statement.bind("comment", comment != null ? comment : "");
            statement.bind("created_at", Instant.now().toString().replace("Z", "")); // Remove Z for ClickHouse compatibility
            statement.bind("last_updated_at", Instant.now().toString().replace("Z", "")); // Remove Z for ClickHouse compatibility

            return Mono.from(statement.execute())
                    .then()
                    .doOnSuccess(v -> log.info("Successfully stored SME annotation: queue '{}', item '{}', SME '{}'",
                            queueId, itemId, smeId))
                    .doOnError(error -> log.error("Failed to store SME annotation: queue '{}', item '{}', SME '{}'",
                            queueId, itemId, smeId, error));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> countCompletedAnnotationsBySME(UUID queueId, String workspaceId, String smeId) {
        log.debug("Counting completed annotations: queue '{}', SME '{}'", queueId, smeId);

        return asyncTemplate.nonTransaction(connection -> {
            var sql = """
                    SELECT COUNT(*) as count
                    FROM opik.sme_annotation_progress
                    WHERE workspace_id = :workspace_id AND queue_id = :queue_id AND sme_identifier = :sme_identifier AND status = 'completed'
                    """;

            var statement = connection.createStatement(sql);
            statement.bind("workspace_id", workspaceId);
            statement.bind("queue_id", queueId.toString());
            statement.bind("sme_identifier", smeId);

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L)
                    .doOnSuccess(count -> log.debug("Found '{}' completed annotations for SME '{}' in queue '{}'",
                            count, smeId, queueId))
                    .doOnError(error -> log.error("Failed to count completed annotations: queue '{}', SME '{}'",
                            queueId, smeId, error));
        });
    }

    @Override
    @WithSpan
    public Mono<Long> countCompletedAnnotationsForQueue(UUID queueId, String workspaceId) {
        log.debug("Counting completed annotations for queue: '{}'", queueId);

        return asyncTemplate.nonTransaction(connection -> {
            var sql = """
                    SELECT COUNT(*) as count
                    FROM opik.sme_annotation_progress
                    WHERE workspace_id = :workspace_id AND queue_id = :queue_id AND status = 'completed'
                    """;

            var statement = connection.createStatement(sql);
            statement.bind("workspace_id", workspaceId);
            statement.bind("queue_id", queueId.toString());

            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, metadata) -> row.get("count", Long.class)))
                    .next()
                    .defaultIfEmpty(0L)
                    .doOnSuccess(count -> log.debug("Found '{}' completed annotations for queue '{}'",
                            count, queueId))
                    .doOnError(error -> log.error("Failed to count completed annotations for queue: '{}'",
                            queueId, error));
        });
    }
}