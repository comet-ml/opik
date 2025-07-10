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
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.List;
import java.util.Map;
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
    private final SecureRandom secureRandom;
    private final Logger userFacingLogger;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineScorePublisher onlineScorePublisher;

    @Inject
    public OnlineScoringSampler(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull OnlineScorePublisher onlineScorePublisher) throws NoSuchAlgorithmException {
        this.ruleEvaluatorService = ruleEvaluatorService;
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

            List<? extends AutomationRuleEvaluator<?>> evaluators = ruleEvaluatorService.findAll(
                    projectId, tracesBatch.workspaceId());

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

    private boolean shouldSampleTrace(AutomationRuleEvaluator<?> evaluator, String workspaceId, Trace trace) {
        var shouldBeSampled = secureRandom.nextFloat() < evaluator.getSamplingRate();

        if (!shouldBeSampled) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = wrapWithMdc(Map.of(
                    UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                    "workspace_id", workspaceId,
                    "rule_id", evaluator.getId().toString(),
                    "trace_id", trace.id().toString()))) {

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

    private void logSampledTrace(TracesCreated tracesBatch, AutomationRuleEvaluator<?> evaluator, List<?> messages) {
        log.info("[AutomationRule '{}', type '{}'] Sampled '{}/{}' from trace batch (expected rate: '{}')",
                evaluator.getName(),
                evaluator.getType(),
                messages.size(),
                tracesBatch.traces().size(),
                evaluator.getSamplingRate());
    }
}
