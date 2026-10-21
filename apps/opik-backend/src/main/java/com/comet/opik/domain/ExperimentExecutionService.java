package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetType;
import com.comet.opik.api.EvaluationMethod;
import com.comet.opik.api.ExecutionPolicy;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentExecutionRequest;
import com.comet.opik.api.ExperimentExecutionResponse;
import com.comet.opik.api.ExperimentExecutionResponse.ExperimentInfo;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.OpikPromptEntry;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.TemplateStructure;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.events.ExperimentItemToProcess;
import com.comet.opik.api.filter.DatasetItemFilter;
import com.comet.opik.api.resources.v1.events.TestSuiteEvaluatorMapper;
import com.comet.opik.infrastructure.ExperimentExecutionConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Singleton
@Slf4j
public class ExperimentExecutionService {

    private static final int STREAM_PAGE_SIZE = 2000;
    private static final String PUBLISH_LOCK = "experiment-execution-publish";

    private final ExperimentService experimentService;
    private final ExperimentCancellationService cancellationService;
    private final DatasetService datasetService;
    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final ExperimentItemPublisher itemPublisher;
    private final IdGenerator idGenerator;
    private final TestSuiteEvaluatorMapper testSuiteEvaluatorMapper;
    private final ExperimentExecutionConfig experimentExecutionConfig;
    private final PromptService promptService;
    private final FeatureFlags featureFlags;
    private final LockService lockService;

    @Inject
    public ExperimentExecutionService(
            @NonNull ExperimentService experimentService,
            @NonNull ExperimentCancellationService cancellationService,
            @NonNull DatasetService datasetService,
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull ExperimentItemPublisher itemPublisher,
            @NonNull IdGenerator idGenerator,
            @NonNull TestSuiteEvaluatorMapper testSuiteEvaluatorMapper,
            @NonNull @Config("experimentExecution") ExperimentExecutionConfig experimentExecutionConfig,
            @NonNull PromptService promptService,
            @NonNull FeatureFlags featureFlags,
            @NonNull LockService lockService) {
        this.experimentService = experimentService;
        this.cancellationService = cancellationService;
        this.datasetService = datasetService;
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.itemPublisher = itemPublisher;
        this.idGenerator = idGenerator;
        this.testSuiteEvaluatorMapper = testSuiteEvaluatorMapper;
        this.experimentExecutionConfig = experimentExecutionConfig;
        this.promptService = promptService;
        this.featureFlags = featureFlags;
        this.lockService = lockService;
    }

