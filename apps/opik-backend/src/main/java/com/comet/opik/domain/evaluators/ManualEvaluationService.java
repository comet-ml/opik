package com.comet.opik.domain.evaluators;

import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.events.SpanToScoreUserDefinedMetricPython;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.ListUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Service for manually triggering evaluation rules on selected traces or threads.
 * This service bypasses the sampling logic used by online scoring and enqueues all
 * specified entities for evaluation.
 */
@ImplementedBy(ManualEvaluationServiceImpl.class)
public interface ManualEvaluationService {

    /**
     * Manually evaluate traces or threads with the specified automation rules.
     * This bypasses sampling and enqueues all entities for evaluation.
     *
     * @param request the manual evaluation request containing entity IDs, rule IDs, and entity type
     * @param projectId the ID of the project containing the entities
     * @param workspaceId the ID of the workspace
     * @param userName the name of the user initiating the evaluation
     * @return ManualEvaluationResponse containing the number of entities queued and rules applied
     */
    Mono<ManualEvaluationResponse> evaluate(@NonNull ManualEvaluationRequest request, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName);
}

@Singleton
@Slf4j
class ManualEvaluationServiceImpl implements ManualEvaluationService {

    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final OnlineScorePublisher onlineScorePublisher;
    private final TraceService traceService;
    private final SpanService spanService;
    private final ProjectService projectService;
    private final TraceThreadService traceThreadService;
    private final ServiceTogglesConfig serviceTogglesConfig;

    @Inject
    public ManualEvaluationServiceImpl(@NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService,
            @NonNull OnlineScorePublisher onlineScorePublisher,
            @NonNull TraceService traceService,
            @NonNull SpanService spanService,
            @NonNull ProjectService projectService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig) {
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.onlineScorePublisher = onlineScorePublisher;
        this.traceService = traceService;
        this.spanService = spanService;
        this.projectService = projectService;
        this.traceThreadService = traceThreadService;
        this.serviceTogglesConfig = serviceTogglesConfig;
    }

    @Override
    public Mono<ManualEvaluationResponse> evaluate(@NonNull ManualEvaluationRequest request, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName) {

        log.info(
                "Starting manual evaluation for '{}' entities of type '{}' with '{}' rules in project '{}', workspace '{}'",
                request.entityIds().size(), request.entityType(), request.ruleIds().size(), projectId, workspaceId);

        return Mono.fromCallable(() -> {
            Set<UUID> ruleIdSet = new HashSet<>(request.ruleIds());
            List<?> rawRules = automationRuleEvaluatorService.findByIds(ruleIdSet, Set.of(projectId), workspaceId);
            @SuppressWarnings("unchecked")
            List<AutomationRuleEvaluator<?, ?>> rules = (List<AutomationRuleEvaluator<?, ?>>) rawRules;

            // Validate that all requested rules were found
            if (rules.size() != request.ruleIds().size()) {
                Set<UUID> foundRuleIds = rules.stream()
                        .map(AutomationRuleEvaluator::getId)
                        .collect(Collectors.toSet());

                Set<UUID> missingRuleIds = ruleIdSet.stream()
                        .filter(id -> !foundRuleIds.contains(id))
                        .collect(Collectors.toSet());

                log.warn("Some rule IDs were not found: '{}'", missingRuleIds);
                throw new BadRequestException(
                        "Automation rule(s) not found with ID(s): %s".formatted(
                                missingRuleIds.stream().map(UUID::toString).collect(Collectors.joining(", "))));
            }

            return rules;
        })
                .flatMap(rules -> {
                    // Process based on entity type
                    return switch (request.entityType()) {
                        case TRACE -> evaluateTraces(request.entityIds(), rules, projectId, workspaceId, userName);
                        case THREAD -> evaluateThreads(request.entityIds(), rules, projectId, workspaceId, userName);
                        case SPAN -> evaluateSpans(request.entityIds(), rules, projectId, workspaceId, userName);
                    };
                })
                .map(evaluatedCount -> {
                    log.info(
                            "Successfully queued '{}' entities for evaluation with '{}' rules in project '{}', workspace '{}'",
                            evaluatedCount, request.ruleIds().size(), projectId, workspaceId);
                    return new ManualEvaluationResponse(evaluatedCount, request.ruleIds().size());
                });
    }

