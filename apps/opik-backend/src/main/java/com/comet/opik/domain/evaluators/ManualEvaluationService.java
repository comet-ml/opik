package com.comet.opik.domain.evaluators;

import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.domain.TraceService;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

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
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ManualEvaluationServiceImpl implements ManualEvaluationService {

    private final @NonNull TraceService traceService;
    private final @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final @NonNull OnlineScorePublisher onlineScorePublisher;

    @Override
    public Mono<ManualEvaluationResponse> evaluate(@NonNull ManualEvaluationRequest request, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName) {

        log.info(
                "Starting manual evaluation for '{}' entities of type '{}' with '{}' rules in project '{}', workspace '{}'",
                request.entityIds().size(), request.entityType(), request.ruleIds().size(), projectId, workspaceId);

        // Validate and fetch automation rules
        return validateAndFetchRules(request.ruleIds(), projectId, workspaceId)
                .flatMap(rules -> {
                    // Process based on entity type
                    return switch (request.entityType()) {
                        case TRACE -> evaluateTraces(request.entityIds(), rules, projectId, workspaceId, userName);
                        case THREAD ->
                            evaluateThreads(request.entityIds(), rules, projectId, workspaceId, userName);
                    };
                })
                .map(evaluatedCount -> {
                    log.info(
                            "Successfully queued '{}' entities for evaluation with '{}' rules in project '{}', workspace '{}'",
                            evaluatedCount, request.ruleIds().size(), projectId, workspaceId);
                    return ManualEvaluationResponse.of(evaluatedCount, request.ruleIds().size());
                });
    }

    /**
     * Validates and fetches all automation rules specified in the request.
     */
    private Mono<List<AutomationRuleEvaluator<?>>> validateAndFetchRules(List<UUID> ruleIds, UUID projectId,
            String workspaceId) {
        return Mono.defer(() -> {
            try {
                List<AutomationRuleEvaluator<?>> rules = ruleIds.stream()
                        .<AutomationRuleEvaluator<?>>map(ruleId -> {
                            try {
                                return automationRuleEvaluatorService.findById(ruleId, projectId, workspaceId);
                            } catch (NotFoundException ex) {
                                log.warn("Rule with ID '{}' not found for projectId '{}' and workspaceId '{}'", ruleId,
                                        projectId, workspaceId, ex);
                                throw new BadRequestException(
                                        "Automation rule not found with ID: '%s'".formatted(ruleId));
                            }
                        })
                        .toList();

                // Validate that all rules are TraceThread type (support for both LLM_AS_JUDGE and USER_DEFINED_METRIC_PYTHON)
                boolean allRulesValid = rules.stream()
                        .allMatch(rule -> rule instanceof AutomationRuleEvaluatorTraceThreadLlmAsJudge
                                || rule instanceof AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython);

                if (!allRulesValid) {
                    throw new BadRequestException(
                            "All automation rules must be of type TraceThread (LLM_AS_JUDGE or USER_DEFINED_METRIC_PYTHON)");
                }

                return Mono.just(rules);
            } catch (BadRequestException ex) {
                return Mono.error(ex);
            } catch (Exception ex) {
                log.error("Error validating automation rules", ex);
                return Mono.error(new BadRequestException("Failed to validate automation rules: " + ex.getMessage()));
            }
        });
    }

    /**
     * Evaluates traces by fetching them and enqueueing evaluation messages for each rule.
     */
    private Mono<Integer> evaluateTraces(List<UUID> traceIds, List<AutomationRuleEvaluator<?>> rules, UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' traces with '{}' rules", traceIds.size(), rules.size());

        // Fetch all traces to validate they exist
        return Mono.fromCallable(() -> traceIds.stream()
                .map(traceId -> {
                    try {
                        Trace trace = traceService.get(traceId).block();
                        if (trace == null) {
                            throw new BadRequestException("Trace not found with ID: '%s'".formatted(traceId));
                        }
                        // Return the threadId from the trace for enqueueing
                        String threadId = trace.threadId();
                        if (threadId == null || threadId.isBlank()) {
                            throw new BadRequestException(
                                    "Trace with ID '%s' does not have a thread ID and cannot be evaluated"
                                            .formatted(traceId));
                        }
                        return threadId;
                    } catch (BadRequestException ex) {
                        throw ex;
                    } catch (Exception ex) {
                        log.error("Error fetching trace with ID: '{}'", traceId, ex);
                        throw new BadRequestException("Failed to fetch trace with ID: '%s'".formatted(traceId));
                    }
                })
                .collect(Collectors.toSet()))
                .flatMap(threadIds -> {
                    // Enqueue messages for each rule
                    rules.forEach(rule -> {
                        log.info("Enqueueing evaluation for rule '{}' with '{}' thread IDs", rule.getId(),
                                threadIds.size());
                        onlineScorePublisher.enqueueThreadMessage(List.copyOf(threadIds), rule.getId(), projectId,
                                workspaceId, userName);
                    });

                    return Mono.just(traceIds.size());
                });
    }

    /**
     * Evaluates threads by fetching them and enqueueing evaluation messages for each rule.
     */
    private Mono<Integer> evaluateThreads(List<UUID> threadIds, List<AutomationRuleEvaluator<?>> rules, UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' threads with '{}' rules", threadIds.size(), rules.size());

        // Convert UUIDs to strings for thread model IDs
        Set<String> threadModelIds = threadIds.stream()
                .map(UUID::toString)
                .collect(Collectors.toSet());

        // Fetch minimal thread info to validate they exist
        return traceService.getMinimalThreadInfoByIds(projectId, threadModelIds)
                .flatMap(threads -> {
                    if (threads.size() != threadIds.size()) {
                        Set<String> foundThreadIds = threads.stream()
                                .map(TraceThread::threadModelId)
                                .map(UUID::toString)
                                .collect(Collectors.toSet());

                        Set<String> missingThreadIds = threadModelIds.stream()
                                .filter(id -> !foundThreadIds.contains(id))
                                .collect(Collectors.toSet());

                        log.warn("Some thread IDs were not found: '{}'", missingThreadIds);
                        throw new BadRequestException(
                                "Thread(s) not found with ID(s): %s".formatted(String.join(", ", missingThreadIds)));
                    }

                    // Extract thread IDs (not thread model IDs) for enqueueing
                    List<String> threadIdsForEvaluation = threads.stream()
                            .map(TraceThread::id)
                            .toList();

                    // Enqueue messages for each rule
                    rules.forEach(rule -> {
                        log.info("Enqueueing evaluation for rule '{}' with '{}' thread IDs", rule.getId(),
                                threadIdsForEvaluation.size());
                        onlineScorePublisher.enqueueThreadMessage(threadIdsForEvaluation, rule.getId(), projectId,
                                workspaceId, userName);
                    });

                    return Mono.just(threadIds.size());
                });
    }
}
