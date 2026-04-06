package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.EvaluatorItem;
import com.comet.opik.api.EvaluatorType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.DatasetItemService;
import com.comet.opik.domain.DatasetVersionService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Listens for TracesCreated events and checks if traces contain eval suite metadata.
 * When found, reads evaluators from the dataset item (per-item evaluators) and/or
 * dataset version (default evaluators), then enqueues them for LLM-as-judge evaluation
 * via the same Redis pipeline used by online scoring.
 *
 * Evaluator resolution follows the SDK pattern:
 * - Dataset version evaluators apply to all items
 * - Each dataset item can have additional evaluators
 * - Both are concatenated for the final evaluator set (not replaced)
 */
@EagerSingleton
@Slf4j
public class EvalSuiteAssertionSampler {

    private static final String DEFAULT_MODEL_NAME = "gpt-5-nano";
    private static final String SUITE_ASSERTION_CATEGORY = "suite_assertion";

    private final DatasetItemService datasetItemService;
    private final DatasetVersionService datasetVersionService;
    private final OnlineScorePublisher onlineScorePublisher;

    @Inject
    public EvalSuiteAssertionSampler(
            @NonNull DatasetItemService datasetItemService,
            @NonNull DatasetVersionService datasetVersionService,
            @NonNull OnlineScorePublisher onlineScorePublisher) {
        this.datasetItemService = datasetItemService;
        this.datasetVersionService = datasetVersionService;
        this.onlineScorePublisher = onlineScorePublisher;
    }

    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        var completeTraces = tracesBatch.traces().stream()
                .filter(trace -> trace.endTime() != null)
                .toList();

        if (completeTraces.isEmpty()) {
            return;
        }

        var firstTrace = completeTraces.getFirst();
        var evalSuiteDatasetId = extractStringMetadata(firstTrace, "eval_suite_dataset_id");

        if (evalSuiteDatasetId.isEmpty()) {
            return;
        }

        UUID datasetId;
        try {
            datasetId = UUID.fromString(evalSuiteDatasetId.get());
        } catch (IllegalArgumentException e) {
            log.warn("Invalid eval_suite_dataset_id '{}' in trace metadata", evalSuiteDatasetId.get());
            return;
        }

        var evalSuiteVersionHash = extractStringMetadata(firstTrace, "eval_suite_version_hash");

        log.info("Eval suite assertion evaluation triggered for dataset '{}', version hash '{}', '{}' traces",
                datasetId, evalSuiteVersionHash.orElse("latest"), completeTraces.size());

        // Fetch version-level default evaluators
        List<EvaluatorItem> versionEvaluators = fetchVersionEvaluators(
                datasetId, evalSuiteVersionHash.orElse(null), tracesBatch.workspaceId());

        List<TraceToScoreLlmAsJudge> messages = new ArrayList<>();

        for (Trace trace : completeTraces) {
            // Get per-item evaluators from the dataset item
            var datasetItemId = extractStringMetadata(trace, "eval_suite_dataset_item_id");
            List<EvaluatorItem> itemEvaluators = List.of();

            if (datasetItemId.isPresent()) {
                itemEvaluators = fetchItemEvaluators(
                        UUID.fromString(datasetItemId.get()), tracesBatch.workspaceId());
            }

            // Concatenate: version-level evaluators apply to all items,
            // item-level evaluators are additional (matching SDK behavior)
            List<EvaluatorItem> evaluators = new ArrayList<>(versionEvaluators);
            evaluators.addAll(itemEvaluators);

            if (evaluators.isEmpty()) {
                log.debug("No evaluators found for trace '{}', dataset item '{}'",
                        trace.id(), datasetItemId.orElse("unknown"));
                continue;
            }

            for (EvaluatorItem evaluator : evaluators) {
                if (evaluator.type() != EvaluatorType.LLM_JUDGE) {
                    log.debug("Skipping non-LLM evaluator '{}' of type '{}'", evaluator.name(), evaluator.type());
                    continue;
                }

                try {
                    LlmAsJudgeCode code = deserializeEvaluatorConfig(evaluator.config());
                    code = resolveModelName(code);
                    code = injectAssertionsVariable(code);
                    code = convertMessagesToMustacheFormat(code);

                    // Map schema field names to assertion description for assertion result naming
                    // (matches SDK behavior where assertion names are the full assertion text)
                    Map<String, String> scoreNameMapping = code.schema() != null
                            ? code.schema().stream()
                                    .collect(Collectors.toMap(
                                            LlmAsJudgeOutputSchema::name,
                                            LlmAsJudgeOutputSchema::description))
                            : Map.of();

                    var message = TraceToScoreLlmAsJudge.builder()
                            .trace(trace)
                            .ruleId(generateDeterministicId(evaluator.name()))
                            .ruleName(evaluator.name())
                            .llmAsJudgeCode(code)
                            .workspaceId(tracesBatch.workspaceId())
                            .userName(tracesBatch.userName())
                            .categoryName(SUITE_ASSERTION_CATEGORY)
                            .scoreNameMapping(scoreNameMapping)
                            .build();

                    messages.add(message);
                } catch (Exception e) {
                    log.error("Failed to deserialize evaluator config for '{}': '{}'",
                            evaluator.name(), e.getMessage(), e);
                }
            }
        }

