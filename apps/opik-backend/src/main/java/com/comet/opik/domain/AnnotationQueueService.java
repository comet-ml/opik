package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueItem;
import com.comet.opik.api.AnnotationQueueItemSource;
import com.comet.opik.api.AnnotationQueueSearchCriteria;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.LockResponse;
import com.comet.opik.api.Project;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.util.LinkedHashSet;
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

    Mono<Long> addItems(UUID queueId, Set<UUID> itemIds, AnnotationQueueItemSource source);

    Mono<Long> removeItems(UUID queueId, Set<UUID> itemIds);

    Mono<AnnotationQueueItem.AnnotationQueueItems> findItemsByIds(UUID queueId, Set<UUID> itemIds);

    Mono<Long> deleteBatch(Set<UUID> ids);

    Mono<LockResponse> tryLockItem(UUID queueId, UUID itemId);
}

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
class AnnotationQueueServiceImpl implements AnnotationQueueService {

    private static final String AUTOMATED_FILL_LOCK = "AnnotationQueueAutomatedFill";

    private final @NonNull AnnotationQueueDAO annotationQueueDAO;
    private final @NonNull AnnotationQueueItemLockService lockService;
    private final @NonNull LockService distributedLockService;
    private final @NonNull AnnotationQueueAutomationService automationService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull ProjectService projectService;

