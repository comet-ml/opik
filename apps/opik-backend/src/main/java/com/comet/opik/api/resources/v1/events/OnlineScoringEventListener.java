package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.domain.AutomationRuleEvaluatorService;
import com.comet.opik.domain.ChatCompletionService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.UserLog;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.google.common.eventbus.EventBus;
import com.google.common.eventbus.Subscribe;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import org.slf4j.MDC;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;

@EagerSingleton
@Slf4j
public class OnlineScoringEventListener {

    private final AutomationRuleEvaluatorService ruleEvaluatorService;
    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;
    private final Logger userFacingLogger;

    @Inject
    public OnlineScoringEventListener(@NonNull EventBus eventBus,
            @NonNull AutomationRuleEvaluatorService ruleEvaluatorService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull FeedbackScoreService feedbackScoreService) {
        this.ruleEvaluatorService = ruleEvaluatorService;
        this.aiProxyService = aiProxyService;
        this.feedbackScoreService = feedbackScoreService;
        eventBus.register(this);
        userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringEventListener.class);
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
                .collect(groupingBy(Trace::projectId));

        Map<String, Integer> countMap = tracesByProject.entrySet().stream()
                .collect(toMap(entry -> "projectId: " + entry.getKey(),
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

            // Important to set the workspaceId for logging purposes
            try (MDC.MDCCloseable logScope = MDC.putCloseable(UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name());
                    MDC.MDCCloseable scope = MDC.putCloseable("workspace_id", tracesBatch.workspaceId())) {

                // for each rule, sample traces and score them
                evaluators.forEach(evaluator -> traces.stream()
                        .filter(trace -> {
                            boolean sampled = random.nextFloat() < evaluator.getSamplingRate();

                            if (!sampled) {
                                MDC.put("rule_id", evaluator.getId().toString());
                                MDC.put("trace_id", trace.id().toString());

                                userFacingLogger.info(
                                        "The traceId '{}' was skipped for rule: '{}' and per the sampling rate '{}'",
                                        trace.id(), evaluator.getName(), evaluator.getSamplingRate());
                            }

                            return sampled;
                        })
                        .forEach(trace -> score(trace, evaluator, tracesBatch.workspaceId(),
                                tracesBatch.userName())));
            }

        });
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param trace         the trace to score
     * @param evaluator     the automation rule to score the trace
     * @param workspaceId   the workspace the trace belongs
     */
    private void score(Trace trace, AutomationRuleEvaluatorLlmAsJudge evaluator, String workspaceId,
            String userName) {

        //This is crucial for logging purposes to identify the rule and trace
        try (var ruleScope = MDC.putCloseable("rule_id", evaluator.getId().toString());
                var traceScope = MDC.putCloseable("trace_id", trace.id().toString())) {

            processScores(trace, evaluator, workspaceId, userName);
        }
    }

    private void processScores(Trace trace, AutomationRuleEvaluatorLlmAsJudge evaluator, String workspaceId,
            String userName) {
        userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), evaluator.getName());

        var scoreRequest = OnlineScoringEngine.prepareLlmRequest(evaluator.getCode(), trace);

        userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}", trace.id(), scoreRequest);

        final ChatResponse chatResponse;

        try {
            chatResponse = aiProxyService.scoreTrace(scoreRequest, evaluator.getCode().model(), workspaceId);
            userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);
        } catch (Exception e) {
            userFacingLogger.error("Unexpected error while scoring traceId '{}' with rule '{}'", trace.id(),
                    evaluator.getName());
            throw e;
        }

        try {
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

            Map<String, List<BigDecimal>> loggedScores = scores
                    .stream()
                    .collect(
                            groupingBy(FeedbackScoreBatchItem::name, mapping(FeedbackScoreBatchItem::value, toList())));

            userFacingLogger.info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores);
        } catch (Exception e) {
            userFacingLogger.error("Unexpected error while storing scores for traceId '{}'", trace.id());
            throw e;
        }
    }

}
