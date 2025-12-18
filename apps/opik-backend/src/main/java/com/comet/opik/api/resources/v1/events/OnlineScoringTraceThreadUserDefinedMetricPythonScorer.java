package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.LogContextAware;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;

@EagerSingleton
@Slf4j
public class OnlineScoringTraceThreadUserDefinedMetricPythonScorer
        extends
            OnlineScoringBaseScorer<TraceThreadToScoreUserDefinedMetricPython> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final PythonEvaluatorService pythonEvaluatorService;
    private final TraceThreadService traceThreadService;
    private final Logger userFacingLogger;
    private final ProjectService projectService;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;

    @Inject
    public OnlineScoringTraceThreadUserDefinedMetricPythonScorer(
            @NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull PythonEvaluatorService pythonEvaluatorService,
            @NonNull TraceService traceService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
                Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);
        this.pythonEvaluatorService = pythonEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.userFacingLogger = UserFacingLoggingFactory
                .getLogger(OnlineScoringTraceThreadUserDefinedMetricPythonScorer.class);
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isTraceThreadPythonEvaluatorEnabled()) {
            super.start();
        } else {
            log.warn("Online Scoring Python evaluator consumer won't start as it is disabled.");
        }
    }

    @Override
    protected void score(@NonNull TraceThreadToScoreUserDefinedMetricPython message) {

        log.info("Message received with projectId '{}', ruleId '{}' for workspace '{}'",
                message.projectId(), message.ruleId(), message.workspaceId());

        Flux.fromIterable(message.threadIds())
                .flatMap(threadId -> processThreadScores(message, threadId))
                .then(Mono.defer(
                        () -> traceThreadService.setScoredAt(message.projectId(), message.threadIds(), Instant.now())))
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName())
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .thenReturn(message)
                .subscribe(
                        unused -> log.info("Processed trace threads for projectId '{}', ruleId '{}' for workspace '{}'",
                                message.projectId(), message.ruleId(), message.workspaceId()),
                        error -> {
                            log.error(
                                    "Error processing trace thread for projectId '{}', ruleId '{}' for workspace '{}': {}",
                                    message.projectId(), message.ruleId(), message.workspaceId(), error.getMessage());
                            log.error("Error processing trace thread scoring", error);
                        });
    }

    private Mono<Void> processThreadScores(TraceThreadToScoreUserDefinedMetricPython message, String currentThreadId) {
        return retrieveFullThreadContext(currentThreadId, new AtomicReference<>(null), message.projectId())
                .sort(Comparator.comparing(Trace::id))
                .collectList()
                .flatMap(traces -> traceThreadService.getThreadModelId(message.projectId(), currentThreadId)
                        .flatMap(threadModelId -> Mono.fromCallable(() -> {
                            try (var logContext = LogContextAware.wrapWithMdc(Map.of(
                                    UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                                    UserLog.WORKSPACE_ID, message.workspaceId(),
                                    UserLog.THREAD_MODEL_ID, threadModelId.toString(),
                                    UserLog.RULE_ID, message.ruleId().toString()))) {

                                // Process python scoring for the thread
                                processScoring(message, traces, threadModelId, currentThreadId);

                                return threadModelId;
                            }
                        }).subscribeOn(Schedulers.boundedElastic())))
                .then();
    }

    /**
     * Processes the scoring for a given thread using the Python evaluator.
     *
     * @param message the message containing project ID, rule ID, and other details
     * @param traces the list of traces associated with the thread
     * @param threadModelId the ID of the thread model
     * @param threadId the ID of the thread
     */
    private void processScoring(TraceThreadToScoreUserDefinedMetricPython message, List<Trace> traces,
            UUID threadModelId, String threadId) {

        final AutomationRuleEvaluator<?, ?> rule;

        try {
            rule = automationRuleEvaluatorService.findById(message.ruleId(), Set.of(message.projectId()),
                    message.workspaceId());
        } catch (NotFoundException ex) {
            log.warn(
                    "Automation rule with ID '{}' not found in projectId '{}' for workspace '{}'. Skipping scoring for threadId '{}'.",
                    message.ruleId(), message.projectId(), message.workspaceId(), threadId);
            return;
        }

        Project project = projectService.get(message.projectId(), message.workspaceId());

        if (traces.isEmpty()) {
            userFacingLogger.info(
                    "No traces found for threadId: '{}' in projectId; '{}', ruleName: '{}' for workspace '{}'",
                    threadId, message.projectId(), rule.getName(), message.workspaceId());
            return;
        }

        List<ChatMessage> context;
        try {
            context = OnlineScoringEngine.fromTraceToThread(traces);
        } catch (Exception exception) {
            userFacingLogger.error("Error preparing Python request for threadId: '{}': \n\n{}",
                    threadId, exception.getMessage());
            throw exception;
        }

        userFacingLogger.info("Sending threadId: '{}' to Python evaluator using the following context:\n\n{}",
                threadId, context);

        List<PythonScoreResult> scoreResults;
        try {
            scoreResults = pythonEvaluatorService.evaluateThread(message.code().metric(), context);
            userFacingLogger.info("Received response for threadId: '{}':\n\n{}", threadId, scoreResults);
        } catch (Exception exception) {
            userFacingLogger.error("Unexpected error while scoring traceId: '{}' with ruleName: '{}': \n\n{}",
                    threadId, rule.getName(), exception.getMessage());
            throw exception;
        }

        List<FeedbackScoreBatchItemThread> scores = scoreResults.stream()
                .map(scoreResult -> FeedbackScoresMapper.INSTANCE.map(
                        scoreResult,
                        threadModelId,
                        threadId,
                        message.projectId(),
                        project.name(),
                        ScoreSource.ONLINE_SCORING))
                .toList();

        try {
            var loggedScores = storeThreadScores(scores, threadId, message.userName(), message.workspaceId());
            userFacingLogger.info("Scores for threadId: '{}' stored successfully:\n\n{}", threadId,
                    loggedScores);
        } catch (Exception exception) {
            userFacingLogger.error("Unexpected error while storing scores for traceId: '{}' with ruleName: '{}'",
                    threadId, rule.getName());
            throw exception;
        }
    }

}
