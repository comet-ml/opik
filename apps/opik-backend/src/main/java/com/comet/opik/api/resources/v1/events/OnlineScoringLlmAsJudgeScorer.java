package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.EntityRef;
import com.comet.opik.api.resources.v1.events.tools.EntityType;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceToScoreLlmAsJudge> {

    private static final int MAX_TOOL_CALL_ROUNDS = 10;

    /**
     * Per-variable substitution cap for the test-suite-assertion (tool-enabled) path. ≈ 4 KB chars
     * (~ 1 K tokens via the {@code Tokens.estimate} convention) is large enough that small trace
     * input/output blobs render inline (cheap, no tool round-trip) but small enough that a huge
     * trace doesn't blow context — the agent fetches the rest via the {@code read} tool.
     */
    private static final int MAX_PROMPT_FIELD_CHARS = 4_000;

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final SpanService spanService;
    private final ToolRegistry toolRegistry;
    private final TraceCompressor traceCompressor;
    private final WorkspaceNameService workspaceNameService;
    private final OpikConfiguration opikConfiguration;
    private final OnlineScoringConfig onlineScoringConfig;
    private final ServiceTogglesConfig serviceTogglesConfig;

    @Inject
    public OnlineScoringLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull TestSuiteAssertionCounterService testSuiteAssertionCounterService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull SpanService spanService,
            @NonNull ToolRegistry toolRegistry,
            @NonNull TraceCompressor traceCompressor,
            @NonNull WorkspaceNameService workspaceNameService,
            @NonNull OpikConfiguration opikConfiguration) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.spanService = spanService;
        this.toolRegistry = toolRegistry;
        this.traceCompressor = traceCompressor;
        this.workspaceNameService = workspaceNameService;
        this.opikConfiguration = opikConfiguration;
        this.onlineScoringConfig = config;
        this.serviceTogglesConfig = serviceTogglesConfig;
    }

    /**
     * Resolves the workspaceName for the post-scoring chain. Needed because
     * {@link com.comet.opik.domain.ExperimentService#finishExperiments(Set)} reads
     * {@code WORKSPACE_NAME} from the reactive context, but {@link TraceToScoreLlmAsJudge}
     * only carries {@code workspaceId}. {@link WorkspaceNameService#getWorkspaceName}
     * is {@code @Cacheable} keyed on workspaceId, so subsequent calls per workspace
     * are free. On lookup failure we fall back to {@code workspaceId} so the chain
     * still completes — finishing the experiment matters more than the name being pretty.
     */
    private String resolveWorkspaceName(String workspaceId) {
        try {
            return workspaceNameService.getWorkspaceName(workspaceId,
                    opikConfiguration.getAuthentication().getReactService().url());
        } catch (Exception e) {
            log.warn("Failed to resolve workspaceName for '{}', falling back to using workspace id. Error: {}",
                    workspaceId, e.getMessage());
            return workspaceId;
        }
    }

    @Override
    protected Mono<Void> processEvent(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            // Resolve workspaceName lazily on subscription. ExperimentService.finishExperiments
            // (reached via decrementAndFinishIfComplete when the assertion counter hits zero) reads
            // WORKSPACE_NAME from the reactive context; without it the post-scoring chain throws
            // NoSuchElementException, the message isn't ack'd, and Redis Streams retries the whole
            // scoring run — re-running the LLM and re-inserting assertion rows.
            return super.processEvent(message)
                    .then(Mono.fromCallable(() -> resolveWorkspaceName(message.workspaceId()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(workspaceName -> testSuiteAssertionCounterService
                                    .decrementAndFinishIfComplete(message.workspaceId(), experimentId)
                                    .contextWrite(ctx -> ctx
                                            .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                            .put(RequestContext.WORKSPACE_NAME, workspaceName)
                                            .put(RequestContext.USER_NAME, message.userName()))));
        }
        return super.processEvent(message);
    }

    @Override
    protected Mono<Void> score(@NonNull TraceToScoreLlmAsJudge message) {
        var trace = message.trace();
        log.info("Message received with traceId '{}', userName '{}', to be scored in '{}'",
                trace.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.TRACE_ID, trace.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        // Spans are fetched here, in the reactive chain, only when the agentic-tools path could
        // fire — either the experimentId-driven branch is on (tools always attached) or the
        // toggle is on and the provider supports tools (the size-based branch may attach tools).
        // Keeping the fetch reactive and out of evaluate() avoids the .block() pattern that
        // pinned a workersScheduler thread for the upstream wait (OPIK-6308). When neither
        // condition holds, an empty list is enough — the size estimate stays below threshold,
        // useTools resolves to false, and handleToolCalls isn't reached.
        String modelName = message.llmAsJudgeCode().model().name();
        boolean spansNeeded = LlmAsJudgeToolsMode.shouldUseTools(message)
                || (serviceTogglesConfig.isAgenticToolsEnabled()
                        && OnlineScoringEngine.supportsToolCalling(
                                llmProviderFactory.getLlmProvider(modelName)));
        Mono<List<Span>> spansMono = spansNeeded
                ? spanService.getByTraceIds(Set.of(trace.id()))
                        .collectList()
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                .put(RequestContext.USER_NAME, message.userName()))
                : Mono.just(List.of());

        return spansMono
                .flatMap(spans -> evaluate(message, spans, mdc))
                .flatMap(scores -> storeScores(scores, trace, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for traceId '{}' stored successfully:\n\n{}", trace.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring traceId '{}' with rule '{}': \n\n{}",
                                trace.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    private Mono<List<FeedbackScoreBatchItem>> evaluate(TraceToScoreLlmAsJudge message, List<Span> spans,
            Map<String, String> mdc) {
        var trace = message.trace();
        // Sync prep is CPU-bound (JSON serialization for the size estimate + prompt rendering)
        // — schedule on Schedulers.parallel() so we don't tax the R2DBC scheduler that emits
        // the spans fetch upstream, and don't pin a workersScheduler thread on the inline path.
        // MDC is applied inside prepareEvaluation via try-with-resources; reactive operators
        // below re-apply MDC via withMdc() because they may run on a different thread than the
        // boundedElastic worker that emitted the chat response.
        return Mono.fromCallable(() -> prepareEvaluation(message, spans, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> scoreTraceReactive(prepared.scoreRequest(), message)
                        .doOnNext(withMdc(mdc, chatResponse -> {
                            if (userFacingLogger.isInfoEnabled()) {
                                userFacingLogger.info("Received response for traceId '{}': {}",
                                        trace.id(), summarizeResponse(chatResponse));
                            }
                        }))
                        .flatMap(initialResponse -> prepared.useTools()
                                ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                        prepared.structuredRequest(), message, spans, mdc)
                                : Mono.just(initialResponse)))
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        // When scoreNameMapping is empty (regular online scoring), names pass through unchanged.
                        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse);
                        OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "traceId", trace.id());
                        return parsed.scores().stream()
                                .map(item -> {
                                    String scoreName = item.name();
                                    if (message.scoreNameMapping().containsKey(scoreName)) {
                                        scoreName = message.scoreNameMapping().get(scoreName);
                                    }
                                    return (FeedbackScoreBatchItem) item.toBuilder()
                                            .name(scoreName)
                                            .categoryName(message.categoryName())
                                            .id(trace.id())
                                            .projectId(trace.projectId())
                                            .projectName(trace.projectName())
                                            .build();
                                })
                                .toList();
                    }
                });
    }

    private PreparedEvaluation prepareEvaluation(TraceToScoreLlmAsJudge message, List<Span> spans,
            Map<String, String> mdc) {
        var trace = message.trace();
        // Logging tags for the rule + trace; covers every userFacingLogger call in this sync prep.
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            // Spans were fetched reactively in score() and threaded through, so handleToolCalls
            // can seed the read-tool cache without a second query. estimateTraceContextTokens
            // works on the {trace, spans} composite — small trace with huge spans still trips
            // the size-based agentic-tools branch.
            String modelName = message.llmAsJudgeCode().model().name();
            int estimatedContextTokens = OnlineScoringEngine.estimateTraceContextTokens(
                    trace, spans, traceCompressor, onlineScoringConfig.getAgenticToolsCharsPerToken());
            boolean useTools = shouldUseAgenticTools(message, estimatedContextTokens, modelName);

            ChatRequest scoreRequest;
            ChatRequest structuredRequest;
            try {
                if (useTools) {
                    // Tools path: cap variable substitutions so huge trace input/output JSON doesn't
                    // pre-load context. The agent has read/jq tools to drill in on demand. Drill-hint
                    // points the model at MEDIUM tier (path-truncated, structurally complete) and jq
                    // for path-targeted lookups — never tier=FULL, which can blow context on huge
                    // traces. ReadTool will silently downgrade tier=FULL anyway, but this avoids a
                    // wasted round.
                    String drillDownHint = ("call read(type=trace, id=%s, tier=MEDIUM) for full structure"
                            + " with per-string truncation hints, or jq(type=trace, id=%s,"
                            + " expression='<path>') for a specific section")
                            .formatted(trace.id(), trace.id());
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace, new InstructionStrategy(),
                            message.promptType(), MAX_PROMPT_FIELD_CHARS, drillDownHint);
                    // The post-tool-loop wrap-up must use the provider-native structured-output
                    // strategy (e.g. response_format=json_schema on OpenAI). InstructionStrategy
                    // is a soft prompt and Anthropic in particular often returns conversational
                    // prose at the wrap-up turn ("Now let me check..."), which then fails JSON
                    // parsing in toFeedbackScores and yields zero scores.
                    structuredRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), MAX_PROMPT_FIELD_CHARS, drillDownHint);
                } else {
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType());
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for traceId '{}': \n\n{}",
                        trace.id(), exception.getMessage());
                throw exception;
            }

            if (useTools) {
                // REQUIRED on the FIRST call only: forces ≥1 tool call before the model can
                // emit an answer. OpenAI judges with tool_choice=AUTO consistently skip the
                // tool loop and answer from visible context, even with explicit "you MUST
                // call tools first" guidance in the system prompt — see SupportedJudgeProvider
                // for the empirical asymmetry. Follow-up rounds in handleToolCalls switch to
                // AUTO so the model can decide when it has enough info to stop investigating;
                // a uniform REQUIRED would loop forever because the wrap-up turn would also
                // be forced to call a tool.
                scoreRequest = addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            // Guarded behind isInfoEnabled() because summarizeRequest streams over the message
            // list to total up character counts; SLF4J's parameter substitution defers the
            // {} placeholder, but the helper invocation itself is still evaluated eagerly.
            if (userFacingLogger.isInfoEnabled()) {
                userFacingLogger.info("Sending traceId '{}' to LLM: {}",
                        trace.id(), summarizeRequest(scoreRequest, message));
            }

            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools);
        }
    }

    /**
     * Wraps the synchronous {@code ChatLanguageModel.chat} call in a Mono and schedules it
     * on {@link Schedulers#boundedElastic()} so the blocking Jersey-client I/O doesn't
     * pin the per-stream worker scheduler thread (OPIK-6308).
     */
    private Mono<ChatResponse> scoreTraceReactive(ChatRequest request, TraceToScoreLlmAsJudge message) {
        return Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.llmAsJudgeCode().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
    }

    // Package-private for unit tests.
    ChatRequest addToolSpecs(ChatRequest request, ToolChoice toolChoice) {
        // Tool specs live inside ChatRequestParameters, so we copy the existing parameters via
        // overrideWith and layer tool specs on top — setting toolSpecifications directly on
        // ChatRequest's builder would conflict with parameters. Using toBuilder() (rather than
        // a fresh builder + .messages()) preserves any other top-level fields on ChatRequest,
        // present or future, guarding against the same "silently dropped fields" regression
        // that previously gave the initial scoring call a different shape from the final
        // structured re-issue in handleToolCalls.
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
                .parameters(parameters)
                .build();
    }

    private static boolean shouldUseTools(TraceToScoreLlmAsJudge message) {
        return LlmAsJudgeToolsMode.shouldUseTools(message);
    }

    /**
     * Routing decision for whether to attach tool specs + run the tool-call loop. Tools fire
     * when either the experimentId-driven branch applies (test-suite assertion) or the
     * size-based branch applies (toggle on, context above threshold, provider supports tools).
     * Side-effects: emits one of two user-facing diagnostic logs when the size branch is
     * non-trivial, so operators can correlate the routing decision with the trace.
     */
    // Package-private for unit tests.
    boolean shouldUseAgenticTools(TraceToScoreLlmAsJudge message, int estimatedContextTokens, String modelName) {
        boolean experimentIdPath = LlmAsJudgeToolsMode.shouldUseTools(message);
        boolean providerSupportsTools = OnlineScoringEngine.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        boolean overSizeThreshold = serviceTogglesConfig.isAgenticToolsEnabled()
                && estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        boolean useTools = experimentIdPath || (overSizeThreshold && providerSupportsTools);

        if (!experimentIdPath && overSizeThreshold && !providerSupportsTools) {
            userFacingLogger.warn(
                    "Trace context exceeds '{}' tokens but provider for model '{}' does not support tool"
                            + " calling; falling back to inline path — may overflow context window.",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), modelName);
        } else if (!experimentIdPath && overSizeThreshold && useTools) {
            userFacingLogger.info(
                    "Trace context exceeds '{}' tokens; switching to agentic-tools mode for traceId '{}'",
                    onlineScoringConfig.getAgenticToolsThresholdTokens(), message.trace().id());
        }
        return useTools;
    }

    /**
     * Cumulative cap on tool-result string length across the whole tool-call loop. Beyond
     * this, further tool calls return a budget-exhausted sentinel so the judge has to compose
     * its final answer from already-gathered data. Sized for ~2 chars/token (random/code
     * worst case) so even adversarial inputs stay under a 128 K-token window after accounting
     * for system prompt, tool specs, user prompt, and assistant turns:
     *
     * <p>{@code 150_000 chars / 2 chars/tok ≈ 75 K tok} of tool results, leaving ≈ 50 K tok
     * of headroom for the rest of the conversation. Pairs with
     * {@link com.comet.opik.api.resources.v1.events.tools.ReadTool#OUTPUT_SAFETY_CHARS}
     * (per-call cap with auto-tier-downgrade): up to ~7 max-cap reads fit before this cap.
     */
    static final long CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS = 150_000L;

    private static final String BUDGET_EXHAUSTED_MESSAGE = "{\"error\": \"Cumulative tool-output"
            + " budget (%d chars) exhausted for this judgment; further tool calls return this"
            + " error. Respond now with your best assessment from the data already gathered.\"}";

    // Package-private for unit tests.
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceToScoreLlmAsJudge message, List<Span> spans,
            Map<String, String> mdc) {

        AiMessage aiMessage = chatResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            return Mono.just(chatResponse);
        }

        var trace = message.trace();
        var ctx = new TraceToolContext(trace, spans, message.workspaceId(), message.userName());
        // Pre-seed the active trace into the cache so read/jq/search can hit it without re-fetching.
        ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()),
                traceCompressor.buildFullJson(trace, spans));

        // Subsequent rounds use tool_choice=AUTO so the model can decide when it has enough
        // information to stop investigating. The initial call uses REQUIRED to force ≥1 tool
        // call (see prepareEvaluation() — overcomes OpenAI's bias against calling tools when it
        // can satisfy the output schema from visible context). If we kept REQUIRED on follow-ups,
        // the wrap-up turn would loop forever, since every round would be forced to invoke
        // a tool even after the model is ready to emit the final JSON.
        var followUpParameters = ChatRequestParameters.builder()
                .overrideWith(toolRequest.parameters())
                .toolChoice(ToolChoice.AUTO)
                .build();

        // Shared mutable state. handleToolCalls runs once per evaluation; the recursive
        // toolCallLoop chains rounds sequentially via flatMap and the inner tool dispatch
        // uses concatMap, so concurrent mutation can't occur here.
        var messages = new ArrayList<ChatMessage>(toolRequest.messages());
        var budget = new ToolOutputBudget();

        return toolCallLoop(0, chatResponse, toolRequest, followUpParameters, message, messages, ctx, budget, mdc)
                .flatMap(loopFinalResponse -> {
                    // Force closure of the tool-using phase. Without this, the model can return prose
                    // that continues the investigation ("Now let me check...") instead of the required
                    // JSON, even when the request carries no tool specs. Combined with the provider-native
                    // structured-output strategy on `structuredRequest`, this gives both a soft and a
                    // hard signal: "stop calling tools, emit only JSON now".
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
    }

    private Mono<ChatResponse> toolCallLoop(int round, ChatResponse currentResponse, ChatRequest toolRequest,
            ChatRequestParameters followUpParameters, TraceToScoreLlmAsJudge message,
            ArrayList<ChatMessage> messages, TraceToolContext ctx, ToolOutputBudget budget,
            Map<String, String> mdc) {
        if (round >= MAX_TOOL_CALL_ROUNDS) {
            return Mono.just(currentResponse);
        }
        if (!currentResponse.aiMessage().hasToolExecutionRequests()) {
            return Mono.just(currentResponse);
        }

        var trace = message.trace();

        // Defer all side effects (the messages.add below, the tool executions, the followUp
        // scoreTrace) until subscription. The early returns above are pure (cold Mono.just),
        // so they don't need to live inside the defer.
        return Mono.defer(() -> {
            messages.add(currentResponse.aiMessage());

            // concatMap (not flatMap) so tool executions in this round preserve order — the
            // ToolExecutionResultMessages must follow their parent AiMessage in the message
            // list in the same order the model emitted them, matching what OpenAI expects.
            Flux<ToolExecutionResultMessage> roundResults = Flux
                    .fromIterable(currentResponse.aiMessage().toolExecutionRequests())
                    .concatMap(toolExecRequest -> executeToolOrBudgetExhausted(round, toolExecRequest, trace.id(),
                            ctx, budget, mdc));

            return roundResults
                    .doOnNext(messages::add)
                    .then(Mono.defer(() -> {
                        // Defensive copy: ChatRequestBuilder stores the list by reference, so a later
                        // iteration mutating `messages` would retroactively change what an async chat
                        // client sees in this round's request. Snapshot per round.
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
            dev.langchain4j.agent.tool.ToolExecutionRequest toolExecRequest, UUID traceId,
            TraceToolContext ctx, ToolOutputBudget budget, Map<String, String> mdc) {
        // Apply MDC so the slf4j tags (workspace_id, trace_id, rule_id) follow the tool-loop
        // log lines — the reactive chain may have hopped threads since prepareEvaluation set
        // MDC, so we re-apply per call.
        try (var logContext = wrapWithMdc(mdc)) {
            log.debug("Tool call round '{}' for traceId '{}': tool '{}'",
                    round, traceId, toolExecRequest.name());
            if (budget.cumulative >= CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS) {
                if (!budget.exhaustedLogged) {
                    log.warn("Tool-output budget '{}' chars exhausted for traceId '{}';"
                            + " subsequent tool calls return budget-exhausted sentinel",
                            CUMULATIVE_TOOL_OUTPUT_BUDGET_CHARS, traceId);
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

    /**
     * Build a sanitized one-line description of the outgoing request for the user-facing
     * log. The full {@code ChatRequest} contains the rendered system prompt, the user
     * message with the trace's input/output, and request parameters — surfacing all of it
     * in a stored log lands trace content (and any tokens or PII it carries) in clear
     * text downstream of whatever sinks the user-facing log feeds. We log shape only.
     */
    private static String summarizeRequest(ChatRequest request, TraceToScoreLlmAsJudge message) {
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        int totalChars = request.messages() == null
                ? 0
                : request.messages().stream().mapToInt(m -> m.toString().length()).sum();
        int toolSpecCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        return String.format("model='%s', messages=%d (~%d chars), tools=%d, toolsEnabled=%s",
                message.llmAsJudgeCode().model().name(),
                messageCount, totalChars, toolSpecCount, shouldUseTools(message));
    }

    /**
     * Build a sanitized one-line description of the LLM response. As with the request,
     * the full {@code ChatResponse} contains the assistant text and any tool-call
     * arguments, both of which can echo trace content the model is reasoning about.
     */
    private static String summarizeResponse(ChatResponse response) {
        var ai = response.aiMessage();
        int textLength = ai.text() == null ? 0 : ai.text().length();
        int toolCallCount = ai.toolExecutionRequests() == null ? 0 : ai.toolExecutionRequests().size();
        var finishReason = response.metadata() == null ? null : response.metadata().finishReason();
        return String.format("textChars=%d, toolCalls=%d, finishReason=%s",
                textLength, toolCallCount, finishReason);
    }

}
