package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import org.slf4j.MDC;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toList;

/**
 * This service listens a Redis stream for Traces to be scored in a LLM provider. It will prepare the LLM request
 * by rendering message templates using values from the Trace and prepare the schema for the return (structured output).
 */
@EagerSingleton
@Slf4j
public class OnlineScoringUserDefinedMetricPythonScorer
        extends
            OnlineScoringBaseScorer<TraceToScoreUserDefinedMetricPython> {

    private final ChatCompletionService aiProxyService;
    private final FeedbackScoreService feedbackScoreService;
    private final Logger userFacingLogger;

    @Inject
    public OnlineScoringUserDefinedMetricPythonScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull FeedbackScoreService feedbackScoreService) {
        super(config, redisson, AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON);
        this.aiProxyService = aiProxyService;
        this.feedbackScoreService = feedbackScoreService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringUserDefinedMetricPythonScorer.class);
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with Trace to score with an Evaluator code, workspace and username
     */
    protected void score(TraceToScoreUserDefinedMetricPython message) {
        var trace = message.trace();

        // This is crucial for logging purposes to identify the rule and trace
        try (var logScope = MDC.putCloseable(UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name());
                var workspaceScope = MDC.putCloseable("workspace_id", message.workspaceId());
                var traceScope = MDC.putCloseable("trace_id", trace.id().toString());
                var ruleScope = MDC.putCloseable("rule_id", message.ruleId().toString())) {

            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                scoreRequest = OnlineScoringEngine.prepareLlmRequest(message.llmAsJudgeCode(), trace);
            } catch (Exception e) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}'", trace.id());
                throw e;
            }

            userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}", trace.id(),
                    scoreRequest);

            ChatResponse chatResponse;
            try {
                chatResponse = aiProxyService.scoreTrace(scoreRequest, message.llmAsJudgeCode().model(),
                        message.workspaceId());
                userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);
            } catch (Exception e) {
                userFacingLogger.error("Unexpected error while scoring traceId '{}' with rule '{}'", trace.id(),
                        message.ruleName());
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

                log.info("Received {} scores for traceId '{}' in workspace '{}'. Storing them.", scores.size(),
                        trace.id(),
                        message.workspaceId());

                feedbackScoreService.scoreBatchOfTraces(scores)
                        .contextWrite(
                                ctx -> ctx.put(RequestContext.USER_NAME, message.userName())
                                        .put(RequestContext.WORKSPACE_ID, message.workspaceId()))
                        .block();

                Map<String, List<BigDecimal>> loggedScores = scores
                        .stream()
                        .collect(groupingBy(FeedbackScoreBatchItem::name,
                                mapping(FeedbackScoreBatchItem::value, toList())));

                userFacingLogger.info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores);

            } catch (Exception e) {
                userFacingLogger.error("Unexpected error while storing scores for traceId '{}'", trace.id());
                throw e;
            }
        }
    }
}