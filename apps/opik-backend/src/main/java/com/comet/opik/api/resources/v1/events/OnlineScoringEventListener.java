package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import jakarta.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.Collectors;

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
     *
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

        log.debug("[OnlineScoring] Received traces for workspace '{}': {}", tracesBatch.workspaceId(), countMap);

        Random random = new Random(System.currentTimeMillis());

        // fetch automation rules per project
        tracesByProject.forEach((projectId, traces) -> {
            log.debug("[OnlineScoring] Fetching evaluators for {} traces, project '{}' on workspace '{}'",
                    traces.size(), projectId, tracesBatch.workspaceId());
            List<AutomationRuleEvaluatorLlmAsJudge> evaluators = ruleEvaluatorService.findAll(
                    projectId, tracesBatch.workspaceId(), AutomationRuleEvaluatorType.LLM_AS_JUDGE);
            log.info("[OnlineScoring] Found {} evaluators for project '{}' on workspace '{}'", evaluators.size(),
                    projectId, tracesBatch.workspaceId());

            // for each rule, sample traces and score them
            evaluators.forEach(evaluator -> traces.stream()
                    .filter(e -> random.nextFloat() < evaluator.getSamplingRate())
                    .forEach(trace -> score(trace, tracesBatch.workspaceId(), evaluator)));
        });
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param trace the trace to score
     * @param workspaceId the workspace the trace belongs
     * @param evaluator the automation rule to score the trace
     */
    private void score(Trace trace, String workspaceId, AutomationRuleEvaluatorLlmAsJudge evaluator) {
        // TODO prepare base request
        var baseRequestBuilder = ChatCompletionRequest.builder()
                .model(evaluator.getCode().model().name())
                .temperature(evaluator.getCode().model().temperature())
                .messages(LlmAsJudgeMessageRender.renderMessages(trace, evaluator.getCode()))
                .build();

        // TODO: call AI Proxy and parse response into 1+ FeedbackScore

        // TODO: store FeedbackScores
    }

}
