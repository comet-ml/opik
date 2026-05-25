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
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
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

        // Spans are fetched here, in the reactive chain, only when they'll actually be consumed
        // downstream — either to pre-seed the agentic-tools cache (provider supports tools AND
        // experimentId branch OR size-based toggle is on) OR to substitute into a {{spans}}
        // template variable on the inline path (sentinel: any variable mapped to the bare
        // string "spans"). Skip the I/O otherwise. Keeping the fetch reactive and out of
        // evaluate() avoids the .block() pattern that pinned a workersScheduler thread for the
        // upstream wait (OPIK-6308).
        Mono<List<Span>> spansMono = shouldFetchSpans(message)
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
                                userFacingLogger.info("Received response for traceId '{}': '{}'",
                                        trace.id(), OnlineScoringEngine.summarizeResponse(chatResponse));
                            }
                        }))
                        .flatMap(initialResponse -> prepared.useTools()
                                ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                        prepared.structuredRequest(), message, spans, prepared.fullJson(), mdc)
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
            //
            // Build the full JSON ONCE here when we'll need the size estimate; if useTools
            // resolves true, handleToolCalls reuses this same JsonNode to pre-seed the cache
            // instead of rebuilding. Saves a full trace+spans serialization on every big-trace
            // run (the most CPU-/GC-expensive part of routing).
            String modelName = message.llmAsJudgeCode().model().name();
            JsonNode fullJson = traceCompressor.buildFullJson(trace, spans);
            int estimatedContextTokens = OnlineScoringEngine.estimateTokensFromJson(
                    fullJson, onlineScoringConfig.getAgenticToolsCharsPerToken());
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
                            message.promptType(), MAX_PROMPT_FIELD_CHARS, drillDownHint, spans);
                    // The post-tool-loop wrap-up must use the provider-native structured-output
                    // strategy (e.g. response_format=json_schema on OpenAI). InstructionStrategy
                    // is a soft prompt and Anthropic in particular often returns conversational
                    // prose at the wrap-up turn ("Now let me check..."), which then fails JSON
                    // parsing in toFeedbackScores and yields zero scores.
                    structuredRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), MAX_PROMPT_FIELD_CHARS, drillDownHint, spans);
                } else {
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), spans);
                    structuredRequest = scoreRequest;
                }
            } catch (Exception exception) {
                OnlineScoringEngine.logPreparingLlmRequestError(userFacingLogger, log, "traceId",
                        trace.id(), exception);
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
                scoreRequest = OnlineScoringEngine.addToolSpecs(scoreRequest, ToolChoice.REQUIRED, toolRegistry);
            }

            // summarizeRequest is cheap (no per-message toString streaming since the chars-count
            // field was removed). At INFO so operators watching their rule's UI logs see a
            // matching "Sending" line between "Evaluating" and "Received response".
            userFacingLogger.info("Sending traceId '{}' to LLM: {}",
                    trace.id(), OnlineScoringEngine.summarizeRequest(scoreRequest,
                            message.llmAsJudgeCode().model().name(), useTools));

            // fullJson is only useful downstream on the agentic-tools path (handleToolCalls
            // pre-seeds it into the tool cache). On the inline path we discard it — we already
            // paid for the build to compute the size estimate; skipping the carry avoids
            // holding a potentially-multi-MB JsonNode on the chain for an evaluation that
            // doesn't consume it.
            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools,
                    useTools ? fullJson : null);
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

    private static boolean shouldUseTools(TraceToScoreLlmAsJudge message) {
        return LlmAsJudgeToolsMode.shouldUseTools(message);
    }

    /**
     * Routing decision for whether to fetch the trace's spans before running the LLM call.
     * Spans are needed in two cases:
     * <ul>
     *   <li>The agentic-tools path is possible (provider supports tools AND the experimentId
     *       branch is on OR the size-based toggle is enabled) — spans pre-seed the read-tool
     *       cache so the in-loop {@code get_trace_spans} call doesn't redo the fetch.
     *   <li>The inline prompt template references {@code {{spans}}} — see
     *       {@link OnlineScoringEngine#templateReferencesSpans} for both opt-in shapes
     *       (sentinel-valued variable AND implicit template reference). <strong>Gated by
     *       {@code isAgenticToolsEnabled}</strong>: both pathways ship under the same flag.
     * </ul>
     *
     * <p><strong>Toggle semantics — important and not symmetric:</strong> this method gates
     * the {@code spanService.getByTraceIds} I/O. It does <em>not</em> gate the substitution
     * itself — {@link OnlineScoringEngine#injectSpansIntoReplacements} runs unconditionally
     * inside {@code prepareLlmRequest}. When the toggle is off and a rule still carries a
     * sentinel-mapped {@code spans} variable (e.g. saved by an older FE before the toggle
     * flipped), {@code {{spans}}} renders as the empty JSON array {@code []} via the empty
     * spans list threaded through. We do <em>not</em> gate the substitution because doing so
     * would let the sentinel value {@code "spans"} leak through {@code toReplacements}'
     * literal-value fallback and render the bare word {@code spans} in the prompt — worse
     * UX than {@code []}. Net behavior with toggle off:
     * <ul>
     *   <li>Existing rules with sentinel mapping → {@code Spans: []}
     *   <li>New rules created via the FE → FE skips the auto-fill, user maps {@code spans}
     *       like any other variable, BE renders whatever path they pick.
     *   <li>Experiment-id (test-suite assertion) path → agentic-tools fires regardless, so
     *       spans are still fetched and {@code {{spans}}} substitutes the actual JSON.
     * </ul>
     */
    // Package-private for unit tests.
    boolean shouldFetchSpans(TraceToScoreLlmAsJudge message) {
        String modelName = message.llmAsJudgeCode().model().name();
        boolean agenticToolsPathPossible = OnlineScoringEngine.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName))
                && (LlmAsJudgeToolsMode.shouldUseTools(message)
                        || serviceTogglesConfig.isAgenticToolsEnabled());
        boolean templateNeedsSpans = serviceTogglesConfig.isAgenticToolsEnabled()
                && OnlineScoringEngine.templateReferencesSpans(
                        message.llmAsJudgeCode().messages(),
                        message.llmAsJudgeCode().variables(),
                        message.promptType());
        return agenticToolsPathPossible || templateNeedsSpans;
    }

    /**
     * Routing decision for whether to attach tool specs + run the tool-call loop. Tools fire
     * when EITHER (a) the experimentId-driven branch applies (test-suite assertion) OR
     * (b) the size-based branch applies (toggle on, context above threshold) — AND the
     * provider supports tool-calling. Without the provider check, a non-tool-calling model
     * (Ollama / Custom / OpikFree) selected via {@code test_suite_model} metadata would
     * crash inside the LangChain4j chat call when the request carries {@code toolSpecifications}.
     *
     * <p>When the experimentId path wants tools but the provider can't handle them, we fall
     * back to the inline path with a user-facing warn — assertions that depend on tool-driven
     * span inspection won't be reliable for that model, and surfacing the misconfiguration
     * loudly is better than the silent crash the old code produced.
     *
     * <p>Side-effects: emits user-facing diagnostic logs whenever the decision is non-trivial,
     * so operators can correlate the routing with the trace.
     */
    // Package-private for unit tests.
    boolean shouldUseAgenticTools(TraceToScoreLlmAsJudge message, int estimatedContextTokens, String modelName) {
        boolean experimentIdPath = LlmAsJudgeToolsMode.shouldUseTools(message);
        boolean providerSupportsTools = OnlineScoringEngine.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        boolean overSizeThreshold = serviceTogglesConfig.isAgenticToolsEnabled()
                && estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        boolean wantsTools = experimentIdPath || overSizeThreshold;
        boolean useTools = wantsTools && providerSupportsTools;

        if (experimentIdPath && !providerSupportsTools) {
            userFacingLogger.warn(
                    "Test-suite assertion for traceId '{}' selected model '{}' which does not support tool"
                            + " calling; falling back to inline path — assertions that depend on tool-driven"
                            + " span inspection won't work for this model. Pick a tool-calling provider"
                            + " (OpenAI / Anthropic / Gemini / OpenRouter / Vertex / Bedrock) for the rule.",
                    message.trace().id(), modelName);
        } else if (!experimentIdPath && overSizeThreshold && !providerSupportsTools) {
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

    // Package-private for unit tests.
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceToScoreLlmAsJudge message, List<Span> spans,
            JsonNode fullJson, Map<String, String> mdc) {

        AiMessage aiMessage = chatResponse.aiMessage();
        if (!aiMessage.hasToolExecutionRequests()) {
            return Mono.just(chatResponse);
        }

        // Defer everything below to subscription time so context + cache pre-seed + message
        // list allocation happen exactly once per subscription. The early Mono.just above is
        // cold and pure, so it doesn't need to be inside the defer.
        return Mono.defer(() -> {
            var trace = message.trace();
            var ctx = TraceToolContext.forActiveTrace(trace, spans, message.workspaceId(), message.userName());
            // Pre-seed the active trace into the cache using the JSON that prepareEvaluation
            // already built for the size estimate — saves a redundant traceCompressor.buildFullJson
            // call on big-trace evaluations. Fall back to rebuilding if the caller didn't supply
            // one (e.g. unit tests that call handleToolCalls directly).
            ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()),
                    fullJson != null ? fullJson : traceCompressor.buildFullJson(trace, spans));

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

            var messages = new ArrayList<ChatMessage>(toolRequest.messages());
            var budget = new ToolCallLoop.Budget();

            return ToolCallLoop.runWithWrapUp(
                    chatResponse, toolRequest, structuredRequest, followUpParameters, toolRegistry,
                    request -> scoreTraceReactive(request, message),
                    messages, ctx, budget, trace.id().toString(), mdc);
        });
    }

    /**
     * Carry from {@link #prepareEvaluation} to {@link #evaluate}. {@code fullJson} is the
     * pre-built {@code {trace, spans}} JSON used both for the size estimate and (when
     * {@code useTools} is true) for pre-seeding the tool context's cache — null on the
     * inline path so we don't hold a multi-MB JsonNode for evaluations that won't consume it.
     */
    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools,
            JsonNode fullJson) {
    }

}
