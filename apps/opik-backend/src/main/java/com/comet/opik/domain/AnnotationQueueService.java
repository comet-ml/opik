package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemType;
import com.comet.opik.api.AnnotationQueueScope;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(AnnotationQueueServiceImpl.class)
public interface AnnotationQueueService {

    Mono<AnnotationQueue> create(@NonNull AnnotationQueue annotationQueue);

    Mono<AnnotationQueue> findById(@NonNull UUID id);

    Mono<AnnotationQueue.AnnotationQueuePage> find(
            int page,
            int size,
            String search,
            AnnotationQueueScope scope,
            List<SortingField> sortingFields);

    Mono<AnnotationQueue> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate annotationQueueUpdate);

    Mono<Void> delete(@NonNull Set<UUID> ids);

    Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds, @NonNull AnnotationQueueItemType itemType);

    Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds);

    Mono<List<java.util.Map<String, Object>>> getItems(@NonNull UUID queueId, int page, int size);

    Mono<Long> getItemsCount(@NonNull UUID queueId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public Mono<AnnotationQueue> create(@NonNull AnnotationQueue annotationQueue) {
        var id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "annotation_queue");
        var userName = requestContext.get().getUserName();

        var annotationQueueToSave = annotationQueue.toBuilder()
                .id(id)
                .createdAt(Instant.now())
                .createdBy(userName)
                .lastUpdatedAt(Instant.now())
                .lastUpdatedBy(userName)
                .build();

        log.info("Creating annotation queue with name '{}' and id '{}'", annotationQueue.name(), id);

        return annotationQueueDAO.save(annotationQueueToSave)
                .doOnSuccess(saved -> log.info("Successfully created annotation queue with id '{}'", saved.id()))
                .doOnError(error -> log.error("Failed to create annotation queue with name '{}'",
                        annotationQueue.name(), error));
    }

    @Override
    public Mono<AnnotationQueue> findById(@NonNull UUID id) {
        log.debug("Finding annotation queue by id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .doOnSuccess(queue -> log.debug("Found annotation queue with id '{}'", id))
                .doOnError(error -> log.debug("Annotation queue not found with id '{}'", id));
    }

    @Override
    public Mono<AnnotationQueue.AnnotationQueuePage> find(
            int page,
            int size,
            String search,
            AnnotationQueueScope scope,
            List<SortingField> sortingFields) {

        log.debug("Finding annotation queues with page '{}', size '{}', search '{}', scope '{}'",
                page, size, search, scope);

        return annotationQueueDAO.find(page, size, search, scope, sortingFields)
                .doOnSuccess(result -> log.debug("Found '{}' annotation queues", result.content().size()))
                .doOnError(error -> log.error("Failed to find annotation queues", error));
    }

    @Override
    public Mono<AnnotationQueue> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate annotationQueueUpdate) {
        var userName = requestContext.get().getUserName();

        log.info("Updating annotation queue with id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .flatMap(existingQueue -> {
                    var updateWithUser = annotationQueueUpdate.toBuilder()
                            .build();

                    return annotationQueueDAO.update(id, updateWithUser)
                            .then(annotationQueueDAO.findById(id));
                })
                .doOnSuccess(updated -> log.info("Successfully updated annotation queue with id '{}'", id))
                .doOnError(error -> log.error("Failed to update annotation queue with id '{}'", id, error));
    }

    @Override
    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("Annotation queue ids list is empty, returning");
            return Mono.empty();
        }

        log.info("Deleting annotation queues with ids count '{}'", ids.size());

        return annotationQueueDAO.delete(ids)
                .doOnSuccess(deletedCount -> log.info("Successfully deleted '{}' annotation queues", deletedCount))
                .doOnError(
                        error -> log.error("Failed to delete annotation queues with ids count '{}'", ids.size(), error))
                .then();
    }

    @Override
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds,
            @NonNull AnnotationQueueItemType itemType) {
        if (itemIds.isEmpty()) {
            log.info("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        log.info("Adding '{}' items to annotation queue with id '{}'", itemIds.size(), queueId);

        return annotationQueueDAO.findById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.addItems(queueId, itemIds, itemType))
                .doOnSuccess(addedCount -> log.info("Successfully added '{}' items to annotation queue with id '{}'",
                        addedCount, queueId))
                .doOnError(error -> log.error("Failed to add items to annotation queue with id '{}'", queueId, error));
    }

    @Override
    public Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.info("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        log.info("Removing '{}' items from annotation queue with id '{}'", itemIds.size(), queueId);

        return annotationQueueDAO.findById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.removeItems(queueId, itemIds))
                .doOnSuccess(removedCount -> log.info(
                        "Successfully removed '{}' items from annotation queue with id '{}'", removedCount, queueId))
                .doOnError(error -> log.error("Failed to remove items from annotation queue with id '{}'", queueId,
                        error));
    }

    @Override
    public Mono<List<java.util.Map<String, Object>>> getItems(@NonNull UUID queueId, int page, int size) {
        log.info("Getting items for annotation queue with id '{}', page '{}', size '{}'", queueId, page, size);

        return annotationQueueDAO.findById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.getItems(queueId, page, size))
                .doOnSuccess(items -> log.info("Successfully retrieved '{}' items for annotation queue with id '{}'",
                        items.size(), queueId))
                .doOnError(error -> log.error("Failed to get items for annotation queue with id '{}'", queueId, error));
    }

    @Override
    public Mono<Long> getItemsCount(@NonNull UUID queueId) {
        log.info("Getting items count for annotation queue with id '{}'", queueId);

        return annotationQueueDAO.findById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.getItemsCount(queueId))
                .doOnSuccess(
                        count -> log.info("Successfully retrieved items count '{}' for annotation queue with id '{}'",
                                count, queueId))
                .doOnError(error -> log.error("Failed to get items count for annotation queue with id '{}'", queueId,
                        error));
    }

    private NotFoundException createNotFoundError(UUID id) {
        var message = "Annotation queue not found: '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }
}
