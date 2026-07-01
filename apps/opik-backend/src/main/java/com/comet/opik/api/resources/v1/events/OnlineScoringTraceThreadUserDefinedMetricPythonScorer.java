package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON;
import static com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest.ChatMessage;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

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
    private final SpanService spanService;

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
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService,
            @NonNull SpanService spanService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
                Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);
        this.pythonEvaluatorService = pythonEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.spanService = spanService;
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
    protected Mono<Void> score(@NonNull TraceThreadToScoreUserDefinedMetricPython message) {

        log.info("Message received with projectId '{}', ruleId '{}' for workspace '{}'",
                message.projectId(), message.ruleId(), message.workspaceId());

        return Flux.fromIterable(message.threadIds())
                // Score each thread id independently: a single thread's failure must not stop scoring the
                // sibling thread ids. Per-thread errors are materialized (onErrorResume) so the flatMap
                // completes for every thread; the batch's first failure is then re-surfaced below. This keeps
                // the failure on the Mono error path handled by BaseRedisSubscriber.processMessage's
                // onErrorResume — classified as a processing error, following the normal retryable/
                // non-retryable path — instead of leaking into the enclosing onErrorContinue via Flux.flatMap
                // (which would drop the element and count it as an "unexpected" error).
                .flatMap(threadId -> processThreadScores(message, threadId)
                        .then(Mono.<Throwable>empty())
                        .onErrorResume(Mono::just))
                .collectList()
                .flatMap(errors -> errors.isEmpty() ? Mono.<Void>empty() : Mono.error(errors.getFirst()))
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName())
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE))
                .doOnSuccess(unused -> log.info(
                        "Processed trace threads for projectId '{}', ruleId '{}' for workspace '{}'",
                        message.projectId(), message.ruleId(), message.workspaceId()))
                .doOnError(error -> log.error(
                        "Error processing trace thread for projectId '{}', ruleId '{}' for workspace '{}'",
                        message.projectId(), message.ruleId(), message.workspaceId(), error))
                .then();
    }

    private Mono<Void> processThreadScores(TraceThreadToScoreUserDefinedMetricPython message,
            String currentThreadId) {
        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.RULE_ID, message.ruleId().toString());
        return retrieveFullThreadContext(currentThreadId, new AtomicReference<>(null), message.projectId())
                .sort(Comparator.comparing(Trace::id))
                .collectList()
                .flatMap(traces -> {
                    if (traces.isEmpty()) {
                        try (var logContext = wrapWithMdc(mdc)) {
                            userFacingLogger.info(
                                    "No traces found for threadId '{}' in projectId '{}'. Skipping scoring.",
                                    currentThreadId, message.projectId());
                        }
                        return Mono.empty();
                    }
                    return traceThreadService.getThreadModelId(message.projectId(), currentThreadId)
                            .switchIfEmpty(Mono.defer(() -> {
                                try (var logContext = wrapWithMdc(mdc)) {
                                    userFacingLogger.info(
                                            "Thread model not found for threadId '{}' in projectId '{}'. Skipping scoring.",
                                            currentThreadId, message.projectId());
                                }
                                return Mono.empty();
                            }))
                            .flatMap(threadModelId -> processScoring(message, traces, threadModelId,
                                    currentThreadId));
                })
                .then();
    }

    /**
     * Scores a single thread for a given rule and persists the resulting feedback scores.
     */
    private Mono<Void> processScoring(TraceThreadToScoreUserDefinedMetricPython message, List<Trace> traces,
            UUID threadModelId, String threadId) {
        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.THREAD_MODEL_ID, threadModelId.toString(),
                UserLog.RULE_ID, message.ruleId().toString());
        return Mono.fromCallable(() -> findRule(message, threadId, mdc))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while looking up rule for threadId '{}': \n\n{}",
                                threadId,
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .flatMap(maybeRule -> maybeRule
                        .map(rule -> scoreThread(message, traces, threadModelId, threadId, rule, mdc))
                        .orElseGet(Mono::empty));
    }

    /**
     * Resolves the automation rule for this scoring run. Returns {@link Optional#empty()} if the rule
     * has been deleted, signalling the caller to skip without throwing.
     */
    private Optional<AutomationRuleEvaluator<?, ?>> findRule(
            TraceThreadToScoreUserDefinedMetricPython message, String threadId, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            try {
                var rule = automationRuleEvaluatorService.findById(message.ruleId(),
                        Set.of(message.projectId()), message.workspaceId());
                return Optional.of(rule);
            } catch (NotFoundException ex) {
                log.warn(
                        "Automation rule with ID '{}' not found in projectId '{}' for workspace '{}'. Skipping scoring for threadId '{}'.",
                        message.ruleId(), message.projectId(), message.workspaceId(), threadId);
                return Optional.empty();
            }
        }
    }

    /**
     * Runs the scoring chain for a known rule and persists the resulting feedback scores. Caller
     * guarantees {@code traces} is non-empty.
     */
    private Mono<Void> scoreThread(TraceThreadToScoreUserDefinedMetricPython message, List<Trace> traces,
            UUID threadModelId, String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        // Fetch every span across every trace in the thread when the agentic-tools feature
        // flag is on — same gate as the LLM-as-judge thread scorer. Spans get nested under
        // their trace's assistant ChatMessage via fromTraceToThreadEnriched, so the user's
        // Python `score(...)` method sees the full call tree (tool inputs/outputs + LLM
        // calls) instead of the legacy {role, content}-only shape. When the toggle is off,
        // empty spans → ChatMessage's `spans` field omitted via @JsonInclude(NON_NULL) →
        // wire-identical to today's [{role, content}, ...].
        Mono<List<Span>> spansMono = serviceTogglesConfig.isAgenticToolsEnabled()
                ? spanService.getByTraceIds(traces.stream().map(Trace::id).collect(Collectors.toSet()))
                        .collectList()
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(List.of());
        return spansMono
                // boundedElastic so the blocking JDBC call inside prepareScoring
                // (projectService.get) doesn't pin the upstream thread — could be the consumer
                // loop when spansMono is Mono.just(empty), or the spanService DB thread when
                // spansMono is the getByTraceIds fetch. Either way, blocking on those threads
                // is bad; boundedElastic is the standard pick for wrapping blocking calls in a
                // reactive chain.
                .flatMap(spans -> Mono.fromCallable(
                        () -> prepareScoring(message, traces, spans, threadId, rule, mdc))
                        .subscribeOn(Schedulers.boundedElastic()))
                .flatMap(context -> evaluateAndStore(message, threadModelId, threadId, context, mdc))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring threadId '{}' with rule '{}': \n\n{}",
                                threadId, rule.getName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * Builds the Python evaluator request (project + chat messages) for the given thread. Caller
     * guarantees {@code traces} is non-empty.
     */
    private Pair<Project, List<ChatMessage>> prepareScoring(TraceThreadToScoreUserDefinedMetricPython message,
            List<Trace> traces, List<Span> spans, String threadId, AutomationRuleEvaluator<?, ?> rule,
            Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating threadId '{}' sampled by rule '{}'", threadId, rule.getName());

            Project project = projectService.get(message.projectId(), message.workspaceId());

            // Always use the enriched helper — when `spans` is empty (toggle off, see
            // scoreThread), it emits the legacy [{role, content}, ...] shape via
            // @JsonInclude(NON_NULL) on ChatMessage.spans. When non-empty, the assistant
            // entry for each trace carries the nested span tree.
            List<ChatMessage> context;
            try {
                context = OnlineScoringEngine.fromTraceToThreadEnriched(traces, spans);
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing Python request for threadId '{}': \n\n{}",
                        threadId, exception.getMessage());
                throw exception;
            }

            userFacingLogger.info("Sending threadId '{}' to Python evaluator using the following context:\n\n{}",
                    threadId, context);

            return Pair.of(project, context);
        }
    }

    private Mono<Map<String, List<BigDecimal>>> evaluateAndStore(
            TraceThreadToScoreUserDefinedMetricPython message, UUID threadModelId, String threadId,
            Pair<Project, List<ChatMessage>> context, Map<String, String> mdc) {
        var project = context.getLeft();
        var chatMessages = context.getRight();
        return pythonEvaluatorService.evaluateThread(message.code().metric(), chatMessages)
                .doOnNext(withMdc(mdc, scoreResults -> userFacingLogger
                        .info("Received response for threadId '{}':\n\n{}", threadId, scoreResults)))
                .flatMap(scoreResults -> {
                    List<FeedbackScoreBatchItemThread> scores = scoreResults.stream()
                            .map(scoreResult -> FeedbackScoresMapper.INSTANCE.map(
                                    scoreResult,
                                    threadModelId,
                                    threadId,
                                    message.projectId(),
                                    project.name(),
                                    ScoreSource.ONLINE_SCORING))
                            .toList();
                    return storeThreadScores(scores, threadId, message.userName(), message.workspaceId());
                })
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for threadId '{}' stored successfully:\n\n{}", threadId, loggedScores)));
    }
}
