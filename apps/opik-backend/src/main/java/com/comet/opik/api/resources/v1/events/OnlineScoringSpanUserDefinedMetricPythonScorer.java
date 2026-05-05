package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.events.SpanToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
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

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * This service listens to a Redis stream for Spans to be scored using Python evaluators. It will prepare the Python
 * request by rendering template variables using values from the Span (span.input, span.output, span.metadata) and
 * execute the Python metric code to generate scores.
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSpanUserDefinedMetricPythonScorer
        extends
            OnlineScoringBaseScorer<SpanToScoreUserDefinedMetricPython> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final PythonEvaluatorService pythonEvaluatorService;
    private final Logger userFacingLogger;

    @Inject
    public OnlineScoringSpanUserDefinedMetricPythonScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull TraceService traceService,
            @NonNull PythonEvaluatorService pythonEvaluatorService) {
        super(config, redisson, feedbackScoreService, traceService, SPAN_USER_DEFINED_METRIC_PYTHON,
                Constants.SPAN_USER_DEFINED_METRIC_PYTHON);
        this.pythonEvaluatorService = pythonEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.userFacingLogger = UserFacingLoggingFactory
                .getLogger(OnlineScoringSpanUserDefinedMetricPythonScorer.class);
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isSpanUserDefinedMetricPythonEnabled()) {
            super.start();
        } else {
            log.warn("Online Scoring Span Python evaluator consumer won't start as it is disabled.");
        }
    }

    @Override
    protected Mono<Void> score(@NonNull SpanToScoreUserDefinedMetricPython message) {
        var span = message.span();
        log.info("Message received with spanId '{}', userName '{}'", span.id(), message.userName());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.SPAN_ID, span.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        return Mono.fromCallable(() -> prepareData(message, mdc))
                .flatMap(data -> pythonEvaluatorService.evaluate(message.code().metric(), data))
                .doOnNext(withMdc(mdc, scoreResults -> userFacingLogger
                        .info("Received response for spanId '{}':\n\n{}", span.id(), scoreResults)))
                .flatMap(scoreResults -> storeSpanScores(toFeedbackScores(scoreResults, span), span,
                        message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for spanId '{}' stored successfully:\n\n{}", span.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring spanId '{}' with rule '{}': \n\n{}",
                                span.id(), message.ruleName(), error.getMessage())))
                .then();
    }

    private Map<String, String> prepareData(SpanToScoreUserDefinedMetricPython message, Map<String, String> mdc) {
        var span = message.span();
        // This is crucial for logging purposes to identify the rule and span
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating spanId '{}' sampled by rule '{}'", span.id(), message.ruleName());
            try {
                var data = OnlineScoringEngine.toReplacements(message.code().arguments(), span);
                userFacingLogger.info("Sending spanId '{}' to Python evaluator using the following input:\n\n{}",
                        span.id(), data);
                return data;
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing Python request for spanId '{}': \n\n{}",
                        span.id(), exception.getMessage());
                throw exception;
            }
        }
    }

    private static List<FeedbackScoreBatchItem> toFeedbackScores(List<PythonScoreResult> scoreResults, Span span) {
        return scoreResults.stream()
                .map(scoreResult -> (FeedbackScoreBatchItem) FeedbackScoreBatchItem.builder()
                        .id(span.id())
                        .projectName(span.projectName())
                        .projectId(span.projectId())
                        .name(scoreResult.name())
                        .value(scoreResult.value())
                        .reason(scoreResult.reason())
                        .source(ScoreSource.ONLINE_SCORING)
                        .build())
                .toList();
    }
}
