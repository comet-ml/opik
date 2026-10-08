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
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Singleton
@Slf4j
public class ExperimentExecutionService {

    private static final int STREAM_PAGE_SIZE = 2000;

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
            @NonNull PromptService promptService) {
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
    }

    /**
     * Creates experiments and publishes item processing messages to Redis Streams.
     * Returns immediately with experiment IDs so the caller can start polling.
     * Dataset items are read a page at a time, so a large dataset never lands in memory whole.
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
                    fetchDatasetExecutionPolicyReactive(request.datasetId(), request.versionHash()),
                    resolveDataset(request.datasetId()))
                    .flatMap(tuple -> {
                        ExecutionPolicy datasetExecutionPolicy = tuple.getT1().orElse(null);
                        var dataset = tuple.getT2();
                        boolean testSuite = dataset.type() == DatasetType.TEST_SUITE;
                        String datasetName = dataset.name();
                        return createExperiments(request, projectName, testSuite, datasetName)
                                .collectSortedList(Comparator.comparingInt(e -> e.info().promptIndex()))
                                .flatMap(experimentEntries -> resolveOpikPromptsByVariant(request)
                                        .flatMap(opikPromptsByVariant -> {
                                            List<UUID> experimentIds = experimentEntries.stream()
                                                    .map(ExperimentEntry::experimentId).toList();
                                            List<ExperimentExecutionResponse.ExperimentInfo> experimentInfos = experimentEntries
                                                    .stream()
                                                    .map(ExperimentEntry::info).toList();

                                            UUID batchId = idGenerator.generateId();

                                            return streamDatasetItems(request, datasetName, filters)
                                                    .flatMapIterable(item -> buildMessages(
                                                            item, request, experimentIds, datasetExecutionPolicy,
                                                            projectName, workspaceId, workspaceName, userName, batchId,
                                                            opikPromptsByVariant, testSuite))
                                                    // One past the cap is already too many
                                                    .take(experimentExecutionConfig.getStreamMaxLen() + 1L)
                                                    .collectList()
                                                    .flatMap(messages -> {
                                                        if (messages.isEmpty()) {
                                                            log.warn(
                                                                    "No dataset items found for dataset '{}', workspaceId '{}'",
                                                                    request.datasetName(), workspaceId);
                                                            return markExperimentsCompleted(experimentIds)
                                                                    .thenReturn(ExperimentExecutionResponse.builder()
                                                                            .experiments(experimentInfos)
                                                                            .totalItems(0)
                                                                            .build());
                                                        }

                                                        if (messages.size() > experimentExecutionConfig
                                                                .getStreamMaxLen()) {
                                                            return markExperimentsFailed(experimentIds)
                                                                    .then(Mono.error(tooLargeToRun()));
                                                        }

                                                        return itemPublisher.publish(batchId, messages, testSuite)
                                                                .then(Mono.fromCallable(() -> {
                                                                    log.info(
                                                                            "Created '{}' experiments with '{}' total items for dataset '{}', workspaceId '{}'",
                                                                            experimentIds.size(), messages.size(),
                                                                            request.datasetName(),
                                                                            workspaceId);

                                                                    return ExperimentExecutionResponse.builder()
                                                                            .experiments(experimentInfos)
                                                                            .totalItems(messages.size())
                                                                            .build();
                                                                }));
                                                    });
                                        }));
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
                        return cancellationService.cancel(workspaceId, running)
                                .thenMany(Flux.fromIterable(running)
                                        .concatMap(experimentId -> experimentService
                                                .update(experimentId, statusUpdate)
                                                .then(cancellationService.purgeQueued(workspaceId, experimentId))
                                                .flatMap(drained -> recordFinishedIfDrained(workspaceId, experimentId,
                                                        drained))
                                                .onErrorResume(error -> {
                                                    log.error("Failed to cancel experiment '{}', workspaceId '{}'",
                                                            experimentId, workspaceId, error);
                                                    return Mono.empty();
                                                })))
                                .then()
                                .doOnSuccess(unused -> log.info("Cancelled '{}' experiments, workspaceId '{}'",
                                        running.size(), workspaceId));
                    });
        });
    }

    private record ExperimentEntry(UUID experimentId, ExperimentExecutionResponse.ExperimentInfo info) {
    }

    private Flux<ExperimentEntry> createExperiments(ExperimentExecutionRequest request, String projectName,
            boolean testSuite, String datasetName) {
        var monos = IntStream.range(0, request.prompts().size())
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
                            .projectName(projectName)
                            .metadata(metadata)
                            .evaluationMethod(testSuite ? EvaluationMethod.TEST_SUITE : EvaluationMethod.DATASET)
                            .status(ExperimentStatus.RUNNING)
                            .promptVersions(
                                    prompt.promptVersions() != null
                                            ? prompt.promptVersions()
                                            : request.promptVersions())
                            .build();

                    return experimentService.create(experiment)
                            .map(id -> new ExperimentEntry(experimentId,
                                    ExperimentExecutionResponse.ExperimentInfo.builder()
                                            .experimentId(experimentId)
                                            .promptIndex(i)
                                            .build()));
                })
                .toList();
        return Flux.merge(monos);
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
            List<DatasetItemFilter> filters) {
        return fetchItemPage(request, datasetName, filters, null)
                .expand(page -> page.size() < STREAM_PAGE_SIZE
                        ? Mono.empty()
                        : fetchItemPage(request, datasetName, filters, page.getLast().id()))
                .flatMapIterable(page -> page);
    }

    private Mono<List<DatasetItem>> fetchItemPage(ExperimentExecutionRequest request, String datasetName,
            List<DatasetItemFilter> filters, UUID lastRetrievedId) {
        var streamRequest = DatasetItemStreamRequest.builder()
                .datasetName(datasetName)
                .datasetVersion(request.versionHash())
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

    private Mono<Optional<ExecutionPolicy>> fetchDatasetExecutionPolicyReactive(UUID datasetId, String versionHash) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return Mono.fromCallable(
                    () -> Optional.ofNullable(fetchDatasetExecutionPolicy(datasetId, versionHash, workspaceId)))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private ExecutionPolicy fetchDatasetExecutionPolicy(UUID datasetId, String versionHash, String workspaceId) {
        try {
            if (versionHash != null) {
                var versionId = datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash);
                var version = datasetVersionService.getVersionById(workspaceId, datasetId, versionId);
                return version.executionPolicy();
            }
            return datasetVersionService.getLatestVersion(datasetId, workspaceId)
                    .map(v -> v.executionPolicy())
                    .orElse(null);
        } catch (NotFoundException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Failed to fetch dataset execution policy for dataset '{}', versionHash '{}'",
                    datasetId, versionHash, e);
            return null;
        }
    }

    /**
     * The ones a stop can still affect. An experiment that has already finished must keep the outcome
     * it earned: a prompt that completes before its siblings keeps its Stop button until the whole run
     * settles, so cancelling what is already done is a click away and would relabel it. The claim is
     * asked before the status, being taken the moment a consumer's last item drains — before the
     * status it is about to write can be read back.
     */
    private Mono<Set<UUID>> stillRunning(String workspaceId, Set<UUID> experimentIds) {
        return Flux.fromIterable(experimentIds)
                .filterWhen(experimentId -> cancellationService
                        .isFinishClaimed(workspaceId, experimentId)
                        .map(claimed -> !claimed))
                .filterWhen(experimentId -> experimentService.getMetadataById(experimentId)
                        .map(experiment -> experiment.status() == null || !experiment.status().isTerminal())
                        .onErrorResume(error -> {
                            log.warn("Could not read experiment '{}' before cancelling, cancelling anyway",
                                    experimentId, error);
                            return Mono.just(true);
                        }))
                .collect(Collectors.toSet());
    }

    /**
     * A run whose remaining items were all purged has stopped producing with nothing left to notice
     * it: no message will reach a consumer to count the last one down. Anything above zero is still
     * with a consumer, which will record it on the way out.
     */
    private Mono<Void> recordFinishedIfDrained(String workspaceId, UUID experimentId, boolean drained) {
        if (!drained) {
            return Mono.empty();
        }

        return cancellationService.claimFinish(workspaceId, experimentId)
                .filter(Boolean::booleanValue)
                .flatMap(claimed -> experimentService.update(experimentId,
                        ExperimentUpdate.builder().finished(true).build()))
                .then();
    }

    /**
     * The experiment records exist before their items are counted, so a run refused at that point
     * would otherwise be left behind reading as still running, for work that will never start.
     */
    private Mono<Void> markExperimentsFailed(List<UUID> experimentIds) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.FAILED)
                .finished(true)
                .build();
        return Flux.fromIterable(experimentIds)
                .concatMap(id -> experimentService.update(id, statusUpdate)
                        .onErrorResume(error -> {
                            log.error("Failed to mark refused experiment '{}' as failed", id, error);
                            return Mono.empty();
                        }))
                .then();
    }

    /**
     * Refused rather than published: the queue trims by length without sparing what no consumer has
     * taken, so a run this size would delete its own items. They would never execute and the run
     * would never finish, with nothing to say why.
     * The exact size is not reported: the run is refused as soon as one message past the bound exists.
     */
    private BadRequestException tooLargeToRun() {
        log.warn("Refusing a run of more than '{}' items, the bound of the processing queue",
                experimentExecutionConfig.getStreamMaxLen());

        return new BadRequestException(
                "This run would queue more than the %,d items the processing queue holds. Narrow the dataset with filters, or run fewer prompts at once."
                        .formatted(experimentExecutionConfig.getStreamMaxLen()));
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
                        .versionHash(request.versionHash())
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