    /**
     * Creates experiments and publishes item processing messages to Redis Streams. Answers once the
     * whole run is queued, not straight away: every message is collected first, because the counter
     * each experiment settles against must be set before a consumer reaches the first item. That
     * collection is the ceiling a too-large run is refused against. A retried timeout starts a
     * second run; there is no idempotency key.
     */
    public Mono<ExperimentExecutionResponse> createAndExecute(
            @NonNull ExperimentExecutionRequest request, @NonNull List<DatasetItemFilter> filters) {

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String workspaceName = ctx.getOrDefault(RequestContext.WORKSPACE_NAME, null);

            String projectName = request.projectName() != null
                    ? request.projectName()
                    : experimentExecutionConfig.getDefaultProjectName();

            return Mono.zip(
                    fetchRunSourceReactive(request.datasetId(), request.versionHash()),
                    resolveDataset(request.datasetId()))
                    .flatMap(tuple -> {
                        ExecutionPolicy datasetExecutionPolicy = tuple.getT1().executionPolicy();
                        String versionHash = tuple.getT1().versionHash();
                        var dataset = tuple.getT2();
                        boolean testSuite = dataset.type() == DatasetType.TEST_SUITE;
                        String datasetName = dataset.name();
                        var plannedExperiments = planExperiments(request, projectName, testSuite, datasetName);
                        return resolveOpikPromptsByVariant(request)
                                .flatMap(opikPromptsByVariant -> {
                                    List<UUID> experimentIds = plannedExperiments.stream()
                                            .map(Experiment::id).toList();
                                    List<ExperimentInfo> experimentInfos = IntStream
                                            .range(0, experimentIds.size())
                                            .mapToObj(i -> ExperimentInfo.builder()
                                                    .experimentId(experimentIds.get(i))
                                                    .promptIndex(i)
                                                    .build())
                                            .toList();

                                    UUID batchId = idGenerator.generateId();

                                    return streamDatasetItems(request, datasetName, filters, versionHash)
                                            .flatMapIterable(item -> buildMessages(
                                                    item, request, versionHash, experimentIds,
                                                    datasetExecutionPolicy, projectName, workspaceId,
                                                    workspaceName, userName, batchId,
                                                    opikPromptsByVariant, testSuite))
                                            // One past the cap is already too many
                                            .take(experimentExecutionConfig.getStreamAdmissionLen() + 1L)
                                            .collectList()
                                            .flatMap(messages -> {
                                                // Refused before anything is written, so a run
                                                // we will not accept leaves nothing behind
                                                if (messages.size() > experimentExecutionConfig
                                                        .getStreamAdmissionLen()) {
                                                    return Mono.error(tooLargeToRun());
                                                }

                                                if (messages.isEmpty()) {
                                                    log.warn(
                                                            "No dataset items found for dataset '{}', workspaceId '{}'",
                                                            request.datasetName(), workspaceId);
                                                    return persistExperiments(plannedExperiments)
                                                            .then(markExperimentsCompleted(experimentIds))
                                                            .thenReturn(ExperimentExecutionResponse.builder()
                                                                    .experiments(experimentInfos)
                                                                    .totalItems(0)
                                                                    .build())
                                                            .onErrorResume(error -> abortRun(workspaceId,
                                                                    experimentIds).then(Mono.error(error)));
                                                }

                                                return admitAndPublish(messages, testSuite, batchId,
                                                        plannedExperiments, workspaceId, experimentIds)
                                                        .then(purgeIfCancelledWhilePublishing(
                                                                workspaceId, experimentIds)
                                                                .then(Mono.fromCallable(() -> {
                                                                    log.info(
                                                                            "Created '{}' experiments with '{}' total items for dataset '{}', workspaceId '{}'",
                                                                            experimentIds.size(), messages.size(),
                                                                            request.datasetName(),
                                                                            workspaceId);

                                                                    return ExperimentExecutionResponse.builder()
                                                                            .experiments(withItemCounts(
                                                                                    experimentInfos, messages))
                                                                            .totalItems(messages.size())
                                                                            .build();
                                                                }))
                                                                // What follows the publish, the writes
                                                                // inside the lock having aborted their own.
                                                                .onErrorResume(error -> abortRun(workspaceId,
                                                                        experimentIds)
                                                                        .then(Mono.error(error))));
                                            });
                                });
                    });
        });
    }

    /**
     * Stops a run: the experiments are marked cancelled for the consumer to skip what it has not
     * reached, and their status is set now rather than when the stream drains, so the caller sees
     * the run stop instead of watching it wind down.
     *
     * Items already handed to a provider finish — stopping prevents work starting, not work in
     * flight.
     */
    public Mono<Void> cancel(@NonNull Set<UUID> experimentIds) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            var statusUpdate = ExperimentUpdate.builder()
                    .status(ExperimentStatus.CANCELLED)
                    .build();

            return stillRunning(workspaceId, experimentIds)
                    .flatMap(running -> {
                        if (running.isEmpty()) {
                            log.info("Nothing to cancel, all '{}' experiments had already finished",
                                    experimentIds.size());
                            return Mono.<Void>empty();
                        }

                        // Marked first: the mark is what stops an item a consumer already holds, and
                        // purging before marking would leave that window unguarded
                        var notStopped = new AtomicInteger();

                        return cancellationService.cancel(workspaceId, running)
                                .thenMany(Flux.fromIterable(running)
                                        .concatMap(experimentId -> writeCancelledIfUnclaimed(workspaceId,
                                                experimentId, statusUpdate)
                                                .then(cancellationService.purgeQueued(workspaceId, experimentId))
                                                .flatMap(drained -> recordFinishedIfDrained(experimentId, drained))
                                                .onErrorResume(error -> {
                                                    // Counted, not swallowed: one failing must not
                                                    // strand the others, but nor can the caller be
                                                    // told a run stopped when it did not
                                                    notStopped.incrementAndGet();
                                                    log.error("Failed to cancel experiment '{}', workspaceId '{}'",
                                                            experimentId, workspaceId, error);
                                                    return Mono.empty();
                                                })))
                                .then(Mono.defer(() -> {
                                    if (notStopped.get() > 0) {
                                        return Mono.error(stopDidNotApply(notStopped.get(), running.size()));
                                    }
                                    log.info("Cancelled '{}' experiments, workspaceId '{}'",
                                            running.size(), workspaceId);
                                    return Mono.<Void>empty();
                                }));
                    });
        });
    }

    /**
     * What each variant dispatched, counted from the messages themselves rather than divided out of
     * the batch total.
     */
    private static List<ExperimentInfo> withItemCounts(
            List<ExperimentInfo> experimentInfos,
            List<ExperimentItemToProcess> messages) {

        var countsByExperiment = messages.stream()
                .collect(Collectors.groupingBy(ExperimentItemToProcess::experimentId, Collectors.counting()));

        return experimentInfos.stream()
                .map(info -> info.toBuilder()
                        .totalItems(countsByExperiment.getOrDefault(info.experimentId(), 0L).intValue())
                        .build())
                .toList();
    }

    private List<Experiment> planExperiments(ExperimentExecutionRequest request, String projectName,
            boolean testSuite, String datasetName) {
        return IntStream.range(0, request.prompts().size())
                .mapToObj(i -> {
                    var prompt = request.prompts().get(i);
                    UUID experimentId = idGenerator.generateId();

                    ObjectNode metadata = JsonUtils.createObjectNode();
                    metadata.put("model", prompt.model());
                    metadata.set("messages", JsonUtils.getMapper().valueToTree(prompt.messages()));
                    if (prompt.configs() != null) {
                        metadata.set("model_config", JsonUtils.getMapper().valueToTree(prompt.configs()));
                    }

                    var experiment = Experiment.builder()
                            .id(experimentId)
                            .name(prompt.experimentName())
                            .datasetName(datasetName)
                            .datasetVersionId(request.datasetVersionId())
                            .projectId(request.projectId())
                            .projectName(projectName)
                            .metadata(metadata)
                            .evaluationMethod(testSuite ? EvaluationMethod.TEST_SUITE : EvaluationMethod.DATASET)
                            .status(ExperimentStatus.RUNNING)
                            .promptVersions(
                                    prompt.promptVersions() != null
                                            ? prompt.promptVersions()
                                            : request.promptVersions())
                            .build();

                    return experiment;
                })
                .toList();
    }

    private Mono<Void> persistExperiments(List<Experiment> experiments) {
        return Flux.fromIterable(experiments)
                .concatMap(experimentService::create)
                .then();
    }

    /**
     * Pages through every matching item. A single call caps at {@link DatasetItemStreamRequest}'s maximum, so
     * asking for the dataset in one go would silently run only the first {@value #STREAM_PAGE_SIZE} items —
     * the whole point of running server-side is that a large dataset completes.
     * <p>
     * Both the versioned and the legacy query order by item id descending and take {@code id < lastRetrievedId},
     * so the last item of a page is the cursor for the next one. A short page means the dataset is exhausted.
     */
    private Flux<DatasetItem> streamDatasetItems(ExperimentExecutionRequest request, String datasetName,
            List<DatasetItemFilter> filters, String versionHash) {
        return fetchItemPage(request, datasetName, filters, versionHash, null)
                .expand(page -> page.size() < STREAM_PAGE_SIZE
                        ? Mono.empty()
                        : fetchItemPage(request, datasetName, filters, versionHash, page.getLast().id()))
                .flatMapIterable(page -> page);
    }

    private Mono<List<DatasetItem>> fetchItemPage(ExperimentExecutionRequest request, String datasetName,
            List<DatasetItemFilter> filters, String versionHash, UUID lastRetrievedId) {
        var streamRequest = DatasetItemStreamRequest.builder()
                .datasetName(datasetName)
                .datasetVersion(versionHash)
                .steamLimit(STREAM_PAGE_SIZE)
                .lastRetrievedId(lastRetrievedId)
                .build();
        return datasetItemService.getItems(streamRequest, filters).collectList();
    }

    /**
     * The stored dataset, which the run's type and name are both taken from rather than the request. Its
     * whole downstream shape hangs off the type — assertion counters, how the subscriber decides an
     * experiment is finished, the metadata the assertion sampler keys on — so it must not be something a
     * caller can claim; and taking the name from the same row is what stops a request pairing one
     * dataset's id with another's name from running its items under the other's semantics.
     */
    private Mono<Dataset> resolveDataset(UUID datasetId) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            Visibility visibility = ctx.get(RequestContext.VISIBILITY);
            return Mono
                    .fromCallable(() -> datasetService.findById(datasetId, workspaceId, visibility))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * The version a run reads its items from, and the policy that version carries. Both come from one
     * read: a request that pins no version is answered with the latest, rather than taking the policy
     * from the latest and then letting the items page from whatever the dataset holds at the time -
     * which lets the two disagree, and lets the items change between pages. A dataset with no version
     * at all is answered with neither, and streams live as before.
     */
    private record RunSource(ExecutionPolicy executionPolicy, String versionHash) {
    }

    private Mono<RunSource> fetchRunSourceReactive(UUID datasetId, String versionHash) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return Mono.fromCallable(() -> fetchRunSource(datasetId, versionHash, workspaceId))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    /**
     * Failures are not caught: the policy decides how many times each item runs and what counts as a
     * pass, so defaulting on a read that failed runs a suite configured for three runs once and
     * reports its pass rate against thresholds nobody chose. Null is still a valid answer — it means
     * the version carries no policy — but it has to come from the version, not from an error.
     */
    private RunSource fetchRunSource(UUID datasetId, String versionHash, String workspaceId) {
        if (versionHash != null) {
            var versionId = datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash);
            var version = datasetVersionService.getVersionById(workspaceId, datasetId, versionId);
            return new RunSource(version.executionPolicy(), versionHash);
        }
        return datasetVersionService.getLatestVersion(datasetId, workspaceId)
                .map(version -> new RunSource(version.executionPolicy(),
                        featureFlags.isDatasetVersioningEnabled() ? version.versionHash() : null))
                .orElseGet(() -> new RunSource(null, null));
    }

    /**
     * The ones a stop can still affect. An experiment that has already finished must keep the outcome
     * it earned: a prompt that completes before its siblings keeps its Stop button until the whole run
     * settles, so cancelling what is already done is a click away and would relabel it.
     *
     * A status read only rules out what had already finished when it was taken, which is why this is a
     * filter and not the decision. What stops a stop from overwriting an ending written in the moment
     * between the two is the claim, taken in {@link #writeCancelledIfUnclaimed}.
     */
    private Mono<Set<UUID>> stillRunning(String workspaceId, Set<UUID> experimentIds) {
        return Flux.fromIterable(experimentIds)
                .filterWhen(experimentId -> itemPublisher.isTracked(workspaceId, experimentId)
                        .doOnNext(tracked -> {
                            if (!tracked) {
                                log.info("Experiment '{}' is not a run this service queued, leaving it alone",
                                        experimentId);
                            }
                        }))
                .filterWhen(experimentId -> experimentService.getMetadataById(experimentId)
                        .map(experiment -> experiment.status() == null || !experiment.status().isTerminal())
                        .onErrorResume(error -> {
                            log.warn("Could not read experiment '{}' before cancelling, leaving it alone",
                                    experimentId, error);
                            return Mono.just(false);
                        }))
                .collect(Collectors.toSet());
    }

    /**
     * Writes CANCELLED only if nothing has written how this run ended. The stop and the consumer
     * draining its last item can reach that point together, and the claim is what settles which of
     * them owns the outcome — rather than each reading the other's state and both writing.
     *
     * Losing it is not a failure: the run ended as it ended, and the stop still purges what is queued.
     */
    private Mono<Void> writeCancelledIfUnclaimed(String workspaceId, UUID experimentId,
            ExperimentUpdate statusUpdate) {
        return cancellationService.claimFinish(workspaceId, experimentId)
                .flatMap(won -> {
                    if (!won) {
                        log.info("Experiment '{}' finished as the stop arrived, leaving its outcome alone",
                                experimentId);
                        return Mono.<Void>empty();
                    }
                    return experimentService.update(experimentId, statusUpdate)
                            // Given back when nothing could be written: held by a write that failed, it
                            // answers "already finished" to the retry this error asks for, and the run
                            // keeps a status no one will ever correct.
                            .onErrorResume(error -> cancellationService.releaseFinish(workspaceId, experimentId)
                                    .onErrorResume(releaseError -> {
                                        log.error("Failed to release the finish of experiment '{}'",
                                                experimentId, releaseError);
                                        return Mono.just(false);
                                    })
                                    .then(Mono.error(error)));
                });
    }

    /**
     * A stop that lands while this run is still publishing purges an empty queue: the messages it
     * meant to delete are not on the stream yet, and a cancel only purges once. Repeating it here,
     * now that they are, is what keeps those messages from being walked one by one.
     *
     * The run is cancelled either way — the mark is set before the purge, so a consumer skips
     * whatever this misses. What is saved is the walk, which on a large dataset is the whole queue.
     */
    private Mono<Void> purgeIfCancelledWhilePublishing(String workspaceId, List<UUID> experimentIds) {
        return Flux.fromIterable(experimentIds)
                .filterWhen(experimentId -> cancellationService.isCancelled(workspaceId, experimentId))
                .concatMap(experimentId -> cancellationService.purgeQueued(workspaceId, experimentId)
                        .flatMap(drained -> recordFinishedIfDrained(experimentId, drained))
                        .onErrorResume(error -> {
                            // Only the walk is lost, and the run still settles through its consumer
                            log.warn("Failed to purge cancelled experiment '{}' after publishing, workspaceId '{}'",
                                    experimentId, workspaceId, error);
                            return Mono.empty();
                        }))
                .then();
    }

    /**
     * A run whose remaining items were all purged has stopped producing with nothing left to notice
     * it: no message will reach a consumer to count the last one down. Anything above zero is still
     * with a consumer, which will record it on the way out. Unclaimed, because the stamp is not the outcome.
     */
    private Mono<Void> recordFinishedIfDrained(UUID experimentId, boolean drained) {
        if (!drained) {
            return Mono.empty();
        }

        return experimentService.update(experimentId, ExperimentUpdate.builder().finished(true).build());
    }

    /**
     * Ends a launch that failed after its records were written. A half-started run can still be
     * half-queued, hence three steps: the mark stops consumers working on whatever did get
     * published, the claim stops a late item reporting the run complete over the failure, and the
     * counters go with it.
     */
    private Mono<Void> abortRun(String workspaceId, List<UUID> experimentIds) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.FAILED)
                .finished(true)
                .build();

        return cancellationService.cancel(workspaceId, experimentIds)
                .thenMany(Flux.fromIterable(experimentIds)
                        .concatMap(id -> cancellationService.claimFinish(workspaceId, id)
                                .flatMap(won -> won
                                        ? experimentService.update(id, statusUpdate)
                                                .retryWhen(RetryUtils.handleConnectionError())
                                        : Mono.<Void>empty())
                                .onErrorResume(error -> {
                                    // One record failing must not strand the others. The claim is
                                    // kept rather than handed back: the counters are discarded just
                                    // below, and a counter that is gone reads as a run that drained,
                                    // so releasing it invites a consumer to record COMPLETED.
                                    log.error("Failed to mark aborted experiment '{}' as failed, leaving the"
                                            + " finish claimed so nothing records it as completed", id, error);
                                    return Mono.empty();
                                })))
                .then(itemPublisher.discardCounters(workspaceId, experimentIds));
    }

    /**
     * The stop itself did not land, not that the run had failures: the mark is set, so items are
     * already being skipped under a status that still reads as running.
     */
    private InternalServerErrorException stopDidNotApply(int notStopped, int total) {
        log.error("Could not stop '{}' of '{}' experiments", notStopped, total);
        return new InternalServerErrorException(
                "Could not stop %d of %d experiments. Their items are no longer being processed, but their status could not be updated — try again."
                        .formatted(notStopped, total));
    }

    /**
     * Serialised cluster-wide: the capacity check is a read, and launches that read the same depth
     * would all be admitted, leaving the trim to take whatever no consumer has claimed. The writes
     * are inside the lock so a refused run still leaves nothing behind.
     */
    private Mono<Void> admitAndPublish(List<ExperimentItemToProcess> messages, boolean testSuite, UUID batchId,
            List<Experiment> plannedExperiments, String workspaceId, List<UUID> experimentIds) {
        return lockService.bestEffortLock(
                new LockService.Lock(PUBLISH_LOCK),
                refuseIfQueueIsFull(messages.size())
                        // Scoped to the writes: a refused run has none to undo, and aborting it
                        // would mark experiments failed that were never created.
                        .then(itemPublisher.startTrackingRun(messages, testSuite)
                                .then(persistExperiments(plannedExperiments))
                                .then(itemPublisher.publish(batchId, messages))
                                .then(Mono.just(true))
                                .onErrorResume(error -> abortRun(workspaceId, experimentIds)
                                        .then(Mono.error(error)))),
                // Refused rather than queued: the endpoint holds a request thread while it waits,
                // and those are shared with every other call.
                Mono.error(anotherRunIsQueueing()),
                experimentExecutionConfig.getPublishLockLease().toJavaDuration(),
                experimentExecutionConfig.getPublishLockWait().toJavaDuration())
                .then();
    }

    private ClientErrorException anotherRunIsQueueing() {
        log.warn("Refusing a run: another is being queued, waited '{}'",
                experimentExecutionConfig.getPublishLockWait());
        return new ClientErrorException(
                "Another run is being queued right now. Try again in a moment.",
                Response.Status.TOO_MANY_REQUESTS);
    }

    /**
     * The bound is on the stream, not on one run. It is shared by every workspace and trimmed by
     * length, so a run that fits on its own still pushes the backlog past the bound when others are
     * in flight — and the trim takes the oldest entries whether or not anyone has them yet, which
     * silently deletes another tenant's work and leaves their run unable to finish.
     *
     * Checked, not reserved: two launches can still pass together, which is why this admits against
     * a lower bound than the stream is trimmed to. Overshooting the admission bound costs nothing;
     * reaching the trim length destroys whatever no consumer has taken yet. The gap is what the
     * race is allowed to spend until runs stop sharing a queue at all.
     */
    private Mono<Void> refuseIfQueueIsFull(int runSize) {
        return itemPublisher.queuedMessageCount()
                .flatMap(queued -> queued + runSize > experimentExecutionConfig.getStreamAdmissionLen()
                        ? Mono.<Void>error(queueTooBusy(queued, runSize))
                        : Mono.<Void>empty());
    }

    private ClientErrorException queueTooBusy(long queued, int runSize) {
        log.warn("Refusing a run of '{}' items: '{}' already queued against an admission bound of '{}'",
                runSize, queued, experimentExecutionConfig.getStreamAdmissionLen());
        return new ClientErrorException(
                "The processing queue is too busy to accept this run right now. Try again in a few minutes.",
                Response.Status.TOO_MANY_REQUESTS);
    }

    /**
     * Refused rather than published: the queue trims by length without sparing what no consumer has
     * taken, so a run this size would delete its own items. They would never execute and the run
     * would never finish, with nothing to say why.
     * The exact size is not reported: the run is refused as soon as one message past the bound exists.
     */
    private BadRequestException tooLargeToRun() {
        log.warn("Refusing a run of more than '{}' items, the bound a run is admitted against",
                experimentExecutionConfig.getStreamAdmissionLen());

        return new BadRequestException(
                String.format(Locale.ROOT,
                        "This run would queue more than the %,d items the processing queue accepts. Narrow the dataset with filters, or run fewer prompts at once.",
                        experimentExecutionConfig.getStreamAdmissionLen()));
    }

    private Mono<Void> markExperimentsCompleted(List<UUID> experimentIds) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .finished(true)
                .build();
        return Flux.fromIterable(experimentIds)
                .concatMap(id -> experimentService.update(id, statusUpdate))
                .then(experimentService.finishExperiments(Set.copyOf(experimentIds)))
                .then();
    }

    private int getEffectiveRunsPerItem(ExecutionPolicy itemPolicy, ExecutionPolicy versionPolicy) {
        return testSuiteEvaluatorMapper.getEffectiveRunsPerItem(itemPolicy, versionPolicy);
    }

    private List<ExperimentItemToProcess> buildMessages(
            DatasetItem item,
            ExperimentExecutionRequest request,
            String versionHash,
            List<UUID> experimentIds,
            ExecutionPolicy datasetExecutionPolicy,
            String projectName,
            String workspaceId,
            String workspaceName,
            String userName,
            UUID batchId,
            List<List<OpikPromptEntry>> opikPromptsByVariant,
            boolean testSuite) {

        int runsPerItem = getEffectiveRunsPerItem(item.executionPolicy(), datasetExecutionPolicy);
        var messages = new ArrayList<ExperimentItemToProcess>();

        for (int run = 0; run < runsPerItem; run++) {
            for (int promptIdx = 0; promptIdx < request.prompts().size(); promptIdx++) {
                var prompt = request.prompts().get(promptIdx);
                UUID experimentId = experimentIds.get(promptIdx);
                List<OpikPromptEntry> opikPrompts = opikPromptsByVariant.get(promptIdx);

                messages.add(ExperimentItemToProcess.builder()
                        .batchId(batchId)
                        .prompt(prompt)
                        .datasetItemId(item.id())
                        .experimentId(experimentId)
                        .datasetId(request.datasetId())
                        .versionHash(versionHash)
                        .projectName(projectName)
                        .workspaceId(workspaceId)
                        .workspaceName(workspaceName)
                        .userName(userName)
                        .allExperimentIds(experimentIds)
                        .opikPrompts(opikPrompts)
                        .testSuite(testSuite)
                        .selectedRuleIds(request.selectedRuleIds())
                        .build());
            }
        }

        return messages;
    }

    /**
     * Resolves the prompt versions linked to each variant via a single bulk lookup against
     * the prompt store, and returns one prebuilt {@code opik_prompts} list per variant (in
     * the order of {@code request.prompts()}). This avoids re-doing the lookup for every
     * dataset item — large experiments would otherwise hit the prompt store thousands of
     * times for the same set of version ids.
     */
    private Mono<List<List<OpikPromptEntry>>> resolveOpikPromptsByVariant(ExperimentExecutionRequest request) {
        List<List<Experiment.PromptVersionLink>> linksByVariant = request.prompts().stream()
                .map(variant -> variant.promptVersions() != null
                        ? variant.promptVersions()
                        : request.promptVersions())
                .map(links -> links == null ? List.<Experiment.PromptVersionLink>of() : links)
                .toList();

        Set<UUID> uniqueVersionIds = linksByVariant.stream()
                .flatMap(List::stream)
                .map(Experiment.PromptVersionLink::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        if (uniqueVersionIds.isEmpty()) {
            return Mono.just(linksByVariant.stream()
                    .map(unused -> List.<OpikPromptEntry>of())
                    .toList());
        }

        // A name-lookup failure should not wipe out the version-based entries — entries with
        // null name are still useful for trace linkage. Degrade locally to an empty info map.
        Mono<Map<UUID, PromptVersionInfo>> infoLookup = promptService.getVersionsInfoByVersionsIds(uniqueVersionIds)
                .onErrorResume(error -> {
                    log.warn(
                            "Failed to resolve prompt names for opik_prompts metadata; entries will be populated without names for dataset '{}' (id '{}', versionHash '{}')",
                            request.datasetName(), request.datasetId(), request.versionHash(), error);
                    return Mono.just(Map.of());
                });

        return Mono.zip(promptService.findVersionByIds(uniqueVersionIds), infoLookup)
                .map(tuple -> {
                    Map<UUID, PromptVersion> versionsById = tuple.getT1();
                    Map<UUID, PromptVersionInfo> infoById = tuple.getT2();
                    return linksByVariant.stream()
                            .map(links -> buildOpikPromptEntries(links, versionsById, infoById))
                            .toList();
                })
                .onErrorResume(error -> {
                    log.warn(
                            "Failed to resolve prompt versions for opik_prompts metadata; traces will not be linked to their prompt(s) for dataset '{}' (id '{}', versionHash '{}')",
                            request.datasetName(), request.datasetId(), request.versionHash(), error);
                    return Mono.just(linksByVariant.stream()
                            .map(unused -> List.<OpikPromptEntry>of())
                            .toList());
                });
    }

    private static List<OpikPromptEntry> buildOpikPromptEntries(List<Experiment.PromptVersionLink> links,
            Map<UUID, PromptVersion> versionsById, Map<UUID, PromptVersionInfo> infoById) {
        if (links == null || links.isEmpty()) {
            return List.of();
        }
        return links.stream()
                .map(link -> toOpikPromptEntry(link, versionsById.get(link.id()), infoById.get(link.id())))
                .filter(Objects::nonNull)
                .toList();
    }

    private static OpikPromptEntry toOpikPromptEntry(Experiment.PromptVersionLink link, PromptVersion version,
            PromptVersionInfo info) {
        if (version == null) {
            return null;
        }
        UUID promptId = version.promptId() != null ? version.promptId() : link.promptId();
        String name = link.promptName() != null ? link.promptName() : (info != null ? info.promptName() : null);
        return OpikPromptEntry.builder()
                .id(promptId)
                .name(name)
                .templateStructure(version.templateStructure())
                .version(OpikPromptEntry.Version.builder()
                        .id(version.id())
                        .template(toTemplateNode(version.template(), version.templateStructure()))
                        .commit(version.commit())
                        .versionNumber(version.versionNumber())
                        .metadata(version.metadata())
                        .build())
                .build();
    }

    private static JsonNode toTemplateNode(String template, TemplateStructure structure) {
        if (template == null) {
            return null;
        }
        if (structure == TemplateStructure.CHAT) {
            return JsonUtils.getJsonNodeFromStringWithFallback(template);
        }
        return TextNode.valueOf(template);
    }
}
