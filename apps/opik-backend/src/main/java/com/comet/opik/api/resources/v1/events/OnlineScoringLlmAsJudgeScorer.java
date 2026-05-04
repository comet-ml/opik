package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
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
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull LlmProviderFactory llmProviderFactory) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
    }

    @Override
    protected Mono<Void> processEvent(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            return super.processEvent(message)
                    .then(testSuiteAssertionCounterService.decrementAndFinishIfComplete(
                            message.workspaceId(), experimentId)
                            .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                    .put(RequestContext.USER_NAME, message.userName())));
        }
        return super.processEvent(message);
    }

    /**
     * Use AI Proxy to score the trace and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with the Trace to score with an Evaluator code, workspace and username.
     */
    @Override
    protected Mono<Void> score(@NonNull TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}', to be scored in '{}'",
                trace.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        return Mono.fromCallable(() -> evaluate(message, mdc))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(scores -> storeScores(scores, trace, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                                trace.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    private List<FeedbackScoreBatchItem> evaluate(TraceToScoreLlmAsJudge message, Map<String, String> mdc) {
        var trace = message.trace();
        // This is crucial for logging purposes to identify the rule and trace
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            ChatRequest scoreRequest;
            try {
                String modelName = message.llmAsJudgeCode().model().name();
                var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                        message.llmAsJudgeCode(), trace, strategy, message.promptType());
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }

            userFacingLogger.info("Sending traceId '{}' to LLM using the following input:\n\n{}",
                    trace.id(), scoreRequest);

            var chatResponse = aiProxyService.scoreTrace(
                    scoreRequest, message.llmAsJudgeCode().model(), message.workspaceId());
            userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), chatResponse);

            // When scoreNameMapping is empty (regular online scoring), names pass through unchanged.
            return OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                    .map(item -> {
                        String scoreName = item.name();
                        if (message.scoreNameMapping().containsKey(scoreName)) {
                            scoreName = message.scoreNameMapping().get(scoreName);
                        }
                        return (FeedbackScoreBatchItem) item.toBuilder()
                                .name(scoreName)
                                .categoryName(message.categoryName())
                                .id(trace.id())
                                .projectId(trace.projectId())
                                .projectName(trace.projectName())
                                .build();
                    })
                    .toList();
        }
    }
}
