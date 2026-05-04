package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.USER_DEFINED_METRIC_PYTHON;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
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
    protected Mono<Void> score(@NonNull TraceToScoreUserDefinedMetricPython message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}'", trace.id(), message.userName());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        return Mono.fromCallable(() -> prepareData(message, mdc))
                .flatMap(data -> pythonEvaluatorService.evaluate(message.code().metric(), data))
                .doOnNext(withMdc(mdc, scoreResults -> userFacingLogger
                        .info("Received response for traceId '{}':\n\n{}", trace.id(), scoreResults)))
                .flatMap(scoreResults -> storeScores(toFeedbackScores(scoreResults, trace), trace,
                        message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                                trace.id(), message.ruleName(), error.getMessage())))
                .then();
    }

    private Map<String, String> prepareData(TraceToScoreUserDefinedMetricPython message, Map<String, String> mdc) {
        var trace = message.trace();
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());
            try {
                var data = OnlineScoringEngine.toReplacements(message.code().arguments(), trace);
                userFacingLogger.info("Sending traceId '{}' to Python evaluator using the following input:\n\n{}",
                        trace.id(), data);
                return data;
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing Python request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }
        }
    }

    private static List<FeedbackScoreBatchItem> toFeedbackScores(List<PythonScoreResult> scoreResults, Trace trace) {
        return scoreResults.stream()
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
    }
}