    @Override
    public Mono<UUID> create(AnnotationQueue annotationQueue) {
        AnnotationQueue queue = prepareAnnotationQueue(annotationQueue);

        return validateAutomations(List.of(queue))
                .then(annotationQueueDAO.createBatch(List.of(queue)))
                .then(saveAutomations(List.of(queue)))
                .thenReturn(queue.id())
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

        return validateAutomations(processedQueues)
                .then(annotationQueueDAO.createBatch(processedQueues))
                .then(saveAutomations(processedQueues))
                .thenReturn(processedQueues.size())
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Rejects invalid automation payloads before any queue is written.
     *
     * <p>The two stores share no transaction, so without this a bad payload would surface only once the
     * queue row already existed: the caller would see a 400 and a queue it did not think it had created,
     * and a retry would create a second one.
     */
    private Mono<Void> validateAutomations(List<AnnotationQueue> queues) {
        List<AnnotationQueue> withAutomation = queues.stream()
                .filter(queue -> queue.automation() != null)
                .toList();

        if (withAutomation.isEmpty()) {
            return Mono.empty();
        }

        Map<UUID, AnnotationQueueAutomation> automations = withAutomation.stream()
                .collect(Collectors.toMap(AnnotationQueue::id, AnnotationQueue::automation));

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromRunnable(() -> automationService.validate(workspaceId, automations));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    /**
     * Queue first, automation second. The two live in different stores so this is not transactional, and
     * the ordering is deliberate: a failure here leaves a queue whose automation is off — visible in the
     * UI and fixable by editing — rather than an automation row pointing at a queue that does not exist,
     * which the sweep would have to defend against on every run.
     *
     * <p>A failed automation write does not fail the request. Creating the queue was the caller's primary
     * intent and it succeeded; failing the whole call would let a transient MySQL blip turn into duplicate
     * queues on retry. The response reflects reality because the automation is read back from storage
     * rather than echoed from the request, so an unsaved automation comes back absent.
     */
    private Mono<Void> saveAutomations(List<AnnotationQueue> queues) {
        List<AnnotationQueue> withAutomation = queues.stream()
                .filter(queue -> queue.automation() != null)
                .toList();

        if (withAutomation.isEmpty()) {
            return Mono.empty();
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Mono.fromRunnable(() -> withAutomation.forEach(queue -> {
                try {
                    automationService.save(workspaceId, userName, queue.id(), queue.projectId(),
                            queue.scope(), queue.automation());
                } catch (BadRequestException e) {
                    // Invalid conditions are the caller's error, not a partial failure — surface them.
                    throw e;
                } catch (Exception e) {
                    log.error("Failed to save automation for annotation queue '{}'; the queue was created "
                            + "without it", queue.id(), e);
                }
            }));
        }).subscribeOn(Schedulers.boundedElastic()).then();
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue> findById(@NonNull UUID id) {
        log.debug("Finding annotation queue by id '{}'", id);

        return annotationQueueDAO.findById(id)
                .switchIfEmpty(Mono.error(createNotFoundError(id)))
                .flatMap(this::enhanceWithProjectName)
                .flatMap(this::enhanceWithAutomation)
                .doOnSuccess(queue -> log.debug("Found annotation queue with id '{}'", id))
                .doOnError(error -> log.info("Annotation queue not found with id '{}'", id));
    }

    public Mono<Void> update(@NonNull UUID id, @NonNull AnnotationQueueUpdate updateRequest) {
        log.info("Updating annotation queue with id '{}'", id);

        return IdGenerator
                .validateVersionAsync(id, "AnnotationQueue")
                .then(Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String userName = ctx.get(RequestContext.USER_NAME);
                    return annotationQueueDAO.findQueueInfoById(id)
                            .switchIfEmpty(Mono.error(createNotFoundError(id)))
                            .flatMap(queueInfo -> {
                                Mono<Void> updateMono = annotationQueueDAO.update(id, updateRequest);

                                if (updateRequest.automation() != null) {
                                    // Same reason as on create: reject the payload before the queue row is
                                    // rewritten, so a 400 never leaves a half-applied update behind.
                                    updateMono = Mono.fromRunnable(
                                            () -> automationService.validate(workspaceId,
                                                    Map.of(id, updateRequest.automation())))
                                            .subscribeOn(Schedulers.boundedElastic())
                                            .then(updateMono)
                                            .then(Mono.fromRunnable(
                                                    () -> automationService.save(workspaceId, userName, id,
                                                            queueInfo.projectId(), queueInfo.scope(),
                                                            updateRequest.automation()))
                                                    .subscribeOn(Schedulers.boundedElastic())
                                                    .then());
                                }

                                if (updateRequest.annotatorsPerItem() == null) {
                                    return updateMono;
                                }
                                int delta = updateRequest.annotatorsPerItem() - queueInfo.annotatorsPerItem();
                                return updateMono
                                        .then(lockService.updateCapacity(workspaceId, id, delta));
                            });
                }));
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueue.AnnotationQueuePage> find(int page, int size,
            AnnotationQueueSearchCriteria searchCriteria) {
        log.info("Finding annotation queues by '{}', page '{}', size '{}'", searchCriteria, page, size);

        return annotationQueueDAO.find(page, size, searchCriteria)
                .flatMap(this::enhancePageWithProjectNames)
                .flatMap(this::enhancePageWithAutomations)
                .doOnSuccess(result -> log.debug("Found annotation queues by '{}', count '{}', page '{}', size '{}'",
                        searchCriteria, result.content().size(), page, size))
                .doOnError(error -> log.info("Failed to find annotation queues by '{}'", searchCriteria, error));
    }

    @WithSpan
    @Override
    public Mono<Long> addItems(@NonNull UUID queueId, @NonNull Set<UUID> itemIds,
            @NonNull AnnotationQueueItemSource source) {
        if (itemIds.isEmpty()) {
            log.debug("Item ids list is empty, returning");
            return Mono.just(0L);
        }

        // Queue items reference trace/thread ids (v7 by construction); enforce so the referenced-id
        // policy is uniform. Past allowed — queues commonly collect older traces/threads.
        itemIds.forEach(itemId -> idGenerator.validateIdNotInFuture(itemId, "AnnotationQueue item"));

        return addEligibleItems(queueId, itemIds, source)
                .doOnSuccess(addedCount -> log.debug("Successfully added '{}' items to annotation queue with id '{}'",
                        addedCount, queueId))
                .doOnError(error -> log.info("Failed to add items to annotation queue with id '{}'", queueId, error));
    }

    /**
     * Automated fills of one queue run one at a time; manual adds are not serialised.
     *
     * <p>The ceiling is checked by reading the queue's size and then inserting, which is not atomic: two
     * consumers draining different batches for the same queue would otherwise both read the same size and
     * each fill the same headroom, taking the queue past its ceiling. The lock is scoped to the automated
     * path because that is the only one the ceiling applies to, so a person adding items never waits on it.
     *
     * <p>The queue lookup is inside the lock, not before it, so a queue deleted while a fill was waiting
     * is seen as gone rather than written to.
     */
    private Mono<Long> addEligibleItems(UUID queueId, Set<UUID> itemIds, AnnotationQueueItemSource source) {

        Mono<Long> add = Mono.defer(() -> annotationQueueDAO.findQueueInfoById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMap(queue -> eligibleItems(queueId, queue.projectId(), itemIds, source)
                        .flatMap(eligible -> eligible.isEmpty()
                                ? Mono.just(0L)
                                : annotationQueueDAO.addItems(queueId, eligible, queue.projectId(), source))));

        return source == AnnotationQueueItemSource.AUTOMATED
                ? distributedLockService.executeWithLock(new LockService.Lock(queueId, AUTOMATED_FILL_LOCK), add)
                : add;
    }

    /**
     * Automation never re-adds an item this queue has held before; a person may.
     *
     * <p>The asymmetry is deliberate. A manual re-add is an explicit act by someone who can see the queue,
     * and it is the escape hatch for recovering an item removed by mistake. Automation re-adding something
     * a reviewer deliberately removed is the loop the history table exists to prevent, so the check lives
     * here rather than in the routing listener — no automated caller can forget it.
     */
    private Mono<Set<UUID>> eligibleItems(UUID queueId, UUID projectId, Set<UUID> itemIds,
            AnnotationQueueItemSource source) {

        if (source != AnnotationQueueItemSource.AUTOMATED) {
            return Mono.just(itemIds);
        }

        return annotationQueueDAO.findPreviouslyAddedItems(queueId, projectId, itemIds)
                .map(alreadyAdded -> {
                    if (alreadyAdded.isEmpty()) {
                        return itemIds;
                    }
                    Set<UUID> eligible = itemIds.stream()
                            .filter(itemId -> !alreadyAdded.contains(itemId))
                            .collect(Collectors.toSet());
                    log.debug("Skipping '{}' items already routed to annotation queue '{}'",
                            alreadyAdded.size(), queueId);
                    return eligible;
                })
                .flatMap(eligible -> withinMaxItems(queueId, projectId, eligible));
    }

    /**
     * Holds automation to the queue's configured ceiling. Sits beside the already-added check for the same
     * reason: both are limits on what automation may do, and neither should depend on a caller remembering
     * to apply it.
     *
     * <p>A queue over its ceiling is filled to the ceiling rather than skipped wholesale — dropping a batch
     * of 500 because there is room for 3 would waste the 3. The remainder is not held anywhere; automation
     * will consider those entities again the next time one of their scores changes.
     */
    private Mono<Set<UUID>> withinMaxItems(UUID queueId, UUID projectId, Set<UUID> eligible) {
        if (eligible.isEmpty()) {
            return Mono.just(eligible);
        }

        return Mono.deferContextual(ctx -> Mono.just(
                automationService.findByQueueId(ctx.get(RequestContext.WORKSPACE_ID), queueId)
                        .map(AnnotationQueueAutomation::maxItemsInQueue)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(maxItemsInQueue -> maxItemsInQueue
                        .map(max -> annotationQueueDAO.countItems(queueId, projectId)
                                .map(held -> fillToMaxItems(queueId, eligible, max, held)))
                        .orElseGet(() -> Mono.just(eligible)));
    }

    static Set<UUID> fillToMaxItems(UUID queueId, Set<UUID> eligible, int maxItemsInQueue, long held) {
        long headroom = maxItemsInQueue - held;

        if (headroom <= 0) {
            log.debug("Annotation queue '{}' holds '{}' items and its automation ceiling is '{}'; "
                    + "skipping '{}' items", queueId, held, maxItemsInQueue, eligible.size());
            return Set.of();
        }

        if (eligible.size() <= headroom) {
            return eligible;
        }

        log.debug("Annotation queue '{}' has room for '{}' of '{}' items before its automation ceiling of '{}'",
                queueId, headroom, eligible.size(), maxItemsInQueue);

        // Sorted so which items land is deterministic rather than dependent on hash order.
        return eligible.stream()
                .sorted()
                .limit(headroom)
                .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    @Override
    @WithSpan
    public Mono<AnnotationQueueItem.AnnotationQueueItems> findItemsByIds(@NonNull UUID queueId,
            @NonNull Set<UUID> itemIds) {
        log.debug("Finding '{}' items of annotation queue with id '{}'", itemIds.size(), queueId);

        return annotationQueueDAO.findQueueInfoById(queueId)
                .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                .flatMapMany(queue -> annotationQueueDAO.findItemsByIds(queueId, queue.projectId(), itemIds))
                .collectList()
                .map(items -> AnnotationQueueItem.AnnotationQueueItems.builder().content(items).build())
                .doOnError(error -> log.info("Failed to find items of annotation queue with id '{}'", queueId, error));
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

        // Automation first, the mirror of the create ordering and for the same reason. Deleting the queue
        // first would let a failure here leave an enabled automation pointing at a queue that no longer
        // exists — which routing would still find, and then fail against for every matching score.
        // Reversed, a failure leaves a queue whose automation is gone: visible, and fixable by editing.
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return Mono.fromRunnable(() -> automationService.deleteByQueueIds(workspaceId, List.copyOf(ids)));
        })
                .then(annotationQueueDAO.deleteBatch(ids))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnSuccess(deletedCount -> log.debug("Successfully deleted '{}' annotation queues", deletedCount))
                .doOnError(error -> log.info("Failed to delete annotation queue batch", error));
    }

    @Override
    @WithSpan
    public Mono<LockResponse> tryLockItem(@NonNull UUID queueId, @NonNull UUID itemId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return annotationQueueDAO.findById(queueId)
                    .switchIfEmpty(Mono.error(createNotFoundError(queueId)))
                    .flatMap(queue -> annotationQueueDAO.getDistinctAnnotatorCount(
                            itemId, queue.projectId(),
                            queue.scope().getValue(),
                            queueId,
                            queue.feedbackDefinitionNames())
                            .map(scoredCount -> Map.entry(queue, scoredCount)))
                    .flatMap(entry -> lockService.tryLock(
                            workspaceId, queueId, itemId, userName,
                            entry.getKey().annotatorsPerItem(),
                            entry.getValue(),
                            entry.getKey().lockTimeoutSeconds()));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<AnnotationQueue> enhanceWithAutomation(AnnotationQueue annotationQueue) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.just(automationService.findByQueueId(workspaceId, annotationQueue.id())
                    .map(automation -> annotationQueue.toBuilder().automation(automation).build())
                    .orElse(annotationQueue));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    // One batched lookup per page rather than one per row — same shape as the project-name enrichment above.
    private Mono<AnnotationQueue.AnnotationQueuePage> enhancePageWithAutomations(
            AnnotationQueue.AnnotationQueuePage page) {
        if (page.content().isEmpty()) {
            return Mono.just(page);
        }

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            List<UUID> queueIds = page.content().stream()
                    .map(AnnotationQueue::id)
                    .toList();

            Map<UUID, AnnotationQueueAutomation> automations = automationService.findByQueueIds(workspaceId, queueIds);

            if (automations.isEmpty()) {
                return Mono.just(page);
            }

            return Mono.just(page.toBuilder()
                    .content(page.content().stream()
                            .map(queue -> queue.toBuilder()
                                    .automation(automations.get(queue.id()))
                                    .build())
                            .toList())
                    .build());
        }).subscribeOn(Schedulers.boundedElastic());
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
        // projectId is persisted without an existence check here, so enforce v7 to avoid storing an orphan v4.
        idGenerator.validateIdNotInFutureIfPresent(annotationQueue.projectId(), "project");

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
