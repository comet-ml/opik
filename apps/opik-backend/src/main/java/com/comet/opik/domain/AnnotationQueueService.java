package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.auth.RequestContext;
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

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(AnnotationQueueServiceImpl.class)
public interface AnnotationQueueService {

    Mono<UUID> create(AnnotationQueue annotationQueue);

    Mono<Integer> createBatch(AnnotationQueueBatch batch);

    Mono<AnnotationQueue> findById(@NonNull UUID id);

    Mono<Void> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate updateRequest);

    Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size, AnnotationQueueSearchCriteria searchCriteria);

    Mono<Long> addItems(UUID queueId, Set<UUID> itemIds);

    Mono<Long> removeItems(UUID queueId, Set<UUID> itemIds);

    Mono<Long> deleteBatch(Set<UUID> ids);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;

    @Override
    public Mono<UUID> create(AnnotationQueue annotationQueue) {
        annotationQueue = prepareAnnotationQueue(annotationQueue);

        return annotationQueueDAO.createBatch(List.of(annotationQueue))
                .thenReturn(annotationQueue.id())
                .subscribeOn(Schedulers.boundedElastic());
    }

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

    @Override
    @WithSpan
    public Mono<AnnotationQueue> findById(@NonNull UUID id) {
        log.debug("Finding annotation queue by id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .flatMap(this::enhanceWithProjectName)
                .doOnSuccess(queue -> log.debug("Found annotation queue with id '{}'", id))
                .doOnError(error -> log.info("Annotation queue not found with id '{}'", id));
    }

    public Mono<Void> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate updateRequest) {
        log.info("Updating annotation queue with id '{}'", id);

        return IdGenerator
                .validateVersionAsync(id, "AnnotationQueue")
                .then(annotationQueueDAO.update(id, updateRequest));
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size,
            AnnotationQueueSearchCriteria searchCriteria) {
        log.info("Finding annotation queues by '{}', page '{}', size '{}'", searchCriteria, page, size);

        return annotationQueueDAO.find(page, size, searchCriteria)
                .flatMap(this::enhancePageWithProjectNames)
                .doOnSuccess(result -> log.debug("Found annotation queues by '{}', count '{}', page '{}', size '{}'",
                        searchCriteria, result.content().size(), page, size))
                .doOnError(error -> log.info("Failed to find annotation queues by '{}'", searchCriteria, error));
    }

    @WithSpan
    @Override
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        return annotationQueueDAO.findQueueInfoById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.addItems(queueId, itemIds, queue.projectId()))
                .doOnSuccess(addedCount -> log.debug("Successfully added '{}' items to annotation queue with id '{}'",
                        addedCount, queueId))
                .doOnError(error -> log.info("Failed to add items to annotation queue with id '{}'", queueId, error));
    }

    @Override
    @WithSpan
    public Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        return annotationQueueDAO.findQueueInfoById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> annotationQueueDAO.removeItems(queueId, itemIds, queue.projectId()))
                .doOnSuccess(removedCount -> log.debug(
                        "Successfully removed '{}' items from annotation queue with id '{}'", removedCount, queueId))
                .doOnError(error -> log.info("Failed to remove items from annotation queue with id '{}'", queueId,
                        error));
    }

    @Override
    @WithSpan
    public Mono<Long> deleteBatch(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.debug("Annotation queue ids list is empty, returning");
            return Mono.just(0L);
        }

        log.info("Deleting annotation queue batch with '{}' items", ids.size());

        return annotationQueueDAO.deleteBatch(ids)
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(deletedCount -> log.debug("Successfully deleted '{}' annotation queues", deletedCount))
                .doOnError(error -> log.info("Failed to delete annotation queue batch", error));
    }

    private Mono<AnnotationQueue> enhanceWithProjectName(AnnotationQueue annotationQueue) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            List<Project> projects = projectService.findByIds(workspaceId, Set.of(annotationQueue.projectId()));
            if (projects.isEmpty()) {
                log.warn("Project not found for annotation queue '{}' with project id '{}'",
                        annotationQueue.id(), annotationQueue.projectId());
                return Mono.just(annotationQueue);
            }

            String projectName = projects.getFirst().name();
            return Mono.just(annotationQueue.toBuilder()
                    .projectName(projectName)
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<AnnotationQueue.AnnotationQueuePage> enhancePageWithProjectNames(
            AnnotationQueue.AnnotationQueuePage page) {
        if (page.content().isEmpty()) {
            return Mono.just(page);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            // Extract all unique project IDs
            Set<UUID> projectIds = page.content().stream()
                    .map(AnnotationQueue::projectId)
                    .collect(Collectors.toSet());

            // Create mapping from project ID to project name
            Map<UUID, String> projectIdToNameMap = projectService.findIdToNameByIds(workspaceId, projectIds);

            // Enhance all annotation queues with project names
            List<AnnotationQueue> enhancedQueues = page.content().stream()
                    .map(queue -> {
                        String projectName = projectIdToNameMap.get(queue.projectId());
                        if (projectName == null) {
                            log.warn("Project not found for annotation queue '{}' with project id '{}'",
                                    queue.id(), queue.projectId());
                        }
                        return queue.toBuilder()
                                .projectName(projectName)
                                .build();
                    })
                    .toList();

            // Return enhanced page
            return Mono.just(page.toBuilder()
                    .content(enhancedQueues)
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private AnnotationQueue prepareAnnotationQueue(AnnotationQueue annotationQueue) {
        UUID id = annotationQueue.id() == null ? idGenerator.generateId() : annotationQueue.id();
        IdGenerator.validateVersion(id, "AnnotationQueue");

        log.debug("Preparing annotation queue with id '{}', name '{}', project '{}'",
                id, annotationQueue.name(), annotationQueue.projectId());

        return annotationQueue.toBuilder()
                .id(id)
                .commentsEnabled(annotationQueue.commentsEnabled() != null ? annotationQueue.commentsEnabled() : false)
                .feedbackDefinitionNames(annotationQueue.feedbackDefinitionNames() != null
                        ? annotationQueue.feedbackDefinitionNames()
                        : List.of())
                .build();
    }

    private NotFoundException createNotFoundError(UUID id) {
        var message = "Annotation queue not found: '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }
}