    /**
     * Evaluates traces by enqueueing evaluation messages for each rule.
     * Handles both span-level evaluators (which need full trace objects) and trace-thread evaluators (which need only IDs).
     */
    private Mono<Integer> evaluateTraces(List<UUID> traceIds, List<AutomationRuleEvaluator<?, ?>> rules, UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' traces with '{}' rules", traceIds.size(), rules.size());

        // Separate rules by type - only trace-level rules are valid for trace evaluation
        List<AutomationRuleEvaluatorLlmAsJudge> spanLevelLlmAsJudgeRules = new ArrayList<>();
        List<AutomationRuleEvaluatorUserDefinedMetricPython> spanLevelPythonRules = new ArrayList<>();
        List<AutomationRuleEvaluator<?, ?>> traceThreadRules = new ArrayList<>();

        for (AutomationRuleEvaluator<?, ?> rule : rules) {
            switch (rule) {
                case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> spanLevelLlmAsJudgeRules.add(llmAsJudge);
                case AutomationRuleEvaluatorUserDefinedMetricPython python -> spanLevelPythonRules.add(python);
                case AutomationRuleEvaluatorTraceThreadLlmAsJudge traceThreadLlmAsJudge ->
                    traceThreadRules.add(traceThreadLlmAsJudge);
                case AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython traceThreadPython ->
                    traceThreadRules.add(traceThreadPython);
                // Reject span-level rules for trace evaluation
                case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmRule ->
                    throw new BadRequestException(
                            String.format("Rule '%s' of type '%s' cannot be used for TRACE evaluation",
                                    spanLlmRule.getId(), spanLlmRule.getType()));
                case AutomationRuleEvaluatorSpanUserDefinedMetricPython spanPythonRule ->
                    throw new BadRequestException(
                            String.format("Rule '%s' of type '%s' cannot be used for TRACE evaluation",
                                    spanPythonRule.getId(), spanPythonRule.getType()));
            }
        }

        // Handle span-level evaluators - need to fetch full traces
        Mono<Void> spanLevelMono = Mono.empty();
        if (!spanLevelLlmAsJudgeRules.isEmpty() || !spanLevelPythonRules.isEmpty()) {
            spanLevelMono = enqueueSpanLevelEvaluations(traceIds, spanLevelLlmAsJudgeRules,
                    spanLevelPythonRules, projectId, workspaceId, userName);
        }

        // Handle trace-thread evaluators - can use IDs directly
        return spanLevelMono.then(Mono.fromCallable(() -> {
            if (!traceThreadRules.isEmpty()) {
                List<String> traceIdStrings = traceIds.stream()
                        .map(UUID::toString)
                        .toList();

                traceThreadRules.forEach(rule -> {
                    log.info("Enqueueing trace-thread evaluation for rule '{}' with '{}' trace IDs", rule.getId(),
                            traceIdStrings.size());
                    onlineScorePublisher.enqueueThreadMessage(traceIdStrings, rule.getId(), projectId, workspaceId,
                            userName);
                });
            }

            return traceIds.size();
        }));
    }

    /**
    * Enqueues span-level evaluations by fetching full trace objects and creating messages.
    */
    private Mono<Void> enqueueSpanLevelEvaluations(List<UUID> traceIds,
            List<AutomationRuleEvaluatorLlmAsJudge> llmAsJudgeRules,
            List<AutomationRuleEvaluatorUserDefinedMetricPython> pythonRules,
            UUID projectId, String workspaceId, String userName) {

        log.info("Fetching '{}' traces for span-level evaluation", traceIds.size());

        // Fetch project to get project name
        var project = projectService.get(projectId, workspaceId);

        // Fetch all traces
        return traceService.getByIds(traceIds)
                .collectList()
                .flatMap(traces -> {
                    if (ListUtils.emptyIfNull(traces).isEmpty()) {
                        log.warn("No traces found to enqueue for span-level evaluation");
                        return Mono.empty();
                    }

                    log.info("Successfully fetched '{}' traces for span-level evaluation", traces.size());

                    // Enqueue LLM as Judge evaluations
                    llmAsJudgeRules.forEach(rule -> {
                        List<TraceToScoreLlmAsJudge> messages = traces.stream()
                                .map(trace -> TraceToScoreLlmAsJudge.builder()
                                        .trace(trace.toBuilder().projectName(project.name()).build())
                                        .ruleId(rule.getId())
                                        .ruleName(rule.getName())
                                        .llmAsJudgeCode(rule.getCode())
                                        .workspaceId(workspaceId)
                                        .userName(userName)
                                        .build())
                                .toList();

                        onlineScorePublisher.enqueueMessage(messages, rule.getType());
                        log.info("Enqueued '{}' span-level LLM as Judge messages for rule '{}'", messages.size(),
                                rule.getId());
                    });

                    // Enqueue Python evaluations (if enabled)
                    pythonRules.forEach(rule -> {
                        if (!serviceTogglesConfig.isPythonEvaluatorEnabled()) {
                            log.warn("Span-level Python evaluator is disabled, skipping rule '{}'", rule.getId());
                            return;
                        }

                        List<TraceToScoreUserDefinedMetricPython> messages = traces.stream()
                                .map(trace -> TraceToScoreUserDefinedMetricPython.builder()
                                        .trace(trace.toBuilder().projectName(project.name()).build())
                                        .ruleId(rule.getId())
                                        .ruleName(rule.getName())
                                        .code(rule.getCode())
                                        .workspaceId(workspaceId)
                                        .userName(userName)
                                        .build())
                                .toList();

                        onlineScorePublisher.enqueueMessage(messages, rule.getType());
                        log.info("Enqueued '{}' span-level Python messages for rule '{}'", messages.size(),
                                rule.getId());
                    });

                    return Mono.empty();
                });
    }

