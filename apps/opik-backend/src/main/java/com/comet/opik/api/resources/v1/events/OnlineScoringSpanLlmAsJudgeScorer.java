package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluation.EvaluatedSubject;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
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
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;

    @Inject
    public OnlineScoringSpanLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder) {
        super(config, redisson, feedbackScoreService, traceService, SPAN_LLM_AS_JUDGE, Constants.SPAN_LLM_AS_JUDGE);
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSpanLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
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
    protected Mono<Void> score(@NonNull SpanToScoreLlmAsJudge message) {
        var span = message.span();
        log.info("Message received with spanId '{}', userName '{}', to be scored in '{}'",
                span.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.SPAN_ID, span.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        // Monitoring recorder (OPIK-6994): one hidden evaluator trace per span evaluation with an llm
        // span for the scoring call. NOOP when the toggle is off.
        EvaluationRecorder recorder = serviceTogglesConfig.isOnlineScoringTracingEnabled()
                ? onlineEvaluationRecorder.begin(EvaluatedSubject.ofSpan(span),
                        message.ruleId(), message.ruleName(), message.llmAsJudgeCode().model().name(),
                        message.workspaceId(), message.userName())
                : EvaluationRecorder.NOOP;

        Mono<List<FeedbackScoreBatchItem>> scoring = Mono.fromCallable(() -> prepareSpanRequest(message, mdc))
                .subscribeOn(Schedulers.boundedElastic())
                // Uniform structure with trace/thread evals: a prepare_evaluation span carrying the
                // evaluated span's preview. Span evals never fetch context or go agentic (inline,
                // single call), hence 0 fetched spans / 0 estimate.
                .flatMap(scoreRequest -> {
                    recorder.recordPreparation(0, 0, false);
                    return recorder.recordLlmCall(scoreRequest,
                            Mono.fromCallable(() -> aiProxyService.scoreTrace(scoreRequest,
                                    message.llmAsJudgeCode().model(), message.workspaceId()))
                                    .subscribeOn(Schedulers.boundedElastic()));
                })
                .map(response -> parseSpanScores(response, message, mdc));

        return recorder.monitor(scoring)
                .flatMap(scores -> storeSpanScores(scores, span, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for spanId '{}' stored successfully:\n\n{}", span.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring spanId '{}' with rule '{}': \n\n{}",
                                span.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    private ChatRequest prepareSpanRequest(SpanToScoreLlmAsJudge message, Map<String, String> mdc) {
        var span = message.span();
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating spanId '{}' sampled by rule '{}'", span.id(), message.ruleName());
            ChatRequest scoreRequest;
            try {
                String modelName = message.llmAsJudgeCode().model().name();
                var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(message.llmAsJudgeCode(), span, strategy);
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for spanId '{}': \n\n{}",
                        span.id(), exception.getMessage());
                throw exception;
            }
            userFacingLogger.info("Sending spanId '{}' to LLM using the following input:\n\n{}",
                    span.id(), scoreRequest);
            return scoreRequest;
        }
    }

    private List<FeedbackScoreBatchItem> parseSpanScores(ChatResponse score, SpanToScoreLlmAsJudge message,
            Map<String, String> mdc) {
        var span = message.span();
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Received response for spanId '{}':\n\n{}", span.id(), score);
            var parsed = OnlineScoringEngine.toFeedbackScores(score);
            OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "spanId", span.id());
            return parsed.scores().stream()
                    .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                            .id(span.id())
                            .projectId(span.projectId())
                            .projectName(span.projectName())
                            .build())
                    .toList();
        }
    }
}
