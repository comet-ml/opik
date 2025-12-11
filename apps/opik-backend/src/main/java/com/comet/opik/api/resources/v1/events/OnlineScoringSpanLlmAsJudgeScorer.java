package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.StructuredOutputStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
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
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * This service listens to a Redis stream for Spans to be scored in a LLM provider. It will prepare the LLM request
 * by rendering message templates using values from the Span and prepare the schema for the return (structured output).
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSpanLlmAsJudgeScorer extends OnlineScoringBaseScorer<SpanToScoreLlmAsJudge> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;

    @Inject
    public OnlineScoringSpanLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull LlmProviderFactory llmProviderFactory) {
        super(config, redisson, feedbackScoreService, traceService, SPAN_LLM_AS_JUDGE, Constants.SPAN_LLM_AS_JUDGE);
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSpanLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isSpanLlmAsJudgeEnabled()) {
            super.start();
        } else {
            log.info("Online Scoring Span LLM as Judge consumer won't start as it is disabled");
        }
    }

    /**
     * Use AI Proxy to score the span and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with the Span to score with an Evaluator code, workspace and username.
     */
    @Override
    protected void score(@NonNull SpanToScoreLlmAsJudge message) {
        var span = message.span();
        log.info("Message received with spanId '{}', userName '{}', to be scored in '{}'",
                span.id(), message.userName(), message.llmAsJudgeCode().model().name());

        // This is crucial for logging purposes to identify the rule and span
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.SPAN_ID, span.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString()))) {

            userFacingLogger.info("Evaluating spanId '{}' sampled by rule '{}'", span.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                String modelName = message.llmAsJudgeCode().model().name();
                var llmProvider = llmProviderFactory.getLlmProvider(modelName);
                var strategy = StructuredOutputStrategy.getStrategy(llmProvider, modelName);
                scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                        message.llmAsJudgeCode(), span, strategy);
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for spanId '{}': \n\n{}",
                        span.id(), exception.getMessage());
                throw exception;
            }

            userFacingLogger.info("Sending spanId '{}' to LLM using the following input:\n\n{}",
                    span.id(), scoreRequest);

            ChatResponse score;
            try {
                // Reuse scoreTrace method as it's generic enough - it just takes a ChatRequest and calls the LLM
                score = aiProxyService.scoreTrace(
                        scoreRequest, message.llmAsJudgeCode().model(), message.workspaceId());
                userFacingLogger.info("Received response for spanId '{}':\n\n{}", span.id(), score);
            } catch (Exception exception) {
                String errorMessage = Optional.ofNullable(exception.getCause())
                        .map(Throwable::getMessage)
                        .orElse(exception.getMessage());

                userFacingLogger.error("Unexpected error while scoring spanId '{}' with rule '{}': \n\n{}",
                        span.id(), message.ruleName(), errorMessage);
                throw exception;
            }

            try {
                List<FeedbackScoreBatchItem> scores = OnlineScoringEngine.toFeedbackScores(score).stream()
                        .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                                .id(span.id())
                                .projectId(span.projectId())
                                .projectName(span.projectName())
                                .build())
                        .toList();
                var loggedScores = storeSpanScores(scores, span, message.userName(), message.workspaceId());
                userFacingLogger.info("Scores for spanId '{}' stored successfully:\n\n{}", span.id(), loggedScores);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while storing scores for spanId '{}'", span.id());
                throw exception;
            }
        }
    }
}
