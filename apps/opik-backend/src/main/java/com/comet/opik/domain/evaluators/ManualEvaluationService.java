package com.comet.opik.domain.evaluators;

import com.comet.opik.api.ManualEvaluationRequest;
import com.comet.opik.api.ManualEvaluationResponse;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

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
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class ManualEvaluationServiceImpl implements ManualEvaluationService {

    private final @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final @NonNull OnlineScorePublisher onlineScorePublisher;

    @Override
    public Mono<ManualEvaluationResponse> evaluate(@NonNull ManualEvaluationRequest request, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName) {

        log.info(
                "Starting manual evaluation for '{}' entities of type '{}' with '{}' rules in project '{}', workspace '{}'",
                request.entityIds().size(), request.entityType(), request.ruleIds().size(), projectId, workspaceId);

        return Mono.fromCallable(() -> {
            Set<UUID> ruleIdSet = new HashSet<>(request.ruleIds());
            List<?> rawRules = automationRuleEvaluatorService.findByIds(ruleIdSet, projectId, workspaceId);
            @SuppressWarnings("unchecked")
            List<AutomationRuleEvaluator<?>> rules = (List<AutomationRuleEvaluator<?>>) rawRules;

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
     * Does not validate trace existence - evaluation will fail later if traces don't exist.
     */
    private Mono<Integer> evaluateTraces(List<UUID> traceIds, List<AutomationRuleEvaluator<?>> rules, UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' traces with '{}' rules", traceIds.size(), rules.size());

        return Mono.fromCallable(() -> {
            // Convert trace IDs to strings for enqueueing
            List<String> traceIdStrings = traceIds.stream()
                    .map(UUID::toString)
                    .toList();

            // Enqueue messages for each rule
            rules.forEach(rule -> {
                log.info("Enqueueing evaluation for rule '{}' with '{}' trace IDs", rule.getId(),
                        traceIdStrings.size());
                onlineScorePublisher.enqueueThreadMessage(traceIdStrings, rule.getId(), projectId, workspaceId,
                        userName);
            });

            return traceIds.size();
        });
    }

    /**
     * Evaluates threads by enqueueing evaluation messages for each rule.
     * Does not validate thread existence - evaluation will fail later if threads don't exist.
     */
    private Mono<Integer> evaluateThreads(List<UUID> threadIds, List<AutomationRuleEvaluator<?>> rules, UUID projectId,
            String workspaceId, String userName) {
        log.info("Evaluating '{}' threads with '{}' rules", threadIds.size(), rules.size());

        return Mono.fromCallable(() -> {
            // Convert thread model IDs to strings for enqueueing
            List<String> threadIdStrings = threadIds.stream()
                    .map(UUID::toString)
                    .toList();

            // Enqueue messages for each rule
            rules.forEach(rule -> {
                log.info("Enqueueing evaluation for rule '{}' with '{}' thread IDs", rule.getId(),
                        threadIdStrings.size());
                onlineScorePublisher.enqueueThreadMessage(threadIdStrings, rule.getId(), projectId, workspaceId,
                        userName);
            });

            return threadIds.size();
        });
    }
}
