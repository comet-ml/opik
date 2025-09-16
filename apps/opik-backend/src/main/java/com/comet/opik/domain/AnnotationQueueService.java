package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(AnnotationQueueServiceImpl.class)
public interface AnnotationQueueService {

    Mono<Integer> createBatch(@NonNull AnnotationQueueBatch batch);

    Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds);

    Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull IdGenerator idGenerator;

    @Override
    @WithSpan
    public Mono<Integer> createBatch(@NonNull AnnotationQueueBatch batch) {
        log.info("Creating annotation queue batch with '{}' items", batch.annotationQueues().size());

        // Generate IDs and prepare annotation queues
        List<AnnotationQueue> processedQueues = batch.annotationQueues().stream()
                .map(this::prepareAnnotationQueue)
                .toList();

        return annotationQueueDAO.createBatch(processedQueues)
                .thenReturn(processedQueues.size())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @WithSpan
    @Override
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        return annotationQueueDAO.findById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.addItems(queueId, itemIds, queue.projectId()))
                .doOnSuccess(addedCount -> log.debug("Successfully added '{}' items to annotation queue with id '{}'",
                        addedCount, queueId))
                .doOnError(error -> log.debug("Failed to add items to annotation queue with id '{}'", queueId, error));
    }

    @Override
    @WithSpan
    public Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        return annotationQueueDAO.findById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.removeItems(queueId, itemIds, queue.projectId()))
                .doOnSuccess(removedCount -> log.debug(
                        "Successfully removed '{}' items from annotation queue with id '{}'", removedCount, queueId))
                .doOnError(error -> log.debug("Failed to remove items from annotation queue with id '{}'", queueId,
                        error));
    }

    private AnnotationQueue prepareAnnotationQueue(AnnotationQueue annotationQueue) {
        UUID id = annotationQueue.id() == null ? idGenerator.generateId() : annotationQueue.id();
        IdGenerator.validateVersion(id, "AnnotationQueue");

        log.debug("Preparing annotation queue with id '{}', name '{}', project '{}'",
                id, annotationQueue.name(), annotationQueue.projectId());

        return annotationQueue.toBuilder()
                .id(id)
                .commentsEnabled(annotationQueue.commentsEnabled() != null ? annotationQueue.commentsEnabled() : false)
                .feedbackDefinitions(annotationQueue.feedbackDefinitions() != null
                        ? annotationQueue.feedbackDefinitions()
                        : List.of())
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();
    }

    private NotFoundException createNotFoundError(UUID id) {
        var message = "Annotation queue not found: '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }
}
