package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.domain.evaluators.SpanFilterEvaluationService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.LogContextAware;
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
 * This service listens for Spans creation server in-memory event (via EventBus). When it happens, it fetches
 * Automation Rules for the span's project and samples the span batch for the proper scoring. The span and code
 * (which can be a LLM-as-Judge or new integrations we add) are enqueued in a Redis stream dedicated
 * to that evaluator type.
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSpanSampler {

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final SpanFilterEvaluationService filterEvaluationService;
    private final SecureRandom secureRandom;
    private final Logger userFacingLogger;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineScorePublisher onlineScorePublisher;

    @Inject
    public OnlineScoringSpanSampler(@NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull SpanFilterEvaluationService filterEvaluationService,
            @NonNull OnlineScorePublisher onlineScorePublisher) throws NoSuchAlgorithmException {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.filterEvaluationService = filterEvaluationService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.serviceTogglesConfig = serviceTogglesConfig;
        secureRandom = SecureRandom.getInstanceStrong();
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSpanSampler.class);
    }

    /**
     * Listen for span batches to check for existent Automation Rules to score them. It samples the span batch and
     * enqueues the sample into Redis Stream.
     *
     * @param spansBatch a spans batch with workspaceId and userName
     */
    @Subscribe
    public void onSpansCreated(SpansCreated spansBatch) {
        var spansByProject = spansBatch.spans().stream().collect(Collectors.groupingBy(Span::projectId));

        var countMap = spansByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.info("Received '{}' spans for workspace '{}': '{}'",
                spansBatch.spans().size(), spansBatch.workspaceId(), countMap);

        // fetch automation rules per project
        spansByProject.forEach((projectId, spans) -> {
            log.info("Fetching evaluators for '{}' spans, project '{}' on workspace '{}'",
                    spans.size(), projectId, spansBatch.workspaceId());

            List<? extends AutomationRuleEvaluator<?, ?>> evaluators = ruleEvaluatorService.findAll(
                    projectId, spansBatch.workspaceId());

            // Filter to only span-level evaluators
            evaluators = evaluators.stream()
                    .filter(evaluator -> evaluator.getType() == AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE)
                    .collect(Collectors.toList());

            if (evaluators.isEmpty()) {
                log.debug("No span-level evaluators found for project '{}' on workspace '{}'",
                        projectId, spansBatch.workspaceId());
                return;
            }

            //When using the MDC with multiple threads, we must ensure that the context is propagated. For this reason, we must use the wrapWithMdc method.
            evaluators.parallelStream().forEach(evaluator -> {
                // samples spans for this rule
                var samples = spans.stream()
                        .filter(span -> shouldSampleSpan(evaluator, spansBatch.workspaceId(), span))
                        .toList();

                switch (evaluator.getType()) {
                    case SPAN_LLM_AS_JUDGE -> {
                        if (serviceTogglesConfig.isSpanLlmAsJudgeEnabled()) {
                            var messages = samples.stream()
                                    .map(span -> toLlmAsJudgeMessage(spansBatch,
                                            (AutomationRuleEvaluatorSpanLlmAsJudge) evaluator, span))
                                    .toList();
                            if (!messages.isEmpty()) {
                                logSampledSpan(spansBatch, evaluator, messages);
                                onlineScorePublisher.enqueueMessage(messages,
                                        AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE);
                            }
                        } else {
                            log.warn(
                                    "Span LLM as Judge evaluator is disabled. Skipping sampling for evaluator type '{}'",
                                    evaluator.getType());
                        }
                    }
                    default -> log.warn("No process defined for evaluator type '{}'", evaluator.getType());
                }
            });
        });
    }

    private boolean shouldSampleSpan(AutomationRuleEvaluator<?, ?> evaluator, String workspaceId, Span span) {
        // Check if rule is enabled first
        if (!evaluator.isEnabled()) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = createSpanLoggingContext(workspaceId, evaluator, span)) {
                userFacingLogger.info(
                        "The spanId '{}' was skipped for rule: '{}' as the rule is disabled",
                        span.id(), evaluator.getName());
            }
            return false;
        }

        if (!filterEvaluationService.matchesAllFilters(evaluator.getFilters(), span)) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = createSpanLoggingContext(workspaceId, evaluator, span)) {
                userFacingLogger.info(
                        "The spanId '{}' was skipped for rule: '{}' as it does not match the configured filters",
                        span.id(), evaluator.getName());
            }
            return false;
        }

        var shouldBeSampled = secureRandom.nextFloat() < evaluator.getSamplingRate();

        if (!shouldBeSampled) {
            // Important to set the workspaceId for logging purposes
            try (var logContext = createSpanLoggingContext(workspaceId, evaluator, span)) {
                userFacingLogger.info(
                        "The spanId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                        span.id(), evaluator.getName(), evaluator.getSamplingRate());
            }
        }

        return shouldBeSampled;
    }

    private SpanToScoreLlmAsJudge toLlmAsJudgeMessage(SpansCreated spansBatch,
            AutomationRuleEvaluatorSpanLlmAsJudge evaluator,
            Span span) {
        return SpanToScoreLlmAsJudge.builder()
                .span(span)
                .ruleId(evaluator.getId())
                .ruleName(evaluator.getName())
                .llmAsJudgeCode(evaluator.getCode())
                .workspaceId(spansBatch.workspaceId())
                .userName(spansBatch.userName())
                .build();
    }

    private void logSampledSpan(SpansCreated spansBatch, AutomationRuleEvaluator<?, ?> evaluator, List<?> messages) {
        log.info("[AutomationRule '{}', type '{}'] Sampled '{}/{}' from span batch (expected rate: '{}')",
                evaluator.getName(),
                evaluator.getType(),
                messages.size(),
                spansBatch.spans().size(),
                evaluator.getSamplingRate());
    }

    private LogContextAware.Closable createSpanLoggingContext(String workspaceId,
            AutomationRuleEvaluator<?, ?> evaluator,
            Span span) {
        return wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, workspaceId,
                UserLog.RULE_ID, evaluator.getId().toString(),
                UserLog.TRACE_ID, span.traceId() != null ? span.traceId().toString() : ""));
    }

}
