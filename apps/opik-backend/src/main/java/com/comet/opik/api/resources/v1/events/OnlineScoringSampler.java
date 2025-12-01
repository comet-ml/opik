package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.domain.evaluators.TraceFilterEvaluationService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * This service listens for Traces creation server in-memory event (via EventBus). When it happens, it fetches
 * Automation Rules for the trace's project and samples the trace batch for the proper scoring. The trace and code
 * (which can be a LLM-as-Judge, a Python code or new integrations we add) are enqueued in a Redis stream dedicated
 * to that evaluator type.
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSampler {

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final TraceFilterEvaluationService filterEvaluationService;
    private final SecureRandom secureRandom;
    private final Logger userFacingLogger;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineScorePublisher onlineScorePublisher;

    @Inject
    public OnlineScoringSampler(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull TraceFilterEvaluationService filterEvaluationService,
            @NonNull OnlineScorePublisher onlineScorePublisher) throws NoSuchAlgorithmException {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.filterEvaluationService = filterEvaluationService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.serviceTogglesConfig = serviceTogglesConfig;
        secureRandom = SecureRandom.getInstanceStrong();
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSampler.class);
    }

    /**
     * Listen for trace batches to check for existent Automation Rules to score them. It samples the trace batch and
     * enqueues the sample into Redis Stream.
     *
     * @param tracesBatch a traces batch with workspaceId and userName
     */
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        var tracesByProject = tracesBatch.traces().stream().collect(Collectors.groupingBy(Trace::projectId));

        var countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.info("Received '{}' traces for workspace '{}': '{}'",
                tracesBatch.traces().size(), tracesBatch.workspaceId(), countMap);

        // fetch automation rules per project
        tracesByProject.forEach((projectId, traces) -> {
            log.info("Fetching evaluators for '{}' traces, project '{}' on workspace '{}'",
                    traces.size(), projectId, tracesBatch.workspaceId());

            List<? extends AutomationRuleEvaluator<?, ?>> evaluators = ruleEvaluatorService.findAll(
                    projectId, tracesBatch.workspaceId());

            // Filter evaluators based on trace metadata if selected_rule_ids is present
            evaluators = filterEvaluatorsByTraceMetadata(evaluators, traces);

            //When using the MDC with multiple threads, we must ensure that the context is propagated. For this reason, we must use the wrapWithMdc method.
            evaluators.parallelStream().forEach(evaluator -> {
                // samples traces for this rule
                var samples = traces.stream()
                        .filter(trace -> shouldSampleTrace(evaluator, tracesBatch.workspaceId(), trace));
                switch (evaluator.getType()) {
                    case LLM_AS_JUDGE -> {
                        var messages = samples
                                .map(trace -> toLlmAsJudgeMessage(tracesBatch,
                                        (AutomationRuleEvaluatorLlmAsJudge) evaluator, trace))
                                .toList();
                        logSampledTrace(tracesBatch, evaluator, messages);
                        onlineScorePublisher.enqueueMessage(messages, AutomationRuleEvaluatorType.LLM_AS_JUDGE);
                    }
                    case USER_DEFINED_METRIC_PYTHON -> {
                        if (serviceTogglesConfig.isPythonEvaluatorEnabled()) {
                            var messages = samples
                                    .map(trace -> toScoreUserDefinedMetricPython(tracesBatch,
                                            (AutomationRuleEvaluatorUserDefinedMetricPython) evaluator, trace))
                                    .toList();
                            logSampledTrace(tracesBatch, evaluator, messages);
                            onlineScorePublisher.enqueueMessage(messages,
                                    AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON);
                        } else {
                            log.warn("Python evaluator is disabled. Skipping sampling for evaluator type '{}'",
                                    evaluator.getType());
                        }
                    }
                    default -> log.warn("No process defined for evaluator type '{}'", evaluator.getType());
                }
            });
        });
    }

    private boolean shouldSampleTrace(AutomationRuleEvaluator<?, ?> evaluator, String workspaceId, Trace trace) {
        // Check if rule is enabled first
        if (!evaluator.isEnabled()) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = createTraceLoggingContext(workspaceId, evaluator, trace)) {
                userFacingLogger.info(
                        "The traceId '{}' was skipped for rule: '{}' as the rule is disabled",
                        trace.id(), evaluator.getName());
            }
            return false;
        }

        // Check if trace matches all filters
        if (!filterEvaluationService.matchesAllFilters(evaluator.getFilters(), trace)) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = createTraceLoggingContext(workspaceId, evaluator, trace)) {
                userFacingLogger.info(
                        "The traceId '{}' was skipped for rule: '{}' as it does not match the configured filters",
                        trace.id(), evaluator.getName());
            }
            return false;
        }

        var shouldBeSampled = secureRandom.nextFloat() < evaluator.getSamplingRate();

        if (!shouldBeSampled) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = createTraceLoggingContext(workspaceId, evaluator, trace)) {
                userFacingLogger.info(
                        "The traceId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                        trace.id(), evaluator.getName(), evaluator.getSamplingRate());
            }
        }

        return shouldBeSampled;
    }

    private TraceToScoreLlmAsJudge toLlmAsJudgeMessage(TracesCreated tracesBatch,
            AutomationRuleEvaluatorLlmAsJudge evaluator,
            Trace trace) {
        return TraceToScoreLlmAsJudge.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(tracesBatch.workspaceId())
                .userName(tracesBatch.userName())
                .build();
    }

    private TraceToScoreUserDefinedMetricPython toScoreUserDefinedMetricPython(TracesCreated tracesBatch,
            AutomationRuleEvaluatorUserDefinedMetricPython evaluator,
            Trace trace) {
        return TraceToScoreUserDefinedMetricPython.builder()
                .trace(trace)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .code(evaluator.getCode())
                .workspaceId(tracesBatch.workspaceId())
                .userName(tracesBatch.userName())
                .build();
    }

    private void logSampledTrace(TracesCreated tracesBatch, AutomationRuleEvaluator<?, ?> evaluator, List<?> messages) {
        log.info("[AutomationRule '{}', type '{}'] Sampled '{}/{}' from trace batch (expected rate: '{}')",
                evaluator.getName(),
                evaluator.getType(),
                messages.size(),
                tracesBatch.traces().size(),
                evaluator.getSamplingRate());
    }

    private LogContextAware.Closable createTraceLoggingContext(String workspaceId,
            AutomationRuleEvaluator<?, ?> evaluator,
            Trace trace) {
        return wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.RULE_ID, evaluator.getId().toString(),
                UserLog.TRACE_ID, trace.id().toString()));
    }

    /**
     * Filters evaluators based on trace metadata. If the first trace in the batch contains
     * "selected_rule_ids" in its metadata, only evaluators with IDs in that list will be returned.
     * Otherwise, all evaluators are returned (default behavior for backward compatibility).
     *
     * Note: All traces in a batch from the same source (e.g., Playground) will have the same metadata,
     * so checking only the first trace is sufficient.
     *
     * @param evaluators the list of all evaluators for the project
     * @param traces the traces batch to check for metadata
     * @return filtered list of evaluators, or all evaluators if no selection metadata is present
     */
    private List<? extends AutomationRuleEvaluator<?, ?>> filterEvaluatorsByTraceMetadata(
            List<? extends AutomationRuleEvaluator<?, ?>> evaluators, List<Trace> traces) {

        // Check if batch is empty
        if (traces.isEmpty()) {
            return evaluators;
        }

        // Check the first trace for selected_rule_ids metadata
        // All traces in the same batch will have identical metadata
        Optional<List<UUID>> selectedRuleIds = extractSelectedRuleIds(traces.getFirst());

        // If no selection found, return all evaluators (default behavior)
        if (selectedRuleIds.isEmpty()) {
            return evaluators;
        }

        List<UUID> ruleIdsToApply = selectedRuleIds.get();
        log.info("Filtering evaluators based on trace metadata. Selected rule IDs: '{}'", ruleIdsToApply);

        // Filter evaluators to only include those in the selected list
        List<? extends AutomationRuleEvaluator<?, ?>> filtered = evaluators.stream()
                .filter(evaluator -> ruleIdsToApply.contains(evaluator.getId()))
                .toList();

        log.info("Filtered '{}' evaluators out of '{}' based on trace metadata", filtered.size(), evaluators.size());
        return filtered;
    }

    /**
     * Extracts selected_rule_ids from trace metadata if present.
     *
     * @param trace the trace to check
     * @return Optional containing list of rule UUIDs if present, empty otherwise
     */
    private Optional<List<UUID>> extractSelectedRuleIds(Trace trace) {
        return Optional.ofNullable(trace.metadata())
                .map(metadata -> metadata.get("selected_rule_ids"))
                .filter(JsonNode::isArray)
                .map(ruleIdsNode -> {
                    try {
                        List<UUID> ruleIds = new ArrayList<>();
                        ruleIdsNode.forEach(idNode -> {
                            if (idNode.isTextual()) {
                                try {
                                    ruleIds.add(UUID.fromString(idNode.asText()));
                                } catch (IllegalArgumentException e) {
                                    log.warn("Invalid UUID format in selected_rule_ids metadata for trace: '{}'",
                                            trace.id(), e);
                                }
                            }
                        });
                        return ruleIds.isEmpty() ? null : ruleIds;
                    } catch (Exception e) {
                        log.warn("Error parsing selected_rule_ids metadata for trace: '{}'", trace.id(), e);
                        return null;
                    }
                });
    }
}
