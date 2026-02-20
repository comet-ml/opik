package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
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
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.SPAN_USER_DEFINED_METRIC_PYTHON;
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
    protected void score(@NonNull SpanToScoreUserDefinedMetricPython message) {
        var span = message.span();
        log.info("Message received with spanId '{}', userName '{}'", span.id(), message.userName());

        // This is crucial for logging purposes to identify the rule and span
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.SPAN_ID, span.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString()))) {

            userFacingLogger.info("Evaluating spanId '{}' sampled by rule '{}'", span.id(), message.ruleName());

            Map<String, Object> data;
            try {
                data = OnlineScoringDataExtractor.preparePythonEvaluatorData(message.code().arguments(), span);
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing Python request for spanId '{}'", span.id());
                throw exception;
            }

            userFacingLogger.info("Sending spanId '{}' to Python evaluator using the following input:\n\n{}",
                    span.id(), data);

            List<PythonScoreResult> scoreResults;
            try {
                scoreResults = pythonEvaluatorService.evaluate(message.code().metric(), data);
                userFacingLogger.info("Received response for spanId '{}':\n\n{}", span.id(), scoreResults);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while scoring spanId '{}' with rule '{}': \n\n{}",
                        span.id(), message.ruleName(), exception.getMessage());
                throw exception;
            }

            try {
                List<FeedbackScoreBatchItem> scores = scoreResults.stream()
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

                var loggedScores = storeSpanScores(scores, span, message.userName(), message.workspaceId());
                userFacingLogger.info("Scores for spanId '{}' stored successfully:\n\n{}", span.id(), loggedScores);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while storing scores for spanId '{}'", span.id());
                throw exception;
            }
        }
    }
}