    /**
     * Evaluates spans by enqueueing evaluation messages for span-level rules.
     * Works directly with span IDs and fetches span objects.
     */
    private Mono<Integer> evaluateSpans(List<UUID> spanIds, List<AutomationRuleEvaluator<?, ?>> rules, UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' spans with '{}' rules", spanIds.size(), rules.size());

        // Separate rules by type - only span-level rules are valid
        List<AutomationRuleEvaluatorSpanLlmAsJudge> spanLlmAsJudgeRules = new ArrayList<>();
        List<AutomationRuleEvaluatorSpanUserDefinedMetricPython> spanPythonRules = new ArrayList<>();

        for (AutomationRuleEvaluator<?, ?> rule : rules) {
            switch (rule) {
                case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmAsJudge ->
                    spanLlmAsJudgeRules.add(spanLlmAsJudge);
                case AutomationRuleEvaluatorSpanUserDefinedMetricPython spanPython ->
                    spanPythonRules.add(spanPython);
                default -> {
                    log.warn("Invalid rule type '{}' for span evaluation, skipping", rule.getType());
                }
            }
        }

        if (spanLlmAsJudgeRules.isEmpty() && spanPythonRules.isEmpty()) {
            log.warn("No valid span-level rules found for span evaluation");
            return Mono.just(0);
        }

        // Fetch project to get project name
        var project = projectService.get(projectId, workspaceId);

        // Fetch all spans by their IDs
        return spanService.getByIds(new HashSet<>(spanIds))
                .collectList()
                .flatMap(spans -> {
                    if (ListUtils.emptyIfNull(spans).isEmpty()) {
                        log.warn("No spans found to enqueue for span evaluation");
                        return Mono.just(0);
                    }

                    log.info("Successfully fetched '{}' spans for evaluation", spans.size());

                    // Enqueue Span LLM as Judge evaluations
                    spanLlmAsJudgeRules.forEach(rule -> {
                        List<SpanToScoreLlmAsJudge> messages = spans.stream()
                                .map(span -> SpanToScoreLlmAsJudge.builder()
                                        .span(span.toBuilder().projectName(project.name()).build())
                                        .ruleId(rule.getId())
                                        .ruleName(rule.getName())
                                        .llmAsJudgeCode(rule.getCode())
                                        .workspaceId(workspaceId)
                                        .userName(userName)
                                        .build())
                                .toList();

                        onlineScorePublisher.enqueueMessage(messages, rule.getType());
                        log.info("Enqueued '{}' span LLM as Judge messages for rule '{}'", messages.size(),
                                rule.getId());
                    });

                    // Enqueue Span Python evaluations (if enabled)
                    spanPythonRules.forEach(rule -> {
                        if (!serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()) {
                            log.warn("Span Python evaluator is disabled, skipping rule '{}'", rule.getId());
                            return;
                        }

                        List<SpanToScoreUserDefinedMetricPython> messages = spans.stream()
                                .map(span -> SpanToScoreUserDefinedMetricPython.builder()
                                        .span(span.toBuilder().projectName(project.name()).build())
                                        .ruleId(rule.getId())
                                        .ruleName(rule.getName())
                                        .code(rule.getCode())
                                        .workspaceId(workspaceId)
                                        .userName(userName)
                                        .build())
                                .toList();

                        onlineScorePublisher.enqueueMessage(messages, rule.getType());
                        log.info("Enqueued '{}' span Python messages for rule '{}'", messages.size(),
                                rule.getId());
                    });

                    return Mono.just(spanIds.size());
                });
    }

    /**
     * Evaluates threads by enqueueing evaluation messages for each rule.
     * Resolves thread model IDs to thread ID strings before enqueueing.
     */
    private Mono<Integer> evaluateThreads(List<UUID> threadModelIds, List<AutomationRuleEvaluator<?, ?>> rules,
            UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' threads with '{}' rules", threadModelIds.size(), rules.size());

        // Fetch thread IDs from thread model IDs
        return traceThreadService.getThreadIdsByThreadModelIds(threadModelIds)
                .flatMap(threadModelIdToThreadIdMap -> {
                    if (threadModelIdToThreadIdMap == null || threadModelIdToThreadIdMap.isEmpty()) {
                        log.warn("No thread IDs found to enqueue for thread evaluation");
                        return Mono.just(0);
                    }

                    log.info("Successfully resolved '{}' thread IDs for thread evaluation",
                            threadModelIdToThreadIdMap.size());

                    // Extract thread ID strings
                    List<String> threadIds = List.copyOf(threadModelIdToThreadIdMap.values());

                    // Enqueue messages for each rule
                    rules.forEach(rule -> {
                        log.info("Enqueueing evaluation for rule '{}' with '{}' thread IDs", rule.getId(),
                                threadIds.size());
                        onlineScorePublisher.enqueueThreadMessage(threadIds, rule.getId(), projectId, workspaceId,
                                userName);
                    });

                    return Mono.just(threadModelIds.size());
                });
    }
}
