package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.events.TraceThreadToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.AutomationRuleEvaluatorService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
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

import java.time.Instant;
import java.util.ArrayList;
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

    private static final int MAX_TOOL_CALL_ROUNDS = 10;

    /**
     * Cumulative cap on tool-result string length across the whole tool-call loop. Same
     * shape and sizing as the trace-level scorer's cap — see that class for the budget
     * math. Pairs with {@link com.comet.opik.api.resources.v1.events.tools.ReadTool#OUTPUT_SAFETY_CHARS}.
     */
    static final long CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS = 150_000L;

    private static final String BUDGET_EXHAUSTED_MESSAGE = "{\"error\": \"Cumulative tool-output"
            + " budget (%d chars) exhausted for this judgment; further tool calls return this"
            + " error. Respond now with your best assessment from the data already gathered.\"}";

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TraceThreadService traceThreadService;
    private final ProjectService projectService;
    private final AutomationRuleEvaluatorService automationRuleEvaluatorService;
    private final ToolRegistry toolRegistry;
    private final OnlineScoringConfig onlineScoringConfig;
    private final ServiceTogglesConfig serviceTogglesConfig;

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
            @NonNull ToolRegistry toolRegistry) {
        super(config, redisson, feedbackScoreService, traceService, TRACE_THREAD_LLM_AS_JUDGE,
                Constants.TRACE_THREAD_LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.llmProviderFactory = llmProviderFactory;
        this.traceThreadService = traceThreadService;
        this.projectService = projectService;
        this.automationRuleEvaluatorService = automationRuleEvaluatorService;
        this.toolRegistry = toolRegistry;
        this.onlineScoringConfig = config;
        this.serviceTogglesConfig = serviceTogglesConfig;
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
        return evaluate(message, traces, threadModelId, threadId, rule, mdc)
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
     * Builds and runs the LLM scoring chain for the thread. Routes through the agentic-tools
     * branch (skeleton + ReadTool/JqTool/SearchTool drill-down) when the inline-rendered thread
     * would exceed the configured size threshold AND the provider supports tool-calling AND the
     * toggle is on. Otherwise the inline path runs unchanged — same shape as today.
     */
    private Mono<List<FeedbackScoreBatchItemThread>> evaluate(TraceThreadToScoreLlmAsJudge message,
            List<Trace> traces, UUID threadModelId, String threadId, AutomationRuleEvaluator<?, ?> rule,
            Map<String, String> mdc) {
        return Mono.fromCallable(() -> prepareEvaluation(message, traces, threadId, rule, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> scoreTraceReactive(prepared.scoreRequest(), message)
                        .doOnNext(withMdc(mdc, chatResponse -> userFacingLogger.info(
                                "Received response for threadId '{}':\n\n{}", threadId, chatResponse)))
                        .flatMap(initialResponse -> prepared.useTools()
                                ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                        prepared.structuredRequest(), message, mdc)
                                : Mono.just(initialResponse)))
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        Project project = projectService.get(message.projectId(), message.workspaceId());
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
            String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating threadId '{}' sampled by rule '{}'", threadId, rule.getName());

            String modelName = message.code().model().name();
            int estimatedContextTokens = OnlineScoringEngine.estimateThreadContextTokens(
                    traces, onlineScoringConfig.getAgenticToolsCharsPerToken());
            boolean useTools = shouldUseAgenticTools(estimatedContextTokens, modelName, threadId);

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
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(message.code(), traces, strategy);
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for threadId '{}': \n\n{}",
                        threadId, exception.getMessage());
                throw exception;
            }

            if (useTools) {
                // REQUIRED on the first call only — same reasoning as the trace scorer: forces
                // ≥1 tool call before the model can answer from skeleton alone. Follow-up rounds
                // switch to AUTO so the wrap-up turn can emit JSON without invoking a tool.
                scoreRequest = addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            userFacingLogger.info("Sending threadId '{}' to LLM using the following input:\n\n{}",
                    threadId, scoreRequest);
            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools);
        }
    }

    /**
     * Routing decision for whether to attach tool specs + run the tool-call loop for a thread.
     * Threads don't have the experimentId-driven branch (no test-suite-assertion equivalent),
     * so the decision is purely size-based: toggle on + estimated thread context above threshold
     * + provider supports tool-calling. Falls back to inline + a user-facing warn when toggle
     * is on and size is over but the provider can't handle tools.
     */
    boolean shouldUseAgenticTools(int estimatedContextTokens, String modelName, String threadId) {
        boolean overSizeThreshold = serviceTogglesConfig.isAgenticToolsEnabled()
                && estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        // Skip the provider lookup when the toggle is off — most evaluations are toggle-off and
        // this saves both a service hit and an unnecessary NPE-risk when the model is unknown.
        if (!overSizeThreshold) {
            return false;
        }
        boolean providerSupportsTools = OnlineScoringEngine.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        if (!providerSupportsTools) {
            userFacingLogger.warn(
                    "Thread context exceeds '{}' tokens but provider for model '{}' does not support tool"
                            + " calling; falling back to inline path — may overflow context window.",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), modelName);
            return false;
        }
        userFacingLogger.info(
                "Thread context exceeds '{}' tokens; switching to agentic-tools mode for threadId '{}'",
                onlineScoringConfig.getAgenticToolsThresholdTokens(), threadId);
        return true;
    }

    // Package-private for unit tests.
    ChatRequest addToolSpecs(ChatRequest request, ToolChoice toolChoice) {
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
                .parameters(parameters)
                .build();
    }

    /**
     * Wraps the synchronous {@code ChatLanguageModel.chat} call in a Mono and schedules it on
     * {@link Schedulers#boundedElastic()} so the blocking Jersey-client I/O doesn't pin the
     * per-stream worker scheduler thread (OPIK-6308). Mirrors the trace scorer.
     */
    private Mono<ChatResponse> scoreTraceReactive(ChatRequest request, TraceThreadToScoreLlmAsJudge message) {
        return Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.code().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Package-private for unit tests.
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceThreadToScoreLlmAsJudge message, Map<String, String> mdc) {

        AiMessage aiMessage = chatResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            return Mono.just(chatResponse);
        }

        // Defer everything below to subscription time. Same reasoning as the trace-side
        // scorer: keeps ctx/messages/budget allocation aligned with chain subscription, so a
        // future caller that composes the returned Mono differently doesn't trigger the
        // setup work at the wrong moment.
        return Mono.defer(() -> {
            // Thread-scoped context: no single active trace. ReadTool fetches any trace from the
            // thread on demand via the standard read(type=trace, id=X) path. GetTraceSpansTool
            // returns a redirect error on this context (see GetTraceSpansTool#execute).
            var ctx = TraceToolContext.forThread(message.workspaceId(), message.userName());

            var followUpParameters = ChatRequestParameters.builder()
                    .overrideWith(toolRequest.parameters())
                    .toolChoice(ToolChoice.AUTO)
                    .build();

            var messages = new ArrayList<ChatMessage>(toolRequest.messages());
            var budget = new ToolOutputBudget();

            return toolCallLoop(0, chatResponse, toolRequest, followUpParameters, message, messages, ctx, budget, mdc)
                    .flatMap(loopFinalResponse -> {
                        messages.add(UserMessage.from(
                                "You have completed your investigation using the available tools."
                                        + " Now respond with ONLY the JSON object specified in the original instructions."
                                        + " Do not call any more tools. Do not include any prose, commentary, or markdown"
                                        + " fences — emit only the raw JSON object."));
                        var finalRequest = structuredRequest.toBuilder()
                                .messages(new ArrayList<>(messages))
                                .build();
                        return scoreTraceReactive(finalRequest, message);
                    });
        });
    }

    private Mono<ChatResponse> toolCallLoop(int round, ChatResponse currentResponse, ChatRequest toolRequest,
            ChatRequestParameters followUpParameters, TraceThreadToScoreLlmAsJudge message,
            ArrayList<ChatMessage> messages, TraceToolContext ctx, ToolOutputBudget budget,
            Map<String, String> mdc) {
        if (round >= MAX_TOOL_CALL_ROUNDS) {
            return Mono.just(currentResponse);
        }
        if (!currentResponse.aiMessage().hasToolExecutionRequests()) {
            return Mono.just(currentResponse);
        }

        // Defer side effects (messages.add, tool executions, followUp scoreTrace) until
        // subscription. Early returns above are pure cold Mono.just and don't need defer.
        return Mono.defer(() -> {
            messages.add(currentResponse.aiMessage());

            // concatMap (not flatMap) so tool executions within a round preserve order — same
            // contract as the trace-level scorer.
            Flux<ToolExecutionResultMessage> roundResults = Flux
                    .fromIterable(currentResponse.aiMessage().toolExecutionRequests())
                    .concatMap(toolExecRequest -> executeToolOrBudgetExhausted(round, toolExecRequest, message,
                            ctx, budget, mdc));

            return roundResults
                    .doOnNext(messages::add)
                    .then(Mono.defer(() -> {
                        var followUp = toolRequest.toBuilder()
                                .messages(new ArrayList<>(messages))
                                .parameters(followUpParameters)
                                .build();
                        return scoreTraceReactive(followUp, message);
                    }))
                    .flatMap(nextResponse -> toolCallLoop(round + 1, nextResponse, toolRequest, followUpParameters,
                            message, messages, ctx, budget, mdc));
        });
    }

    private Mono<ToolExecutionResultMessage> executeToolOrBudgetExhausted(int round,
            ToolExecutionRequest toolExecRequest, TraceThreadToScoreLlmAsJudge message,
            TraceToolContext ctx, ToolOutputBudget budget, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            log.debug("Tool call round '{}' for ruleId '{}': tool '{}'",
                    round, message.ruleId(), toolExecRequest.name());
            if (budget.cumulative >= CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS) {
                if (!budget.exhaustedLogged) {
                    log.warn("Tool-output budget '{}' chars exhausted for ruleId '{}';"
                            + " subsequent tool calls return budget-exhausted sentinel",
                            CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS, message.ruleId());
                    budget.exhaustedLogged = true;
                }
                return Mono.just(ToolExecutionResultMessage.from(toolExecRequest,
                        BUDGET_EXHAUSTED_MESSAGE.formatted(CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS)));
            }
        }
        return toolRegistry.execute(toolExecRequest.name(), toolExecRequest.arguments(), ctx)
                .map(result -> {
                    budget.cumulative += result.length();
                    return ToolExecutionResultMessage.from(toolExecRequest, result);
                });
    }

    /** Shared per-evaluation tool-output budget state. Mutated sequentially via concatMap. */
    private static final class ToolOutputBudget {
        long cumulative = 0L;
        boolean exhaustedLogged = false;
    }

    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools) {
    }
}
