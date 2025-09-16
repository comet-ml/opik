package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
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
import org.reactivestreams.Publisher;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;
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

    Mono<Void> createBatch(List<AnnotationQueue> annotationQueues);

    Mono<AnnotationQueue> findById(UUID id);

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
            ORDER BY last_updated_at DESC
            LIMIT 1 BY id
            """;

    private final @NonNull ConnectionFactory connectionFactory;

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
                    var template = new ST(DELETE_ITEMS_BY_IDS);

                    var statement = connection.createStatement(template.render())
                            .bind("project_id", projectId.toString())
                            .bind("queue_id", queueId.toString())
                            .bind("item_ids", itemIds.toArray(UUID[]::new));

                    return makeMonoContextAware(bindWorkspaceIdToMono(statement));
                })
                .flatMap(Result::getRowsUpdated)
                .reduce(0L, Long::sum);
    }

    private Flux<? extends Result> findById(UUID id, Connection connection) {
        var statement = connection.createStatement(SELECT_BY_ID)
                .bind("id", id);

        return makeFluxContextAware(bindWorkspaceIdToFlux(statement));
    }

    private Mono<? extends Result> createBatch(List<AnnotationQueue> annotationQueues, Connection connection) {
        var queryItems = getQueryItemPlaceHolder(annotationQueues.size());
        var template = new ST(BATCH_INSERT).add("items", queryItems);

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
                            annotationQueue.feedbackDefinitions() != null
                                    ? annotationQueue.feedbackDefinitions().toArray(UUID[]::new)
                                    : new UUID[]{});
            i++;
        }

        return makeMonoContextAware(
                bindUserNameAndWorkspaceContext(statement));
    }

    private Publisher<? extends Result> createItems(UUID queueId, Set<UUID> itemIds, UUID projectId,
            Connection connection) {
        var queryItems = getQueryItemPlaceHolder(itemIds.size());
        var template = new ST(BATCH_ITEMS_INSERT).add("items", queryItems);

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

    private Publisher<AnnotationQueue> mapAnnotationQueue(Result result) {
        return result.map((row, rowMetadata) -> AnnotationQueue.builder()
                .id(row.get("id", UUID.class))
                .projectId(row.get("project_id", UUID.class))
                .name(row.get("name", String.class))
                .description(row.get("description", String.class))
                .instructions(row.get("instructions", String.class))
                .commentsEnabled(row.get("comments_enabled", Integer.class) == 1)
                .feedbackDefinitions(Arrays.stream(row.get("feedback_definitions", String[].class))
                        .map(UUID::fromString)
                        .toList())
                .scope(AnnotationQueue.AnnotationScope.fromString(row.get("scope", String.class)))
                .createdAt(row.get("created_at", Instant.class))
                .createdBy(row.get("created_by", String.class))
                .lastUpdatedAt(row.get("last_updated_at", Instant.class))
                .lastUpdatedBy(row.get("last_updated_by", String.class))
                .build());
    }
}
