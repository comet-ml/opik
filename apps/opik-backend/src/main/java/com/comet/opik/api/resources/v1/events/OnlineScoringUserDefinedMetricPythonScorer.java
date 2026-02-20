package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.events.TraceToScoreUserDefinedMetricPython;
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
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringUserDefinedMetricPythonScorer
        extends
            OnlineScoringBaseScorer<TraceToScoreUserDefinedMetricPython> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final PythonEvaluatorService pythonEvaluatorService;
    private final Logger userFacingLogger;

    @Inject
    public OnlineScoringUserDefinedMetricPythonScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull TraceService traceService,
            @NonNull PythonEvaluatorService pythonEvaluatorService) {
        super(config, redisson, feedbackScoreService, traceService, USER_DEFINED_METRIC_PYTHON,
                Constants.USER_DEFINED_METRIC_PYTHON);
        this.pythonEvaluatorService = pythonEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringUserDefinedMetricPythonScorer.class);
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isPythonEvaluatorEnabled()) {
            super.start();
        } else {
            log.warn("Online Scoring Python evaluator consumer won't start as it is disabled.");
        }
    }

    @Override
    protected void score(@NonNull TraceToScoreUserDefinedMetricPython message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}'", trace.id(), message.userName());

        // This is crucial for logging purposes to identify the rule and trace
        try (var logContext = wrapWithMdc(Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString()))) {

            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            Map<String, Object> data;
            try {
                data = OnlineScoringDataExtractor.preparePythonEvaluatorData(message.code().arguments(), trace);
            } catch (IllegalArgumentException exception) {
                userFacingLogger.error("Error preparing Python request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage(), exception);
                throw exception;
            }

            userFacingLogger.info("Sending traceId '{}' to Python evaluator using the following input:\n\n{}",
                    trace.id(), data);

            List<PythonScoreResult> scoreResults;
            try {
                scoreResults = pythonEvaluatorService.evaluate(message.code().metric(), data);
                userFacingLogger.info("Received response for traceId '{}':\n\n{}", trace.id(), scoreResults);
            } catch (Exception exception) {
                userFacingLogger.error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                        trace.id(), message.ruleName(), exception.getMessage());
                throw exception;
            }

            try {
                List<FeedbackScoreBatchItem> scores = scoreResults.stream()
                        .map(scoreResult -> (FeedbackScoreBatchItem) FeedbackScoreBatchItem.builder()
                                .id(trace.id())
                                .projectName(trace.projectName())
                                .projectId(trace.projectId())
                                .name(scoreResult.name())
                                .value(scoreResult.value())
                                .reason(scoreResult.reason())
                                .source(ScoreSource.ONLINE_SCORING)
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
