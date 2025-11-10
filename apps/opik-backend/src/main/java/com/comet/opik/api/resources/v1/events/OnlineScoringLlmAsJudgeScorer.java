package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * This service listens a Redis stream for Traces to be scored in a LLM provider. It will prepare the LLM request
 * by rendering message templates using values from the Trace and prepare the schema for the return (structured output).
 */
@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceToScoreLlmAsJudge> {

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull LlmProviderFactory llmProviderFactory) {
        super(config, redisson, feedbackScoreService, traceService, LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with the Trace to score with an Evaluator code, workspace and username.
     */
    @Override
    protected void score(@NonNull TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}', to be scored in '{}'",
                trace.id(), message.userName(), message.llmAsJudgeCode().model().name());

        // This is crucial for logging purposes to identify the rule and trace
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString()))) {

            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                String modelName = message.llmAsJudgeCode().model().name();
                var llmProvider = llmProviderFactory.getLlmProvider(modelName);
                var strategy = StructuredOutputStrategy.getStrategy(llmProvider, modelName);
                scoreRequest = OnlineScoringEngine.prepareLlmRequest(message.llmAsJudgeCode(), trace, strategy);
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }

            userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}",
                    trace.id(), scoreRequest);

            ChatResponse chatResponse;
            try {
                chatResponse = aiProxyService.scoreTrace(
                        scoreRequest, message.llmAsJudgeCode().model(), message.workspaceId());
                userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);
            } catch (Exception exception) {
                String errorMessage = Optional.ofNullable(exception.getCause())
                        .map(Throwable::getMessage)
                        .orElse(exception.getMessage());

                userFacingLogger.error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                        trace.id(), message.ruleName(), errorMessage);
                throw exception;
            }

            try {
                List<FeedbackScoreBatchItem> scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                        .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                                .id(trace.id())
                                .projectId(trace.projectId())
                                .projectName(trace.projectName())
                                .build())
                        .toList();
                var loggedScores = storeScores(scores, trace, message.userName(), message.workspaceId());
                userFacingLogger.info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while storing scores for traceId '{}'", trace.id());
                throw exception;
            }
        }
    }
}
