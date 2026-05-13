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
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.data.message.AiMessage;
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
     * Total cap applied to the rendered {@code {{context}}} substitution on the agentic-tools
     * path. Sized larger than the per-variable trace-level cap (4 KB) because a thread context
     * aggregates many messages — 8 KB chars (~ 2 K tokens) keeps small/medium threads inline
     * while forcing tool-routed drill-in for large ones. The agent recovers full content via
     * {@code read(type=trace, id=<thread-trace-id>)}.
     */
    private static final int MAX_THREAD_CONTEXT_CHARS = 8_000;

    /**
     * Cumulative cap on tool-result string length across the whole tool-call loop. Identical
     * sizing rationale to {@link OnlineScoringLlmAsJudgeScorer#CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS}
     * — 150 K chars / 2 chars/tok ≈ 75 K tok of tool results, leaving ~ 50 K tok headroom for
     * the rest of the conversation in a 128 K window.
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
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final ToolRegistry toolRegistry;
    private final OnlineScoringConfig onlineScoringConfig;

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
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.toolRegistry = toolRegistry;
        this.onlineScoringConfig = config;
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
     * guarantees {@code traces} is non-empty. When the rendered thread context exceeds the
     * configured token threshold and the provider supports tool calling, routes through the
     * agentic-tools loop (read / jq / search / get_trace_spans) instead of pasting the full
     * conversation inline.
     */
    private List<FeedbackScoreBatchItemThread> evaluate(TraceThreadToScoreLlmAsJudge message, List<Trace> traces,
            UUID threadModelId, String threadId, AutomationRuleEvaluator<?, ?> rule, Map<String, String> mdc) {
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating threadId '{}' sampled by rule '{}'", threadId, rule.getName());

            Project project = projectService.get(message.projectId(), message.workspaceId());

            String modelName = message.code().model().name();
            int estimatedContextTokens = OnlineScoringEngine.estimateThreadContextTokens(traces);
            boolean useTools = shouldUseAgenticTools(message, threadId, estimatedContextTokens, modelName);

            ChatRequest scoreRequest;
            ChatRequest structuredRequest;
            try {
                if (useTools) {
                    String drillDownHint = ("call read(type=trace, id=<thread-trace-id>, tier=MEDIUM) for"
                            + " full structure, get_trace_spans(trace_id=<thread-trace-id>) for the"
                            + " sub-span overview, or jq(type=trace, id=<thread-trace-id>,"
                            + " expression='<path>') for path-targeted lookups");
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(
                            message.code(), traces, new InstructionStrategy(),
                            MAX_THREAD_CONTEXT_CHARS, drillDownHint);
                    // Provider-native structured-output strategy for the wrap-up turn — see
                    // OnlineScoringLlmAsJudgeScorer.evaluate for the same rationale.
                    structuredRequest = OnlineScoringEngine.prepareThreadLlmRequest(
                            message.code(), traces,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            MAX_THREAD_CONTEXT_CHARS, drillDownHint);
                } else {
                    var strategy = llmProviderFactory.getStructuredOutputStrategy(modelName);
                    scoreRequest = OnlineScoringEngine.prepareThreadLlmRequest(message.code(), traces, strategy);
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for threadId '{}': \n\n{}",
                        threadId, exception.getMessage());
                throw exception;
            }

            if (useTools) {
                // REQUIRED on the FIRST call only: forces ≥1 tool call before the model can
                // emit an answer. Mirrors OnlineScoringLlmAsJudgeScorer.evaluate — provider
                // bias (especially OpenAI) is to skip tool calls and answer from visible
                // context, even with explicit instructions. Follow-up rounds switch to AUTO
                // in handleToolCalls so the model can decide when to stop investigating.
                scoreRequest = addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            userFacingLogger.info("Sending threadId '{}' to LLM using the following input:\n\n{}",
                    threadId, scoreRequest);

            var chatResponse = aiProxyService.scoreTrace(scoreRequest, message.code().model(), message.workspaceId());
            userFacingLogger.info("Received response for threadId '{}':\n\n{}", threadId, chatResponse);

            if (useTools) {
                chatResponse = handleToolCalls(chatResponse, scoreRequest, structuredRequest, message, traces);
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
    }

    /**
     * Routing decision for whether to attach tool specs + run the tool-call loop. Mirrors
     * {@link OnlineScoringLlmAsJudgeScorer#shouldUseAgenticTools} but without the experimentId
     * branch — trace-thread scoring never runs in test-suite-assertion mode, so the only path
     * to tool calling is the size-based gate (toggle on, context above threshold, provider
     * supports tools).
     */
    boolean shouldUseAgenticTools(TraceThreadToScoreLlmAsJudge message, String threadId, int estimatedContextTokens,
            String modelName) {
        boolean providerSupportsTools = OnlineScoringEngine.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        boolean overSizeThreshold = serviceTogglesConfig.isAgenticToolsEnabled()
                && estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        boolean useTools = overSizeThreshold && providerSupportsTools;

        if (overSizeThreshold && !providerSupportsTools) {
            userFacingLogger.warn(
                    "Thread context exceeds '{}' tokens but provider for model '{}' does not support tool"
                            + " calling; falling back to inline path — may overflow context window.",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), modelName);
        } else if (useTools) {
            userFacingLogger.info(
                    "Thread context exceeds '{}' tokens; switching to agentic-tools mode for threadId '{}'",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), threadId);
        }
        return useTools;
    }

    // Package-private for unit tests.
    ChatRequest addToolSpecs(ChatRequest request, ToolChoice toolChoice) {
        // Layer tool specs on top of the existing parameters via overrideWith — same pattern as
        // OnlineScoringLlmAsJudgeScorer.addToolSpecs.
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
                .parameters(parameters)
                .build();
    }

    // Package-private for unit tests.
    ChatResponse handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceThreadToScoreLlmAsJudge message, List<Trace> traces) {

        AiMessage aiMessage = chatResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            return chatResponse;
        }

        // Thread mode: no pre-seeded "active trace" or spans — the agent navigates by trace ids
        // from the {{context}} skeleton, and ReadTool populates the cache lazily on first hit.
        var ctx = TraceToolContext.forThread(traces, message.workspaceId(), message.userName());
        var messages = new ArrayList<>(toolRequest.messages());

        // Follow-up rounds switch to AUTO so the wrap-up turn isn't forced to call another tool.
        var followUpParameters = ChatRequestParameters.builder()
                .overrideWith(toolRequest.parameters())
                .toolChoice(ToolChoice.AUTO)
                .build();

        long toolOutputCumulative = 0L;
        boolean budgetExhaustedLogged = false;

        for (int round = 0; round < MAX_TOOL_CALL_ROUNDS; round++) {
            if (!chatResponse.aiMessage().hasToolExecutionRequests()) {
                break;
            }

            messages.add(chatResponse.aiMessage());

            for (var toolExecRequest : chatResponse.aiMessage().toolExecutionRequests()) {
                log.debug("Tool call round '{}' for threadIds '{}': tool '{}'",
                        round, message.threadIds(), toolExecRequest.name());
                String result;
                if (toolOutputCumulative >= CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS) {
                    if (!budgetExhaustedLogged) {
                        log.warn("Tool-output budget '{}' chars exhausted for threadIds '{}';"
                                + " subsequent tool calls return budget-exhausted sentinel",
                                CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS, message.threadIds());
                        budgetExhaustedLogged = true;
                    }
                    result = BUDGET_EXHAUSTED_MESSAGE.formatted(CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS);
                } else {
                    result = toolRegistry.execute(
                            toolExecRequest.name(), toolExecRequest.arguments(), ctx);
                    toolOutputCumulative += result.length();
                }
                messages.add(ToolExecutionResultMessage.from(toolExecRequest, result));
            }

            var followUp = toolRequest.toBuilder()
                    .messages(new ArrayList<>(messages))
                    .parameters(followUpParameters)
                    .build();

            chatResponse = aiProxyService.scoreTrace(
                    followUp, message.code().model(), message.workspaceId());
        }

        // Force closure of the tool-using phase — same rationale as the trace-level scorer.
        messages.add(UserMessage.from(
                "You have completed your investigation using the available tools."
                        + " Now respond with ONLY the JSON object specified in the original instructions."
                        + " Do not call any more tools. Do not include any prose, commentary, or markdown"
                        + " fences — emit only the raw JSON object."));

        var finalRequest = structuredRequest.toBuilder()
                .messages(new ArrayList<>(messages))
                .build();

        return aiProxyService.scoreTrace(
                finalRequest, message.code().model(), message.workspaceId());
    }
}
