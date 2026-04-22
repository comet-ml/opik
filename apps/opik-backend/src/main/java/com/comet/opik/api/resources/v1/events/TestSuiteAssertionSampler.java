package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
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
import com.comet.opik.domain.AssertionCounterService;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.LlmProviderApiKeyService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.TestSuiteConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
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
import java.util.stream.Stream;

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
    private final AssertionCounterService assertionCounterService;
    private final ExperimentService experimentService;

    @Inject
    public TestSuiteAssertionSampler(
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull OnlineScorePublisher onlineScorePublisher,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("testSuite") TestSuiteConfig testSuiteConfig,
            @NonNull TestSuiteEvaluatorMapper evaluatorMapper,
            @NonNull LlmProviderApiKeyService llmProviderApiKeyService,
            @NonNull AssertionCounterService assertionCounterService,
            @NonNull ExperimentService experimentService) {
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.idGenerator = idGenerator;
        this.testSuiteConfig = testSuiteConfig;
        this.evaluatorMapper = evaluatorMapper;
        this.llmProviderApiKeyService = llmProviderApiKeyService;
        this.assertionCounterService = assertionCounterService;
        this.experimentService = experimentService;
    }

    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        var completeTraces = tracesBatch.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .toList();

        if (completeTraces.isEmpty()) {
            return;
        }

        var reactiveContext = Context.of(
                RequestContext.WORKSPACE_ID, tracesBatch.workspaceId(),
                RequestContext.USER_NAME, tracesBatch.userName(),
                RequestContext.VISIBILITY, Visibility.PRIVATE);

        Duration fetchTimeout = Duration.ofSeconds(testSuiteConfig.getFetchTimeoutSeconds());

        // Resolve model once per batch: prefer connected provider, fall back to first trace's model
        var connectedProviders = getConnectedProviders(tracesBatch.workspaceId());
        String modelName = SupportedJudgeProvider.resolveModel(connectedProviders)
                .or(() -> getMetadataString(completeTraces.getFirst(), TestSuiteMetadataKeys.MODEL))
                .orElse(null);

        if (modelName == null) {
            log.warn("No LLM model resolved for test suite batch in workspace '{}' — "
                    + "no supported provider connected and no test_suite_model in trace metadata",
                    tracesBatch.workspaceId());
            return;
        }

        // Cache dataset evaluators by (datasetId:versionHash) to avoid redundant fetches
        Map<String, List<PreparedEvaluator>> datasetEvaluatorsCache = new HashMap<>();

        List<TraceToScoreLlmAsJudge> messages = completeTraces.stream()
                .flatMap(trace -> {
                    var testSuiteDatasetId = getMetadataString(trace, TestSuiteMetadataKeys.DATASET_ID);
                    if (testSuiteDatasetId.isEmpty()) {
                        return Stream.empty();
                    }

                    UUID datasetId;
                    try {
                        datasetId = UUID.fromString(testSuiteDatasetId.get());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid test_suite_dataset_id '{}' in trace metadata",
                                testSuiteDatasetId.get());
                        return Stream.empty();
                    }

                    var versionHash = getMetadataString(trace, TestSuiteMetadataKeys.DATASET_VERSION_HASH)
                            .orElse(null);

                    var cacheKey = datasetId + ":" + (versionHash != null ? versionHash : "");
                    var preparedDatasetEvaluators = datasetEvaluatorsCache.computeIfAbsent(cacheKey, k -> {
                        log.info("Fetching test suite evaluators for dataset '{}', version hash '{}'",
                                datasetId, versionHash != null ? versionHash : "latest");
                        DatasetEvaluatorsResult result = fetchDatasetEvaluators(datasetId, versionHash)
                                .contextWrite(reactiveContext)
                                .timeout(fetchTimeout)
                                .block();
                        return evaluatorMapper.prepareEvaluators(result.evaluators(), modelName);
                    });

                    var experimentId = getMetadataString(trace, TestSuiteMetadataKeys.EXPERIMENT_ID)
                            .flatMap(id -> parseUUID(id, trace.id()))
                            .orElse(null);

                    var datasetItemId = getMetadataString(trace, TestSuiteMetadataKeys.DATASET_ITEM_ID);
                    if (datasetItemId.isEmpty()) {
                        log.debug("Skipping trace '{}' — no test_suite_dataset_item_id in metadata",
                                trace.id());
                        decrementAssertionCounter(experimentId, tracesBatch.workspaceId(),
                                tracesBatch.userName());
                        return Stream.empty();
                    }

                    return parseUUID(datasetItemId.get(), trace.id()).stream()
                            .flatMap(itemId -> {
                                List<PreparedEvaluator> allEvaluators = new ArrayList<>(
                                        preparedDatasetEvaluators);
                                allEvaluators.addAll(fetchItemEvaluators(itemId, reactiveContext,
                                        modelName));

                                if (allEvaluators.isEmpty()) {
                                    log.debug("No evaluators found for trace '{}', dataset item '{}'",
                                            trace.id(), datasetItemId.get());
                                    decrementAssertionCounter(experimentId, tracesBatch.workspaceId(),
                                            tracesBatch.userName());
                                    return Stream.empty();
                                }

                                if (allEvaluators.size() > 1) {
                                    adjustAssertionCounter(experimentId, allEvaluators.size() - 1);
                                }

                                return allEvaluators.stream()
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
                                                .build());
                            });
                })
                .toList();

        if (!messages.isEmpty()) {
            log.info("Enqueuing '{}' test suite assertion messages", messages.size());
            onlineScorePublisher.enqueueMessage(messages, AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }
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
            }).subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(e -> {
                        log.error("Failed to fetch dataset evaluators for dataset '{}'", datasetId, e);
                        return Mono.just(DatasetEvaluatorsResult.builder().evaluators(List.of()).build());
                    });
        });
    }

    private List<PreparedEvaluator> fetchItemEvaluators(
            UUID itemId, Context reactiveContext,
            String modelName) {
        try {
            var item = datasetItemService.get(itemId)
                    .contextWrite(reactiveContext)
                    .timeout(Duration.ofSeconds(testSuiteConfig.getFetchTimeoutSeconds()))
                    .block();

            if (item == null || item.evaluators() == null || item.evaluators().isEmpty()) {
                return List.of();
            }

            return evaluatorMapper.prepareEvaluators(item.evaluators(), modelName);
        } catch (Exception e) {
            log.error("Failed to fetch evaluators for item '{}'", itemId, e);
            return List.of();
        }
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

    private Optional<UUID> parseUUID(String id, UUID traceId) {
        try {
            return Optional.of(UUID.fromString(id));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid UUID for test_suite_dataset_item_id '{}' in trace '{}'", id, traceId);
            return Optional.empty();
        }
    }

    private void decrementAssertionCounter(UUID experimentId, String workspaceId, String userName) {
        if (experimentId == null) {
            return;
        }
        try {
            long remaining = assertionCounterService.decrement(experimentId).block();
            if (remaining <= 0) {
                log.info("Assertion counter reached zero for experiment '{}', finishing", experimentId);
                finishExperimentAfterAssertions(experimentId, workspaceId, userName);
            }
        } catch (Exception e) {
            log.error("Failed to decrement assertion counter for experiment '{}'", experimentId, e);
        }
    }

    private void finishExperimentAfterAssertions(UUID experimentId, String workspaceId, String userName) {
        try {
            var statusUpdate = ExperimentUpdate.builder()
                    .status(ExperimentStatus.COMPLETED)
                    .build();

            experimentService.update(experimentId, statusUpdate)
                    .then(experimentService.finishExperiments(Set.of(experimentId)))
                    .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, userName)
                            .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                    .block();

            log.info("Finished experiment '{}' after all assertions completed", experimentId);
        } catch (Exception e) {
            log.error("Failed to finish experiment '{}' after assertions", experimentId, e);
        }
    }

    private void adjustAssertionCounter(UUID experimentId, long additionalMessages) {
        if (experimentId == null) {
            return;
        }
        try {
            assertionCounterService.adjust(experimentId, additionalMessages).block();
        } catch (Exception e) {
            log.error("Failed to adjust assertion counter for experiment '{}'", experimentId, e);
        }
    }

    private Set<LlmProvider> getConnectedProviders(String workspaceId) {
        try {
            return llmProviderApiKeyService.find(workspaceId)
                    .content().stream()
                    .map(ProviderApiKey::provider)
                    .collect(Collectors.toSet());
        } catch (Exception e) {
            log.error("Failed to fetch connected providers for workspace '{}'", workspaceId, e);
            return Set.of();
        }
    }

}
