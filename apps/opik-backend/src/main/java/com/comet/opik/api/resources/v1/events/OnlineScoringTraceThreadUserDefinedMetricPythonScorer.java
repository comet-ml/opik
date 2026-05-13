package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.events.TraceThreadToScoreUserDefinedMetricPython;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.evaluators.python.PythonEvaluatorService;
import com.comet.opik.domain.evaluators.python.PythonScoreResult;
import com.comet.opik.domain.evaluators.python.TraceThreadPythonEvaluatorRequest;
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
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
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
    private final SpanService spanService;
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
            @NonNull SpanService spanService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_USER_DEFINED_METRIC_PYTHON,
                Constants.TRACE_THREAD_USER_DEFINED_METRIC_PYTHON);
        this.pythonEvaluatorService = pythonEvaluatorService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.traceThreadService = traceThreadService;
        this.spanService = spanService;
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
    protected Mono<Void> score(@NonNull TraceThreadToScoreUserDefinedMetricPython message) {

        log.info("Message received with projectId '{}', ruleId '{}' for workspace '{}'",
                message.projectId(), message.ruleId(), message.workspaceId());

        return Flux.fromIterable(message.threadIds())
                .flatMap(threadId -> processThreadScores(message, threadId))
                .then(Mono.defer(
                        () -> traceThreadService.setScoredAt(message.projectId(), message.threadIds(), Instant.now())))
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
        return Mono.fromCallable(() -> prepareScoring(message, traces, threadId, rule, mdc))
                .flatMap(context -> evaluateAndStore(message, threadModelId, threadId, context, mdc))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring threadId '{}' with rule '{}': \n\n{}",
                                threadId, rule.getName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * Builds the Python evaluator payload for the given thread. The data shape depends on
     * whether the rule declared opt-in {@code spans} / {@code traces} arguments:
     * <ul>
     *   <li>No opt-in arguments → legacy {@code List<ChatMessage>} positional shape.</li>
     *   <li>At least one opt-in argument → kwargs-shaped {@code Map<String, Object>} with
     *       {@code messages} plus the requested {@code spans} / {@code traces} entries.</li>
     * </ul>
     * Caller guarantees {@code traces} is non-empty. Span fetch is opt-in: only fired when
     * the rule actually declared {@code spans} in {@code arguments}, so most threads avoid
     * the DB hit.
     */
    private Pair<Project, Object> prepareScoring(TraceThreadToScoreUserDefinedMetricPython message,
            List<Trace> traces, String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating threadId '{}' sampled by rule '{}'", threadId, rule.getName());

            Project project = projectService.get(message.projectId(), message.workspaceId());

            Object data;
            try {
                List<ChatMessage> messages = OnlineScoringEngine.fromTraceToThread(traces);
                Map<String, String> arguments = message.code().arguments();
                boolean wantsSpans = arguments != null
                        && arguments.containsKey(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.SPANS_ARG_NAME);
                boolean wantsTraces = arguments != null
                        && arguments
                                .containsKey(AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython.TRACES_ARG_NAME);

                if (!wantsSpans && !wantsTraces) {
                    // Legacy path: single-positional messages list. Untouched for rules without
                    // opt-in arguments, so existing thread metrics keep working as-is.
                    data = messages;
                } else {
                    var dict = new LinkedHashMap<String, Object>();
                    dict.put(TraceThreadPythonEvaluatorRequest.MESSAGES_KEY, messages);
                    if (wantsSpans) {
                        dict.put(TraceThreadPythonEvaluatorRequest.SPANS_KEY,
                                fetchSpansForThread(traces, message.workspaceId(), message.userName()));
                    }
                    if (wantsTraces) {
                        dict.put(TraceThreadPythonEvaluatorRequest.TRACES_KEY, traces);
                    }
                    data = dict;
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing Python request for threadId '{}': \n\n{}",
                        threadId, exception.getMessage());
                throw exception;
            }

            if (userFacingLogger.isInfoEnabled()) {
                userFacingLogger.info("Sending threadId '{}' to Python evaluator: {}",
                        threadId, summarizeData(data));
            }

            return Pair.of(project, data);
        }
    }

    private List<Span> fetchSpansForThread(List<Trace> traces, String workspaceId, String userName) {
        Set<UUID> traceIds = traces.stream().map(Trace::id).collect(Collectors.toSet());
        List<Span> spans = spanService.getByTraceIds(traceIds)
                .collectList()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .block();
        return spans == null ? List.of() : spans;
    }

    /**
     * Shape-only one-line summary of the rendered Python evaluator input. Same rationale as
     * {@code OnlineScoringEngine.summarizeEvaluatorInput} — values are rendered user data
     * (messages, spans, traces); logging them verbatim would land conversation content
     * downstream of whatever sinks the user-facing log feeds.
     */
    @SuppressWarnings("unchecked")
    private static String summarizeData(Object data) {
        if (data instanceof List<?> list) {
            return String.format("messages=list(%d)", list.size());
        }
        if (data instanceof Map<?, ?> mapAny) {
            Map<String, Object> map = (Map<String, Object>) mapAny;
            var parts = new ArrayList<String>();
            map.forEach((k, v) -> {
                if (v instanceof List<?> list) {
                    parts.add(String.format("%s=list(%d)", k, list.size()));
                } else {
                    parts.add(String.format("%s=%s", k, v == null ? "null" : v.getClass().getSimpleName()));
                }
            });
            return String.join(", ", parts);
        }
        return String.format("data=%s", data == null ? "null" : data.getClass().getSimpleName());
    }

    @SuppressWarnings("unchecked")
    private Mono<Map<String, List<BigDecimal>>> evaluateAndStore(
            TraceThreadToScoreUserDefinedMetricPython message, UUID threadModelId, String threadId,
            Pair<Project, Object> context, Map<String, String> mdc) {
        var project = context.getLeft();
        var data = context.getRight();
        Mono<List<PythonScoreResult>> evaluation = (data instanceof List)
                ? pythonEvaluatorService.evaluateThread(message.code().metric(), (List<ChatMessage>) data)
                : pythonEvaluatorService.evaluateThreadWithData(message.code().metric(), (Map<String, Object>) data);
        return evaluation
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
