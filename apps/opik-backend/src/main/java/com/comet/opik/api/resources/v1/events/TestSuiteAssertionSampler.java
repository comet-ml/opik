package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.ProviderApiKey;
import com.comet.opik.api.TestSuiteMetadataKeys;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.resources.v1.events.TestSuiteEvaluatorMapper.PreparedEvaluator;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.TestSuiteConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.context.Context;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Listens for TracesCreated events and checks if traces contain test suite metadata.
 * When found, reads evaluators from the dataset item (per-item evaluators) and/or
 * dataset version (default evaluators), then enqueues them for LLM-as-judge evaluation
 * via the same Redis pipeline used by online scoring.
 *
 * Evaluator resolution:
 * - Dataset version evaluators apply to all items
 * - Each dataset item can have additional evaluators
 * - Both are concatenated for the final evaluator set
 */
@EagerSingleton
@Slf4j
public class TestSuiteAssertionSampler {

    public static final String SUITE_ASSERTION_CATEGORY = "suite_assertion";

    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final OnlineScorePublisher onlineScorePublisher;
    private final IdGenerator idGenerator;
    private final TestSuiteConfig testSuiteConfig;
    private final TestSuiteEvaluatorMapper evaluatorMapper;
    private final LlmProviderApiKeyService llmProviderApiKeyService;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;

    @Inject
    public TestSuiteAssertionSampler(
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull OnlineScorePublisher onlineScorePublisher,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("testSuite") TestSuiteConfig testSuiteConfig,
            @NonNull TestSuiteEvaluatorMapper evaluatorMapper,
            @NonNull LlmProviderApiKeyService llmProviderApiKeyService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService) {
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.idGenerator = idGenerator;
        this.testSuiteConfig = testSuiteConfig;
        this.evaluatorMapper = evaluatorMapper;
        this.llmProviderApiKeyService = llmProviderApiKeyService;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
    }

    // TracesCreated fires only on insert; updates (e.g. endTime null→set) fire TracesUpdated,
    // so a given trace ID appears here exactly once — safe to partition and decrement without double-counting.
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        var completeTraces = tracesBatch.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .toList();

        var reactiveContext = Context.of(
                RequestContext.WORKSPACE_ID, tracesBatch.workspaceId(),
                RequestContext.USER_NAME, tracesBatch.userName(),
                RequestContext.VISIBILITY, Visibility.PRIVATE);

        Duration fetchTimeout = Duration.ofSeconds(testSuiteConfig.getFetchTimeoutSeconds());

        var decrementIncomplete = Flux.fromIterable(tracesBatch.traces())
                .filter(trace -> trace.endTime() == null)
                .flatMap(trace -> decrementAssertionCounterForTrace(trace,
                        tracesBatch.workspaceId()))
                .then();

        if (completeTraces.isEmpty()) {
            decrementIncomplete
                    .contextWrite(reactiveContext)
                    .block();
            return;
        }

        var connectedProviders = getConnectedProviders(tracesBatch.workspaceId());
        String modelName = SupportedJudgeProvider.resolveModel(connectedProviders)
                .or(() -> getMetadataString(completeTraces.getFirst(), TestSuiteMetadataKeys.MODEL))
                .orElse(null);

        if (modelName == null) {
            log.warn("No LLM model resolved for test suite batch in workspace '{}' — "
                    + "no supported provider connected and no test_suite_model in trace metadata",
                    tracesBatch.workspaceId());
            decrementIncomplete
                    .then(Flux.fromIterable(completeTraces)
                            .flatMap(trace -> decrementAssertionCounterForTrace(trace,
                                    tracesBatch.workspaceId()))
                            .then())
                    .contextWrite(reactiveContext)
                    .block();
            return;
        }

        Map<String, Mono<List<PreparedEvaluator>>> datasetEvaluatorsCache = new HashMap<>();

