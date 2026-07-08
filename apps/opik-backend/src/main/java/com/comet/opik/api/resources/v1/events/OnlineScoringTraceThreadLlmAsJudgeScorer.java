package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.attachment.EntityType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.LlmAsJudgeMessage;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.evaluation.EvaluatedThread;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
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
    private final AgenticScoringService agenticScoringService;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final SpanService spanService;
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;
    private final AttachmentService attachmentService;

    @Inject
    public OnlineScoringTraceThreadLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull TraceService traceService,
            @NonNull TraceThreadService traceThreadService,
            @NonNull ProjectService projectService,
            @NonNull AutomationRuleEvaluatorService automationRuleEvaluatorService,
            @NonNull AgenticScoringService agenticScoringService,
            @NonNull SpanService spanService,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder,
            @NonNull AttachmentService attachmentService) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_LLM_AS_JUDGE,
                Constants.TRACE_THREAD_LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.llmProviderFactory = llmProviderFactory;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.agenticScoringService = agenticScoringService;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.spanService = spanService;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
        this.attachmentService = attachmentService;
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
        // When the feature flag is on, fetch every span across every trace in the thread
        // up front. We fetch BEFORE the inline-vs-tools path decision in prepareEvaluation
        // (rather than only when the inline path wins) so estimateThreadContextTokens can
        // serialize the enriched shape and route honestly — otherwise a thread with small
        // trace bodies but huge spans would inline-render an oversized prompt. The cost is
        // wasted I/O when the tools path ultimately wins: the prepared spans go unused on
        // that path (which renders only the compact skeleton; the model uses ReadTool to
        // re-fetch per-trace on demand). Acceptable trade-off — route correctness over a
        // narrow over-fetch.
        //
        // When the flag is off, an empty list is passed through; the enriched serializer
        // omits the `spans` field via @JsonInclude(NON_NULL), so the rendered JSON is
        // byte-identical to today's [{role, content}, ...] shape.
        Mono<List<Span>> spansMono = serviceTogglesConfig.isAgenticToolsEnabled()
                ? spanService.getByTraceIds(traces.stream().map(Trace::id).collect(Collectors.toSet()))
                        .collectList()
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(List.of());
        // Monitoring recorder (OPIK-6994): one hidden evaluator trace per thread evaluation, with an
        // llm span per LLM round and tool spans for the agentic loop. NOOP when the toggle is off.
        // Resolved reactively because the project-name lookup is blocking.
        Mono<EvaluationRecorder> recorderMono = Mono.fromCallable(
                () -> serviceTogglesConfig.isOnlineScoringTracingEnabled()
                        ? onlineEvaluationRecorder.begin(
                                EvaluatedThread.builder()
                                        .id(threadId)
                                        .projectId(message.projectId())
                                        .projectName(projectService.get(message.projectId(),
                                                message.workspaceId()).name())
                                        .build(),
                                rule.getId(), rule.getName(), message.code().model().name(),
                                message.workspaceId(), message.userName())
                        : EvaluationRecorder.NOOP)
                .subscribeOn(Schedulers.boundedElastic())
                // Monitoring is best-effort: a project-name lookup failure (e.g. missing project) must
                // not abort the actual thread scoring, so degrade to NOOP instead of failing the zip.
                .onErrorResume(error -> {
                    log.warn("Failed to start online-evaluation monitoring for thread '{}', "
                            + "proceeding without it", threadId, error);
                    return Mono.just(EvaluationRecorder.NOOP);
                });

        // Check whether any trace in the thread has attachments — if so, force the agentic-tools
        // path regardless of context size (the judge needs get_attachment to fetch them). Best-effort:
        // a transient listing error returns false and falls back to the normal size-based routing.
        Mono<Boolean> hasAttachmentsMono = serviceTogglesConfig.isAgenticToolsEnabled()
                ? attachmentService.hasAnyAttachmentByEntityIds(
                        EntityType.TRACE,
                        traces.stream().map(Trace::id).collect(Collectors.toSet()))
                        .onErrorReturn(false)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(false);

        return Mono.zip(recorderMono, spansMono, hasAttachmentsMono)
                .flatMap(tuple -> {
                    EvaluationRecorder recorder = tuple.getT1();
                    return recorder.monitor(evaluate(message, traces, tuple.getT2(), tuple.getT3(), threadModelId,
                            threadId, rule, mdc, recorder))
                            .flatMap(scores -> storeThreadScores(scores, threadId, message.userName(),
                                    message.workspaceId()));
                })
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
     * Builds and runs the LLM scoring chain for the thread. Routes through the agentic-tools
     * branch (skeleton + ReadTool/JqTool/SearchTool drill-down) when the toggle is on AND either
     * the inline-rendered thread would exceed the configured size threshold OR the thread has
     * attachments (so the judge can call get_attachment). Provider must support tool-calling and
     * the template must be text-only. Otherwise the inline path runs unchanged — same shape as today.
     */
    private Mono<List<FeedbackScoreBatchItemThread>> evaluate(TraceThreadToScoreLlmAsJudge message,
            List<Trace> traces, List<Span> spans, boolean hasAttachments, UUID threadModelId, String threadId,
            AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc, EvaluationRecorder recorder) {
        // One guard per thread evaluation; UNLIMITED when the rule sets no maxCostUsd. Charges every
        // LLM call (initial + tool rounds + wrap-up) and tells the tool loop when to start wrapping up.
        var costGuard = BudgetGuard.create(message.code().maxCostUsd(), message.code().model().name(),
                llmProviderFactory);
        return Mono.fromCallable(() -> prepareEvaluation(message, traces, spans, hasAttachments, threadId, rule, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> {
                    // Uniform structure with trace evals: prepare_evaluation span (fetched spans,
                    // size estimate, mode) before the first LLM round. The agentic flag also sets
                    // the parent trace's mode.
                    recorder.recordPreparation(spans.size(), prepared.estimatedTokens(), prepared.useTools());
                    return scoreTraceReactive(prepared.scoreRequest(), message, recorder, costGuard)
                            .doOnNext(withMdc(mdc, chatResponse -> {
                                if (userFacingLogger.isInfoEnabled()) {
                                    userFacingLogger.info("Received response for threadId '{}': '{}'",
                                            threadId, agenticScoringService.summarizeResponse(chatResponse));
                                }
                            }))
                            .flatMap(initialResponse -> prepared.useTools()
                                    ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                            prepared.structuredRequest(), message, mdc, recorder, costGuard)
                                    : Mono.just(initialResponse));
                })
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        Project project = projectService.get(message.projectId(), message.workspaceId());
                        if (costGuard.wasBudgetEnforced()) {
                            // Keyed on wasBudgetEnforced() (the loop's budget gate actually cut the run
                            // short), not shouldWrapUp() (mere spend >= limit): this warn claims the
                            // investigation was stopped, so it must not fire on the inline single-call path
                            // or a natural stop that only crossed spend. Same authoritative signal as the
                            // budget_exceeded trace tag flagged inside ToolCallLoop.
                            userFacingLogger.warn(
                                    "Spend budget of '{}' USD reached for threadId '{}' (spent '{}'); "
                                            + "stopped investigating and wrapped up with the scores gathered so far.",
                                    costGuard.limitUsd(), threadId, costGuard.spentUsd());
                        }
                        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse);
                        OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "threadId", threadId);
                        return parsed.scores().stream()
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
                });
    }

    /**
     * Sync preparation step — picks the path (inline vs agentic-tools) and builds the chat
     * request(s). Wrapped in {@code Mono.fromCallable} on {@code Schedulers.parallel()} by the
     * caller because the body is CPU-bound (JSON serialization for the size estimate + prompt
     * rendering); no blocking I/O happens here.
     */
    private PreparedEvaluation prepareEvaluation(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
            List<Span> spans, boolean hasAttachments, String threadId, AutomationRuleEvaluator<?, ?> rule,
            Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating threadId '{}' sampled by rule '{}'", threadId, rule.getName());

            String modelName = message.code().model().name();
            // Skip the JSON serialization that drives the token estimate when the toggle is off —
            // we'd just throw the number away. shouldUseAgenticTools re-checks the toggle, so the
            // estimate is only consulted on the agentic-tools path. When the toggle is on, `spans`
            // is the pre-fetched per-thread span list (empty when toggle off) and is factored in so
            // an enriched-context payload routes to tools when it's actually big — not when the
            // trace bodies alone happen to be small.
            int estimatedContextTokens = serviceTogglesConfig.isAgenticToolsEnabled()
                    ? agenticScoringService.estimateThreadContextTokens(traces, spans,
                            onlineScoringConfig.getAgenticToolsCharsPerToken())
                    : 0;
            boolean useTools = shouldUseAgenticTools(estimatedContextTokens, hasAttachments, modelName,
                    threadId, message.code().messages());

            ChatRequest scoreRequest;
            ChatRequest structuredRequest;
            try {
                var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                if (useTools) {
                    // Tools path: skeleton + drill-down hint instead of full trace dump. The agent
                    // drills into specific traces via read(type=trace, id=X) — same lazy pattern
                    // the trace-level path uses. Spans for any one trace are fetched reactively in
                    // ReadTool.readTrace only when the model actually asks for that trace.
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequestWithTools(
                            message.code(), traces, strategy);
                    // The post-tool-loop wrap-up uses the same structured-output strategy — for
                    // threads there is no separate InstructionStrategy variant, so the initial and
                    // wrap-up requests share a shape (modulo tool specs).
                    structuredRequest = scoreRequest;
                } else {
                    // Inline path: render {{context}} with the enriched per-assistant `spans`
                    // shape. When the toggle is off, `spans` is an empty list and the JSON is
                    // wire-identical to today's [{role, content}, ...].
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(message.code(), traces, strategy,
                            spans);
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                OnlineScoringEngine.logPreparingLlmRequestError(userFacingLogger, log, "threadId",
                        threadId, exception);
                throw exception;
            }

            if (useTools) {
                // REQUIRED on the first call only — same reasoning as the trace scorer: forces
                // ≥1 tool call before the model can answer from skeleton alone. Follow-up rounds
                // switch to AUTO so the wrap-up turn can emit JSON without invoking a tool.
                scoreRequest = agenticScoringService.addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            // summarizeRequest is cheap (no per-message toString streaming). At INFO to mirror
            // the trace scorer's symmetric Evaluating / Sending / Received chain.
            userFacingLogger.info("Sending threadId '{}' to LLM: {}",
                    threadId, agenticScoringService.summarizeRequest(scoreRequest, modelName, useTools));
            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools, estimatedContextTokens);
        }
    }

    /**
     * Routing decision for whether to attach tool specs + run the tool-call loop for a thread.
     * Requires the toggle on AND at least one of:
     * <ul>
     *   <li>estimated thread context exceeds the configured token threshold, OR</li>
     *   <li>the thread has attachments (the judge needs get_attachment to fetch them)</li>
     * </ul>
     * Both cases also require the provider to support tool-calling and the template to be
     * text-only. Falls back to inline with a user-facing warn when those secondary guards fail.
     */
    boolean shouldUseAgenticTools(int estimatedContextTokens, boolean hasAttachments, String modelName,
            String threadId, List<LlmAsJudgeMessage> templateMessages) {
        if (!serviceTogglesConfig.isAgenticToolsEnabled()) {
            return false;
        }
        boolean overSizeThreshold = estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        // Skip the provider lookup when neither trigger is met — most evaluations are toggle-off
        // or have neither large contexts nor attachments.
        if (!overSizeThreshold && !hasAttachments) {
            return false;
        }
        boolean providerSupportsTools = agenticScoringService.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        if (!providerSupportsTools) {
            userFacingLogger.warn(
                    "Thread has attachments or context exceeds '{}' tokens but provider for model '{}'"
                            + " does not support tool calling; falling back to inline path for threadId"
                            + " '{}' — may overflow context window.",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), modelName, threadId);
            return false;
        }
        // The thread agentic-tools render path only substitutes string content; multimodal
        // templates (image / audio / video parts alongside text) would otherwise hit the
        // safety throw in renderThreadMessagesWithReplacement and fail the evaluation.
        if (OnlineScoringEngine.hasMultimodalTemplate(templateMessages)) {
            userFacingLogger.warn(
                    "Thread has attachments or context exceeds '{}' tokens but evaluator template has"
                            + " multimodal content; falling back to inline path for threadId '{}'"
                            + " — may overflow context window.",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), threadId);
            return false;
        }
        if (hasAttachments) {
            log.debug("Thread has attachments; switching to agentic-tools mode for threadId '{}'", threadId);
        } else {
            log.debug("Thread context exceeds '{}' tokens; switching to agentic-tools mode for threadId '{}'",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), threadId);
        }
        return true;
    }

    /**
     * Wraps the synchronous {@code ChatLanguageModel.chat} call in a Mono and schedules it on
     * {@link Schedulers#boundedElastic()} so the blocking Jersey-client I/O doesn't pin the
     * per-stream worker scheduler thread (OPIK-6308). Mirrors the trace scorer.
     */
    private Mono<ChatResponse> scoreTraceReactive(ChatRequest request, TraceThreadToScoreLlmAsJudge message,
            EvaluationRecorder recorder, BudgetGuard costGuard) {
        var call = Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.code().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
        return costGuard.track(recorder.recordLlmCall(request, call));
    }

    // Package-private for unit tests.
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceThreadToScoreLlmAsJudge message, Map<String, String> mdc,
            EvaluationRecorder recorder, BudgetGuard costGuard) {
        // Shared loop orchestration lives in AgenticScoringService; here we provide only the
        // thread-scoped context — no single active trace. ReadTool fetches any trace from the
        // thread on demand via the standard read(type=trace, id=X) path. GetTraceSpansTool
        // returns a redirect error on this context (see GetTraceSpansTool#execute).
        return agenticScoringService.runToolCallLoop(chatResponse, toolRequest, structuredRequest,
                () -> TraceToolContext.forThread(message.workspaceId(), message.userName(), message.projectId(),
                        onlineScoringConfig.getAgenticToolsMaxInjectedBytes()),
                request -> scoreTraceReactive(request, message, recorder, costGuard),
                costGuard,
                () -> message.code().model().name(), "threadId/ruleId=" + message.ruleId(), userFacingLogger, mdc,
                recorder);
    }

    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools,
            int estimatedTokens) {
    }
}
