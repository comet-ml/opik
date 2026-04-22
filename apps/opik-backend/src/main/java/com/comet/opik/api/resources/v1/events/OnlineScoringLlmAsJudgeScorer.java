package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ExperimentStatus;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.domain.AssertionCounterService;
import com.comet.opik.domain.ExperimentService;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

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
    private final AssertionCounterService assertionCounterService;
    private final ExperimentService experimentService;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull ExperimentService experimentService,
            @NonNull AssertionCounterService assertionCounterService,
            @NonNull LlmProviderFactory llmProviderFactory) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.assertionCounterService = assertionCounterService;
        this.experimentService = experimentService;
    }

    @Override
    protected Mono<Void> processEvent(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            return Mono.fromRunnable(() -> score(message))
                    .thenReturn(true)
                    .onErrorResume(e -> {
                        log.error("Failed to score assertion for experiment '{}'", experimentId, e);
                        return Mono.just(false);
                    })
                    .flatMap(success -> decrementAssertionCounter(experimentId, message));
        }
        return super.processEvent(message);
    }

    private Mono<Void> decrementAssertionCounter(UUID experimentId, TraceToScoreLlmAsJudge message) {
        return assertionCounterService.decrement(experimentId)
                .flatMap(remaining -> {
                    if (remaining <= 0) {
                        log.info("Assertion counter reached zero for experiment '{}', finishing", experimentId);
                        return finishExperimentAfterAssertions(experimentId, message);
                    }
                    return Mono.empty();
                })
                .then();
    }

    private Mono<Void> finishExperimentAfterAssertions(UUID experimentId, TraceToScoreLlmAsJudge message) {
        var statusUpdate = ExperimentUpdate.builder()
                .status(ExperimentStatus.COMPLETED)
                .build();

        return experimentService.update(experimentId, statusUpdate)
                .then(experimentService.finishExperiments(Set.of(experimentId)))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()))
                .doOnSuccess(unused -> log.info("Finished experiment '{}' after all assertions completed",
                        experimentId))
                .onErrorResume(error -> {
                    log.error("Failed to finish experiment '{}' after assertions", experimentId, error);
                    return Mono.empty();
                });
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
                // When scoreNameMapping is empty (regular online scoring), names pass through unchanged.
                List<FeedbackScoreBatchItem> scores = OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
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
                var loggedScores = storeScores(scores, trace, message.userName(), message.workspaceId());
                userFacingLogger.info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while storing scores for traceId '{}'", trace.id());
                throw exception;
            }
        }
    }
}