        if (!messages.isEmpty()) {
            log.info("Enqueuing '{}' eval suite assertion messages", messages.size());
            onlineScorePublisher.enqueueMessage(messages, AutomationRuleEvaluatorType.LLM_AS_JUDGE);
        }
    }

    private List<EvaluatorItem> fetchVersionEvaluators(UUID datasetId, String versionHash, String workspaceId) {
        try {
            Optional<DatasetVersion> version;
            if (versionHash != null) {
                var versionId = datasetVersionService.resolveVersionId(workspaceId, datasetId, versionHash);
                version = Optional.of(datasetVersionService.getVersionById(workspaceId, datasetId, versionId));
            } else {
                version = datasetVersionService.getLatestVersion(datasetId, workspaceId);
            }

            return version
                    .map(v -> v.evaluators() != null ? v.evaluators() : List.<EvaluatorItem>of())
                    .orElse(List.of());
        } catch (Exception e) {
            log.error("Failed to fetch version evaluators for dataset '{}': '{}'",
                    datasetId, e.getMessage(), e);
            return List.of();
        }
    }

    private List<EvaluatorItem> fetchItemEvaluators(UUID datasetItemId, String workspaceId) {
        try {
            DatasetItem item = datasetItemService.get(datasetItemId)
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.WORKSPACE_ID, workspaceId)
                            .put(RequestContext.USER_NAME, "system")
                            .put(RequestContext.VISIBILITY, com.comet.opik.api.Visibility.PRIVATE))
                    .block();

            if (item != null && item.evaluators() != null && !item.evaluators().isEmpty()) {
                return item.evaluators();
            }
            return List.of();
        } catch (Exception e) {
            log.error("Failed to fetch item evaluators for dataset item '{}': '{}'",
                    datasetItemId, e.getMessage(), e);
            return List.of();
        }
    }

    private LlmAsJudgeCode deserializeEvaluatorConfig(JsonNode config) {
        return JsonUtils.treeToValue(config, LlmAsJudgeCode.class);
    }

    /**
     * Builds assertions text from the schema field and adds it as a literal variable,
     * matching the SDK's format_assertions() output format:
     * - `assertion_1`: description text
     * - `assertion_2`: description text
     */
    private LlmAsJudgeCode injectAssertionsVariable(LlmAsJudgeCode code) {
        if (code.schema() == null || code.schema().isEmpty()) {
            return code;
        }

        String assertionsText = buildAssertionsText(code.schema());

        var updatedVariables = new HashMap<>(code.variables());
        updatedVariables.put("assertions", assertionsText);

        return new LlmAsJudgeCode(code.model(), code.messages(), updatedVariables, code.schema());
    }

    private String buildAssertionsText(List<LlmAsJudgeOutputSchema> schema) {
        return schema.stream()
                .map(s -> "- `%s`: %s".formatted(s.name(), s.description()))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Converts SDK-style Python format string templates {var} in message content
     * to Mustache triple-brace format {{{var}}} used by OnlineScoringEngine.
     * Triple braces prevent Mustache from HTML-escaping substituted values,
     * which is needed because assertions text contains backticks and newlines.
     */
    private LlmAsJudgeCode convertMessagesToMustacheFormat(LlmAsJudgeCode code) {
        var convertedMessages = code.messages().stream()
                .map(msg -> {
                    if (msg.isStringContent()) {
                        String converted = convertSdkTemplateToMustache(msg.asString());
                        return msg.toBuilder().content(converted).build();
                    }
                    return msg;
                })
                .toList();
        return new LlmAsJudgeCode(code.model(), convertedMessages, code.variables(), code.schema());
    }

    private static String convertSdkTemplateToMustache(String template) {
        // Match {word_chars} not preceded by { and not followed by }
        return template.replaceAll("(?<!\\{)\\{(\\w+)}(?!})", "{{{$1}}}");
    }

    private LlmAsJudgeCode resolveModelName(LlmAsJudgeCode code) {
        if (code.model() == null || code.model().name() == null || code.model().name().isBlank()) {
            var existingModel = code.model();
            var resolvedModel = LlmAsJudgeModelParameters.builder()
                    .name(DEFAULT_MODEL_NAME)
                    .temperature(existingModel != null ? existingModel.temperature() : null)
                    .seed(existingModel != null ? existingModel.seed() : null)
                    .customParameters(existingModel != null ? existingModel.customParameters() : null)
                    .build();
            return new LlmAsJudgeCode(resolvedModel, code.messages(), code.variables(), code.schema());
        }
        return code;
    }

    private Optional<String> extractStringMetadata(Trace trace, String key) {
        return Optional.ofNullable(trace.metadata())
                .map(metadata -> metadata.get(key))
                .filter(JsonNode::isTextual)
                .map(JsonNode::asText)
                .filter(s -> !s.isEmpty());
    }

    private UUID generateDeterministicId(String name) {
        return UUID.nameUUIDFromBytes(name.getBytes());
    }
}
