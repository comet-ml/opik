package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.PromptType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.resources.v1.events.EvalSuiteEvaluatorMapper.PreparedEvaluator;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.EvalSuiteConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Listens for TracesCreated events and checks if traces contain eval suite metadata.
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
public class EvalSuiteAssertionSampler {

    public static final String SUITE_ASSERTION_CATEGORY = "suite_assertion";

    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final OnlineScorePublisher onlineScorePublisher;
    private final IdGenerator idGenerator;
    private final EvalSuiteConfig evalSuiteConfig;
    private final EvalSuiteEvaluatorMapper evaluatorMapper;

    @Inject
    public EvalSuiteAssertionSampler(
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull OnlineScorePublisher onlineScorePublisher,
            @NonNull IdGenerator idGenerator,
            @NonNull @Config("evalSuite") EvalSuiteConfig evalSuiteConfig,
            @NonNull EvalSuiteEvaluatorMapper evaluatorMapper) {
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.idGenerator = idGenerator;
        this.evalSuiteConfig = evalSuiteConfig;
        this.evaluatorMapper = evaluatorMapper;
    }

    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        var completeTraces = tracesBatch.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .toList();

        if (completeTraces.isEmpty()) {
            return;
        }

        var reactiveContext = reactor.util.context.Context.of(
                RequestContext.WORKSPACE_ID, tracesBatch.workspaceId(),
                RequestContext.USER_NAME, tracesBatch.userName(),
                RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE);

        Duration fetchTimeout = Duration.ofSeconds(evalSuiteConfig.getFetchTimeoutSeconds());

        // Cache dataset evaluators by (datasetId:versionHash) to avoid redundant fetches
        Map<String, List<PreparedEvaluator>> datasetEvaluatorsCache = new HashMap<>();

        List<TraceToScoreLlmAsJudge> messages = completeTraces.stream()
                .flatMap(trace -> {
                    var evalSuiteDatasetId = getMetadataString(trace, "eval_suite_dataset_id");
                    if (evalSuiteDatasetId.isEmpty()) {
                        return Stream.empty();
                    }

                    UUID datasetId;
                    try {
                        datasetId = UUID.fromString(evalSuiteDatasetId.get());
                    } catch (IllegalArgumentException e) {
                        log.warn("Invalid eval_suite_dataset_id '{}' in trace metadata",
                                evalSuiteDatasetId.get());
                        return Stream.empty();
                    }

                    var versionHash = getMetadataString(trace, "eval_suite_dataset_version_hash")
                            .orElse(null);

                    var cacheKey = datasetId + ":" + (versionHash != null ? versionHash : "");
                    var preparedDatasetEvaluators = datasetEvaluatorsCache.computeIfAbsent(cacheKey, k -> {
                        log.info("Fetching eval suite evaluators for dataset '{}', version hash '{}'",
                                datasetId, versionHash != null ? versionHash : "latest");
                        DatasetEvaluatorsResult result = fetchDatasetEvaluators(datasetId, versionHash)
                                .contextWrite(reactiveContext)
                                .timeout(fetchTimeout)
                                .block();
                        return evaluatorMapper.prepareEvaluators(result.evaluators());
                    });

                    var datasetItemId = getMetadataString(trace, "eval_suite_dataset_item_id");
                    if (datasetItemId.isEmpty()) {
                        log.debug("Skipping trace '{}' — no eval_suite_dataset_item_id in metadata",
                                trace.id());
                        return Stream.empty();
                    }

                    return parseUUID(datasetItemId.get(), trace.id()).stream()
                            .flatMap(itemId -> {
                                List<PreparedEvaluator> allEvaluators = new ArrayList<>(
                                        preparedDatasetEvaluators);
                                allEvaluators.addAll(fetchItemEvaluators(itemId, reactiveContext));

                                if (allEvaluators.isEmpty()) {
                                    log.debug("No evaluators found for trace '{}', dataset item '{}'",
                                            trace.id(), datasetItemId.get());
                                    return Stream.empty();
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
                                                .build());
                            });
                })
                .toList();

        if (!messages.isEmpty()) {
            log.info("Enqueuing '{}' eval suite assertion messages", messages.size());
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
            UUID itemId, reactor.util.context.Context reactiveContext) {
        try {
            var item = datasetItemService.get(itemId)
                    .contextWrite(reactiveContext)
                    .timeout(Duration.ofSeconds(evalSuiteConfig.getFetchTimeoutSeconds()))
                    .block();

            if (item == null || item.evaluators() == null || item.evaluators().isEmpty()) {
                return List.of();
            }

            return evaluatorMapper.prepareEvaluators(item.evaluators());
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
            log.warn("Invalid UUID for eval_suite_dataset_item_id '{}' in trace '{}'", id, traceId);
            return Optional.empty();
        }
    }

}
