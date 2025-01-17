package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeCode;

@EagerSingleton
@Slf4j
public class OnlineScoringEventListener {

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;

    @Inject
    public OnlineScoringEventListener(EventBus eventBus,
            AutomationRuleEvaluatorService ruleEvaluatorService,
            ChatCompletionService aiProxyService,
            FeedbackScoreService feedbackScoreService) {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.aiProxyService = aiProxyService;
        this.feedbackScoreService = feedbackScoreService;
        eventBus.register(this);
    }

    /**
     * Listen for trace batches to check for existent Automation Rules to score them.
     * <br>
     * Automation Rule registers the percentage of traces to score, how to score them and so on.
     *
     * @param tracesBatch a traces batch with workspaceId and userName
     */
    @Subscribe
    public void onTracesCreated(TracesCreated tracesBatch) {
        log.debug(tracesBatch.traces().toString());

        Map<UUID, List<Trace>> tracesByProject = tracesBatch.traces().stream()
                .collect(Collectors.groupingBy(Trace::projectId));

        Map<String, Integer> countMap = tracesByProject.entrySet().stream()
                .collect(Collectors.toMap(entry -> "projectId: " + entry.getKey(),
                        entry -> entry.getValue().size()));

        log.debug("Received traces for workspace '{}': {}", tracesBatch.workspaceId(), countMap);

        Random random = new Random(System.currentTimeMillis());

        // fetch automation rules per project
        tracesByProject.forEach((projectId, traces) -> {
            log.debug("Fetching evaluators for {} traces, project '{}' on workspace '{}'",
                    traces.size(), projectId, tracesBatch.workspaceId());
            List<AutomationRuleEvaluatorLlmAsJudge> evaluators = ruleEvaluatorService.findAll(
                    projectId, tracesBatch.workspaceId(), AutomationRuleEvaluatorType.LLM_AS_JUDGE);
            log.info("Found {} evaluators for project '{}' on workspace '{}'", evaluators.size(),
                    projectId, tracesBatch.workspaceId());

            // for each rule, sample traces and score them
            evaluators.forEach(evaluator -> traces.stream()
                    .filter(e -> random.nextFloat() < evaluator.getSamplingRate())
                    .forEach(trace -> score(trace, evaluator.getCode(), tracesBatch.workspaceId(),
                            tracesBatch.userName())));
        });
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param trace         the trace to score
     * @param evaluatorCode the automation rule to score the trace
     * @param workspaceId   the workspace the trace belongs
     */
    private void score(Trace trace, LlmAsJudgeCode evaluatorCode, String workspaceId,
            String userName) {

        var scoreRequest = OnlineScoringEngine.prepareLlmRequest(evaluatorCode, trace);

        var chatResponse = aiProxyService.scoreTrace(scoreRequest, evaluatorCode.model(), workspaceId);

        var scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                .map(item -> item.toBuilder()
                        .id(trace.id())
                        .projectId(trace.projectId())
                        .projectName(trace.projectName())
                        .build())
                .toList();

        log.info("Received {} scores for traceId '{}' in workspace '{}'. Storing them.", scores.size(), trace.id(),
                workspaceId);

        feedbackScoreService.scoreBatchOfTraces(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
    }

}
