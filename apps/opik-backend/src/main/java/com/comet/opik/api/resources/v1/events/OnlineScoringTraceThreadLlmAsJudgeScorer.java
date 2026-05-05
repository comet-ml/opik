package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.TRACE_THREAD_LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringTraceThreadLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceThreadToScoreLlmAsJudge> {

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TraceThreadService traceThreadService;
    private final ProjectService projectService;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;

    @Inject
    public OnlineScoringTraceThreadLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull TraceService traceService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_LLM_AS_JUDGE,
                Constants.TRACE_THREAD_LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.llmProviderFactory = llmProviderFactory;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringTraceThreadLlmAsJudgeScorer.class);
    }

    /**
     * Use AI Proxy to score the trace thread and store it as a feedback scores.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with the trace thread to score with an Evaluator code, workspace and username.
     */
    @Override
    protected Mono<Void> score(@NonNull TraceThreadToScoreLlmAsJudge message) {

        log.info("Message received with projectId: '{}', ruleId: '{}', threadIds: '{}' for workspace '{}'",
                message.projectId(), message.ruleId(), message.threadIds(), message.workspaceId());

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

    private Mono<Void> processThreadScores(TraceThreadToScoreLlmAsJudge message, String currentThreadId) {
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
    private Mono<Void> processScoring(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
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
    private Optional<AutomationRuleEvaluator<?, ?>> findRule(TraceThreadToScoreLlmAsJudge message, String threadId,
            Map<String, String> mdc) {
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
    private Mono<Void> scoreThread(TraceThreadToScoreLlmAsJudge message, List<Trace> traces, UUID threadModelId,
            String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        return Mono.fromCallable(() -> evaluate(message, traces, threadModelId, threadId, rule, mdc))
                .flatMap(scores -> storeThreadScores(scores, threadId, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for threadId '{}' stored successfully:\n\n{}", threadId, loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring threadId '{}' with rule '{}': \n\n{}",
                                threadId, rule.getName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * Calls the LLM provider for the given thread and returns the resulting feedback scores. Caller
     * guarantees {@code traces} is non-empty.
     */
    private List<FeedbackScoreBatchItemThread> evaluate(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
            UUID threadModelId, String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating threadId '{}' sampled by rule '{}'", threadId, rule.getName());

            Project project = projectService.get(message.projectId(), message.workspaceId());

            ChatRequest scoreRequest;
            try {
                String modelName = message.code().model().name();
                var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(message.code(), traces, strategy);
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for threadId '{}': \n\n{}",
                        threadId, exception.getMessage());
                throw exception;
            }

            userFacingLogger.info("Sending threadId '{}' to LLM using the following input:\n\n{}",
                    threadId, scoreRequest);

            var chatResponse = aiProxyService.scoreTrace(scoreRequest, message.code().model(), message.workspaceId());
            userFacingLogger.info("Received response for threadId '{}':\n\n{}", threadId, chatResponse);

            return OnlineScoringEngine.toFeedbackScores(chatResponse).stream()
                    .map(item -> FeedbackScoresMapper.INSTANCE.map(
                            item.toBuilder()
                                    .id(threadModelId)
                                    .projectId(message.projectId())
                                    .projectName(project.name())
                                    .source(ScoreSource.ONLINE_SCORING)
                                    .build(),
                            threadId))
                    .toList();
        }
    }
}
