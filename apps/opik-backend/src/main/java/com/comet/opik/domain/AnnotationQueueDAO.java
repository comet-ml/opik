package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemType;
import com.comet.opik.api.AnnotationQueueScope;
import com.comet.opik.api.AnnotationQueueUpdate;
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
                created_at,
                created_by,
                last_updated_at,
                last_updated_by
            FROM annotation_queues
            WHERE workspace_id = :workspace_id
            AND id = :id
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
                    .singleOrEmpty()
                    .flatMap(queue -> {
                        // Get items count for this specific queue
                        return getItemsCount(queue.id())
                                .map(itemsCount -> queue.toBuilder()
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
            var sql = String.format("""
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
                        created_at,
                        created_by,
                        last_updated_at,
                        last_updated_by
                    FROM annotation_queues
                    WHERE workspace_id = :workspace_id
                    ORDER BY created_at DESC
                    LIMIT %d OFFSET %d
                    """, size, offset);

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

    private AnnotationQueue mapAnnotationQueue(Row row, io.r2dbc.spi.RowMetadata metadata) {
        var feedbackDefinitionsArray = row.get("feedback_definitions", String[].class);
        var feedbackDefinitions = feedbackDefinitionsArray != null
                ? Arrays.stream(feedbackDefinitionsArray)
                        .map(UUID::fromString)
                        .toList()
                : List.<UUID>of();

        return AnnotationQueue.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .description(row.get("description", String.class))
                .instructions(row.get("instructions", String.class))
                .commentsEnabled(row.get("comments_enabled", Integer.class) == 1)
                .feedbackDefinitions(feedbackDefinitions)
                .scope(AnnotationQueueScope.fromValue(row.get("scope", String.class)))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build();
    }
}