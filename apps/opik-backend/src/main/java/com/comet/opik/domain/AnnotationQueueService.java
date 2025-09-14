package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueItemType;
import com.comet.opik.api.AnnotationQueueScope;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.SMEAnnotationProgress;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
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
import java.util.Map;
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

    Mono<AnnotationQueue> generateShareToken(@NonNull UUID id);

    Mono<AnnotationQueue> findByShareToken(@NonNull UUID shareToken);

    Mono<Long> getItemsCountForPublicAccess(@NonNull UUID queueId, @NonNull String workspaceId);

    Mono<List<java.util.Map<String, Object>>> getItemsForPublicAccess(@NonNull UUID queueId,
            @NonNull String workspaceId, int page, int size);

    Mono<Object> getItemDataForAnnotation(@NonNull UUID queueId, @NonNull String workspaceId, @NonNull UUID itemId,
            @NonNull AnnotationQueueScope scope);

    Mono<SMEAnnotationProgress.SMEProgressSummary> getSMEProgress(@NonNull UUID queueId, @NonNull String workspaceId,
            @NonNull String smeId);

    Mono<Map<String, Object>> getQueueProgress(@NonNull UUID queueId, @NonNull String workspaceId);

    Mono<Map<String, Object>> getNextItemForSME(@NonNull UUID queueId, @NonNull String smeId);

    Mono<Void> submitAnnotation(@NonNull UUID queueId, @NonNull UUID itemId, @NonNull String smeId,
            @NonNull List<FeedbackScore> feedbackScores, String comment);

    Mono<Void> submitAnnotationWithQueue(@NonNull AnnotationQueue queue, @NonNull UUID itemId, @NonNull String smeId,
            @NonNull List<FeedbackScore> feedbackScores, String comment);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull TraceService traceService;
    private final @NonNull TraceThreadService traceThreadService;
    private final @NonNull FeedbackScoreService feedbackScoreService;

    @Override
    @WithSpan
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
    @WithSpan
    public Mono<AnnotationQueue> findById(@NonNull UUID id) {
        log.debug("Finding annotation queue by id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .doOnSuccess(queue -> log.debug("Found annotation queue with id '{}'", id))
                .doOnError(error -> log.debug("Annotation queue not found with id '{}'", id));
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

        return annotationQueueDAO.find(page, size, search, scope, sortingFields)
                .doOnSuccess(result -> log.debug("Found '{}' annotation queues", result.content().size()))
                .doOnError(error -> log.error("Failed to find annotation queues", error));
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate annotationQueueUpdate) {
        log.info("Updating annotation queue with id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .flatMap(existingQueue -> annotationQueueDAO.update(id, annotationQueueUpdate)
                        .then(annotationQueueDAO.findById(id)))
                .doOnSuccess(updated -> log.info("Successfully updated annotation queue with id '{}'", id))
                .doOnError(error -> log.error("Failed to update annotation queue with id '{}'", id, error));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.debug("Annotation queue ids list is empty, returning");
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
    @WithSpan
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds,
            @NonNull AnnotationQueueItemType itemType) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
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
    @WithSpan
    public Mono<Long> removeItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
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
    @WithSpan
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
    @WithSpan
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

    @Override
    @WithSpan
    public Mono<AnnotationQueue> generateShareToken(@NonNull UUID id) {
        log.info("Generating share token for annotation queue with id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .flatMap(queue -> annotationQueueDAO.generateShareToken(id))
                .doOnSuccess(
                        queue -> log.info("Successfully generated share token for annotation queue with id '{}'", id))
                .doOnError(error -> log.error("Failed to generate share token for annotation queue with id '{}'", id,
                        error));
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue> findByShareToken(@NonNull UUID shareToken) {
        log.info("Finding annotation queue by share token: '{}'", shareToken);

        return annotationQueueDAO.findByShareToken(shareToken)
                .doOnNext(queue -> log.info("Found annotation queue with share token: '{}', queue id: '{}'", shareToken,
                        queue.id()))
                .doOnError(
                        error -> log.error("Error finding annotation queue with share token: '{}'", shareToken, error))
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("No annotation queue found with share token: '{}'", shareToken);
                    return Mono.error(new NotFoundException("Annotation queue not found or not public"));
                }));
    }

    @Override
    @WithSpan
    public Mono<Long> getItemsCountForPublicAccess(@NonNull UUID queueId, @NonNull String workspaceId) {
        log.info("Getting items count for annotation queue with id '{}' (public access)", queueId);

        return annotationQueueDAO.getItemsCountByQueueId(queueId, workspaceId)
                .doOnSuccess(count -> log.info(
                        "Successfully retrieved items count '{}' for annotation queue with id '{}' (public access)",
                        count, queueId))
                .doOnError(error -> log.error(
                        "Failed to get items count for annotation queue with id '{}' (public access)", queueId, error));
    }

    @Override
    @WithSpan
    public Mono<List<java.util.Map<String, Object>>> getItemsForPublicAccess(@NonNull UUID queueId,
            @NonNull String workspaceId, int page, int size) {
        log.info("Getting items for annotation queue with id '{}' (public access)", queueId);

        return annotationQueueDAO.getItemsByQueueId(queueId, workspaceId, page, size)
                .doOnSuccess(items -> log.info(
                        "Successfully retrieved '{}' items for annotation queue with id '{}' (public access)",
                        items.size(), queueId))
                .doOnError(error -> log.error("Failed to get items for annotation queue with id '{}' (public access)",
                        queueId, error));
    }

    @Override
    @WithSpan
    public Mono<Object> getItemDataForAnnotation(@NonNull UUID queueId, @NonNull String workspaceId,
            @NonNull UUID itemId, @NonNull AnnotationQueueScope scope) {
        log.debug("Getting item data for annotation: queue '{}', item '{}', scope '{}'", queueId, itemId, scope);

        // Fetch actual trace or thread data based on scope
        return switch (scope) {
            case TRACE -> {
                log.info("Fetching trace data for item '{}' in workspace '{}'", itemId, workspaceId);
                // Use TraceService to get the actual trace data
                yield traceService.get(itemId)
                        .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                                .put(RequestContext.USER_NAME, "sme-annotation-system"))
                        .doOnNext(trace -> log.info(
                                "Successfully retrieved trace '{}' for annotation: name='{}', input='{}'", itemId,
                                trace.name(), trace.input()))
                        .doOnError(error -> log.error(
                                "Failed to retrieve trace '{}' for annotation: {} - Error type: {}", itemId,
                                error.getMessage(), error.getClass().getSimpleName(), error))
                        .map(trace -> {
                            log.info("Mapping trace '{}' to response data", itemId);
                            try {
                                var result = new java.util.HashMap<String, Object>();
                                result.put("id", trace.id());
                                result.put("type", "trace");
                                result.put("input", trace.input());
                                result.put("output", trace.output());
                                result.put("metadata", trace.metadata() != null ? trace.metadata() : Map.of());
                                result.put("tags", trace.tags() != null ? trace.tags() : List.of());
                                result.put("start_time", trace.startTime());
                                result.put("end_time", trace.endTime());
                                result.put("name", trace.name());
                                log.info("Successfully mapped trace '{}' response data", itemId);
                                return result;
                            } catch (Exception e) {
                                log.error("Error during trace mapping for '{}': {}", itemId, e.getMessage(), e);
                                throw e;
                            }
                        })
                        .cast(Object.class)
                        .doOnNext(result -> log.info("Trace '{}' mapping completed successfully", itemId))
                        .doOnError(error -> log.error("Error after mapping trace '{}': {} - Error type: {}", itemId,
                                error.getMessage(), error.getClass().getSimpleName(), error))
                        .onErrorReturn(Map.of(
                                "id", itemId.toString(),
                                "type", "trace",
                                "error", "Trace not found or inaccessible"));
            }
            case THREAD -> {
                log.debug("Fetching thread data for item '{}'", itemId);
                // TODO: Implement thread data fetching when thread support is added
                yield Mono.just(Map.<String, Object>of(
                        "id", itemId.toString(),
                        "type", "thread",
                        "error", "Thread annotation not yet implemented"))
                        .cast(Object.class);
            }
        };
    }

    @Override
    @WithSpan
    public Mono<SMEAnnotationProgress.SMEProgressSummary> getSMEProgress(@NonNull UUID queueId,
            @NonNull String workspaceId, @NonNull String smeId) {
        log.debug("Getting SME progress: queue '{}', SME '{}'", queueId, smeId);

        // Get total items count and completed items count
        return getItemsCountForPublicAccess(queueId, workspaceId)
                .flatMap(totalItems -> annotationQueueDAO.countCompletedAnnotationsBySME(queueId, workspaceId, smeId)
                        .map(completedItems -> {
                            var completionPercentage = totalItems > 0
                                    ? (double) completedItems / totalItems * 100
                                    : 0.0;
                            var pendingItems = totalItems.intValue() - completedItems.intValue();

                            log.debug(
                                    "SME progress: queue '{}', SME '{}', total: {}, completed: {}, pending: {}, percentage: {}%",
                                    queueId, smeId, totalItems, completedItems, pendingItems, completionPercentage);

                            return SMEAnnotationProgress.SMEProgressSummary.builder()
                                    .totalItems(totalItems.intValue())
                                    .completedItems(completedItems.intValue())
                                    .skippedItems(0) // TODO: Implement skipped items tracking if needed
                                    .pendingItems(pendingItems)
                                    .nextItemId(null) // TODO: Implement next item logic
                                    .nextItemType(null)
                                    .completionPercentage(completionPercentage)
                                    .build();
                        }));
    }

    @Override
    @WithSpan
    public Mono<Map<String, Object>> getQueueProgress(@NonNull UUID queueId, @NonNull String workspaceId) {
        log.debug("Getting queue progress: queue '{}'", queueId);

        // Get total items count and completed items count
        return getItemsCountForPublicAccess(queueId, workspaceId)
                .flatMap(totalItems -> annotationQueueDAO.countCompletedAnnotationsForQueue(queueId, workspaceId)
                        .map(completedItems -> {
                            var progressPercentage = totalItems > 0 ? (double) completedItems / totalItems * 100 : 0.0;

                            log.debug("Queue progress: queue '{}', total: {}, completed: {}, percentage: {}%",
                                    queueId, totalItems, completedItems, progressPercentage);

                            return Map.<String, Object>of(
                                    "total_items", totalItems,
                                    "completed_items", completedItems,
                                    "progress_percentage", progressPercentage);
                        }));
    }

    @Override
    @WithSpan
    public Mono<Map<String, Object>> getNextItemForSME(@NonNull UUID queueId, @NonNull String smeId) {
        log.debug("Getting next item for SME: queue '{}', SME '{}'", queueId, smeId);

        // TODO: Implement next item logic
        // 1. Find items not yet processed by this SME
        // 2. Return next item in queue order
        // 3. Handle case when all items are processed

        return Mono.just(Map.of(
                "id", UUID.randomUUID().toString(),
                "type", "trace",
                "position", 1));
    }

    @Override
    @WithSpan
    public Mono<Void> submitAnnotation(@NonNull UUID queueId, @NonNull UUID itemId, @NonNull String smeId,
            @NonNull List<FeedbackScore> feedbackScores, String comment) {
        log.info("Submitting annotation: queue '{}', item '{}', SME '{}'", queueId, itemId, smeId);

        // For now, just store the feedback scores using the existing feedback score service
        // TODO: Implement full SME progress tracking in sme_annotation_progress table

        return findById(queueId)
                .flatMap(queue -> {
                    // Store feedback scores for the item
                    var feedbackScoresToStore = feedbackScores.stream()
                            .map(score -> score.toBuilder()
                                    .source(ScoreSource.UI)
                                    .createdAt(Instant.now())
                                    .lastUpdatedAt(Instant.now())
                                    .createdBy("sme")
                                    .lastUpdatedBy("sme")
                                    .build())
                            .toList();

                    // Store feedback scores for trace (simplified for now)
                    if (queue.scope() == AnnotationQueueScope.TRACE && !feedbackScoresToStore.isEmpty()) {
                        var scoreMonos = feedbackScoresToStore.stream()
                                .map(score -> feedbackScoreService.scoreTrace(itemId, score))
                                .toList();
                        return Mono.when(scoreMonos);
                    }

                    return Mono.empty();
                })
                .doOnSuccess(v -> log.info("Successfully submitted annotation: queue '{}', item '{}', SME '{}'",
                        queueId, itemId, smeId));
    }

    @Override
    @WithSpan
    public Mono<Void> submitAnnotationWithQueue(@NonNull AnnotationQueue queue, @NonNull UUID itemId,
            @NonNull String smeId,
            @NonNull List<FeedbackScore> feedbackScores, String comment) {
        log.info("Submitting annotation with queue data: queue '{}', item '{}', SME '{}'", queue.id(), itemId, smeId);

        // First, store the feedback scores using the regular feedback score system
        return Mono.when(
                feedbackScores.stream()
                        .map(score -> feedbackScoreService.scoreTrace(itemId, score)
                                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, queue.workspaceId())
                                        .put(RequestContext.USER_NAME, "sme-annotation-system")))
                        .toList())
                .then(annotationQueueDAO.storeSMEAnnotation(
                        queue.id(),
                        queue.workspaceId(),
                        itemId,
                        smeId,
                        feedbackScores,
                        comment,
                        queue.scope()))
                .doOnSuccess(v -> {
                    log.info("Successfully stored SME annotation and feedback scores: queue '{}', item '{}', SME '{}'",
                            queue.id(), itemId, smeId);
                    feedbackScores
                            .forEach(score -> log.debug("Stored feedback score: name='{}', value='{}', source='{}'",
                                    score.name(), score.value(), score.source()));
                    if (comment != null && !comment.trim().isEmpty()) {
                        log.debug("Stored annotation comment: '{}'", comment);
                    }
                }).doOnError(error -> {
                    log.error("Failed to store SME annotation: queue '{}', item '{}', SME '{}'",
                            queue.id(), itemId, smeId, error);
                });
    }

    private NotFoundException createNotFoundError(UUID id) {
        var message = "Annotation queue not found: '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }
}