        decrementIncomplete
                .thenMany(Flux.fromIterable(completeTraces)
                        .concatMap(trace -> processTrace(trace, tracesBatch,
                                datasetEvaluatorsCache, modelName, fetchTimeout)))
                .flatMapIterable(list -> list)
                .collectList()
                .doOnNext(messages -> {
                    if (!messages.isEmpty()) {
                        log.info("Enqueuing '{}' test suite assertion messages", messages.size());
                        onlineScorePublisher.enqueueMessage(messages,
                                AutomationRuleEvaluatorType.LLM_AS_JUDGE);
                    }
                })
                .contextWrite(reactiveContext)
                .block();
    }

    private Mono<List<TraceToScoreLlmAsJudge>> processTrace(
            Trace trace, TracesCreated tracesBatch,
            Map<String, Mono<List<PreparedEvaluator>>> datasetEvaluatorsCache,
            String modelName, Duration fetchTimeout) {

        var experimentId = getMetadataString(trace, TestSuiteMetadataKeys.EXPERIMENT_ID)
                .flatMap(id -> parseUUID(id, trace.id()))
                .orElse(null);

        var testSuiteDatasetId = getMetadataString(trace, TestSuiteMetadataKeys.DATASET_ID);
        if (testSuiteDatasetId.isEmpty()) {
            return decrementAssertionCounter(experimentId, tracesBatch.workspaceId())
                    .then(Mono.empty());
        }

        UUID datasetId;
        try {
            datasetId = UUID.fromString(testSuiteDatasetId.get());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid test_suite_dataset_id '{}' in trace metadata",
                    testSuiteDatasetId.get());
            return decrementAssertionCounter(experimentId, tracesBatch.workspaceId())
                    .then(Mono.empty());
        }

        var versionHash = getMetadataString(trace, TestSuiteMetadataKeys.DATASET_VERSION_HASH)
                .orElse(null);

        var cacheKey = datasetEvaluatorsCacheKey(datasetId, versionHash);
        var datasetEvaluatorsMono = datasetEvaluatorsCache.computeIfAbsent(cacheKey, k -> {
            log.debug("Fetching test suite evaluators for dataset '{}', version hash '{}'",
                    datasetId, versionHash != null ? versionHash : "latest");
            return fetchDatasetEvaluators(datasetId, versionHash)
                    .timeout(fetchTimeout)
                    .map(result -> evaluatorMapper.prepareEvaluators(result.evaluators(), modelName))
                    .cache();
        });

        var datasetItemId = getMetadataString(trace, TestSuiteMetadataKeys.DATASET_ITEM_ID);
        if (datasetItemId.isEmpty()) {
            log.debug("Skipping trace '{}' — no test_suite_dataset_item_id in metadata",
                    trace.id());
            return decrementAssertionCounter(experimentId, tracesBatch.workspaceId())
                    .then(Mono.empty());
        }

        var itemIdOpt = parseUUID(datasetItemId.get(), trace.id());
        if (itemIdOpt.isEmpty()) {
            return decrementAssertionCounter(experimentId, tracesBatch.workspaceId())
                    .then(Mono.empty());
        }
        var itemId = itemIdOpt.get();

        return datasetEvaluatorsMono
                .flatMap(datasetEvals -> fetchItemEvaluators(itemId, modelName)
                        .map(itemEvals -> {
                            var allEvaluators = new ArrayList<PreparedEvaluator>(datasetEvals);
                            allEvaluators.addAll(itemEvals);
                            return allEvaluators;
                        }))
                .flatMap(allEvaluators -> {
                    if (allEvaluators.isEmpty()) {
                        log.debug("No evaluators found for trace '{}', dataset item '{}'",
                                trace.id(), datasetItemId.get());
                        return decrementAssertionCounter(experimentId,
                                tracesBatch.workspaceId())
                                .then(Mono.<List<TraceToScoreLlmAsJudge>>empty());
                    }

                    Mono<Void> adjustCounter = allEvaluators.size() > 1
                            ? adjustAssertionCounter(experimentId,
                                    tracesBatch.workspaceId(), allEvaluators.size() - 1)
                            : Mono.empty();

                    return adjustCounter.thenReturn(
                            allEvaluators.stream()
                                    .map(prepared -> TraceToScoreLlmAsJudge.builder()
                                            .trace(trace)
                                            .ruleId(idGenerator.generateId())
                                            .ruleName(prepared.name())
                                            .llmAsJudgeCode(prepared.code())
                                            .workspaceId(tracesBatch.workspaceId())
                                            .userName(tracesBatch.userName())
                                            .categoryName(SUITE_ASSERTION_CATEGORY)
                                            .scoreNameMapping(prepared.scoreNameMapping())
                                            .promptType(PromptType.PYTHON)
                                            .experimentId(experimentId)
                                            .build())
                                    .toList());
                })
                .onErrorResume(e -> {
                    log.error("Failed to fetch evaluators for trace '{}', "
                            + "decrementing counter and skipping scoring", trace.id(), e);
                    return decrementAssertionCounter(experimentId,
                            tracesBatch.workspaceId())
                            .then(Mono.empty());
                });
    }

    private Mono<DatasetEvaluatorsResult> fetchDatasetEvaluators(UUID datasetId, String versionHash) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            return Mono.fromCallable(() -> {
                Optional<DatasetVersion> version;
                if (versionHash != null) {
                    var versionId = datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash);
                    version = Optional.of(datasetVersionService.getVersionById(workspaceId, datasetId, versionId));
                } else {
                    version = datasetVersionService.getLatestVersion(datasetId, workspaceId);
                }

                return version
                        .map(v -> DatasetEvaluatorsResult.builder()
                                .versionId(v.id())
                                .evaluators(v.evaluators() != null ? v.evaluators() : List.of())
                                .build())
                        .orElse(DatasetEvaluatorsResult.builder().evaluators(List.of()).build());
            }).subscribeOn(Schedulers.boundedElastic());
        });
    }

    private Mono<List<PreparedEvaluator>> fetchItemEvaluators(UUID itemId, String modelName) {
        return datasetItemService.get(itemId)
                .timeout(Duration.ofSeconds(testSuiteConfig.getFetchTimeoutSeconds()))
                .map(item -> Optional.ofNullable(item.evaluators())
                        .filter(evaluators -> !evaluators.isEmpty())
                        .map(evaluators -> evaluatorMapper.prepareEvaluators(evaluators, modelName))
                        .orElse(List.of()))
                .defaultIfEmpty(List.of());
    }

    @lombok.Builder(toBuilder = true)
    private record DatasetEvaluatorsResult(UUID versionId, @NonNull List<EvaluatorItem> evaluators) {
    }

    private Optional<String> getMetadataString(Trace trace, String key) {
        return Optional.ofNullable(trace.metadata())
                .map(metadata -> metadata.get(key))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .filter(s -> !s.isEmpty());
    }

    private static String datasetEvaluatorsCacheKey(UUID datasetId, String versionHash) {
        return datasetId + ":" + (versionHash != null ? versionHash : "");
    }

    private Optional<UUID> parseUUID(String id, UUID traceId) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID for test_suite_dataset_item_id '{}' in trace '{}'", id, traceId);
            return Optional.empty();
        }
    }

    private Mono<Void> decrementAssertionCounterForTrace(Trace trace, String workspaceId) {
        return Mono.justOrEmpty(
                getMetadataString(trace, TestSuiteMetadataKeys.EXPERIMENT_ID)
                        .flatMap(id -> parseUUID(id, trace.id())))
                .flatMap(experimentId -> decrementAssertionCounter(experimentId, workspaceId));
    }

    private Mono<Void> decrementAssertionCounter(UUID experimentId, String workspaceId) {
        if (experimentId == null) {
            return Mono.empty();
        }
        return testSuiteAssertionCounterService.decrementAndFinishIfComplete(workspaceId, experimentId)
                .onErrorResume(e -> {
                    log.error("Failed to decrement assertion counter for experiment '{}'",
                            experimentId, e);
                    return Mono.empty();
                });
    }

    private Mono<Void> adjustAssertionCounter(UUID experimentId, String workspaceId,
            long additionalMessages) {
        if (experimentId == null) {
            return Mono.empty();
        }
        return testSuiteAssertionCounterService.adjust(workspaceId, experimentId, additionalMessages)
                .then()
                .onErrorResume(e -> {
                    log.error("Failed to adjust assertion counter for experiment '{}'",
                            experimentId, e);
                    return Mono.empty();
                });
    }

    private Set<LlmProvider> getConnectedProviders(String workspaceId) {
        try {
            return llmProviderApiKeyService.find(workspaceId)
                    .content().stream()
                    .map(ProviderApiKey::provider)
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            log.error("Failed to fetch connected providers for workspace '{}'", workspaceId, e);
            return Set.of();
        }
    }

}
