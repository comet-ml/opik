package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.PromptType;
import com.comet.opik.api.Span;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.events.SpanToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.AttachmentSummaries;
import com.comet.opik.api.resources.v1.events.tools.EntityRef;
import com.comet.opik.api.resources.v1.events.tools.EntityType;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.SPAN_LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * This service listens to a Redis stream for Spans to be scored in a LLM provider. It renders the
 * evaluator's message templates with values from the Span and prepares the structured-output schema.
 *
 * <p>By default scoring is inline (template variables substituted, one LLM call). When a rule's prompt
 * references the {@code {{span}}} structure variable (and the {@code agentic_tools} toggle is on and the
 * provider supports tool-calling), the scorer instead injects a compact span structure (span id + the
 * span's own attachment {@code file_name}s) and runs the agentic tool loop, so the judge can call
 * {@code get_attachment(type=span, ...)} / {@code read} / {@code jq} to load and inspect the span's
 * attachments. Span-level analogue of the {@code {{trace}}} path in {@link OnlineScoringLlmAsJudgeScorer}.
 */
@EagerSingleton
@Slf4j
public class OnlineScoringSpanLlmAsJudgeScorer extends OnlineScoringBaseScorer<SpanToScoreLlmAsJudge> {

    private final ServiceTogglesConfig serviceTogglesConfig;
    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final AgenticScoringService agenticScoringService;
    private final AttachmentService attachmentService;
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;

    @Inject
    public OnlineScoringSpanLlmAsJudgeScorer(@NonNull @Config("onlineScoring") OnlineScoringConfig config,
            @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceTogglesConfig,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull ChatCompletionService aiProxyService,
            @NonNull TraceService traceService,
            @NonNull LlmProviderFactory llmProviderFactory,
            @NonNull AgenticScoringService agenticScoringService,
            @NonNull AttachmentService attachmentService,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder) {
        super(config, redisson, feedbackScoreService, traceService, SPAN_LLM_AS_JUDGE, Constants.SPAN_LLM_AS_JUDGE);
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringSpanLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.agenticScoringService = agenticScoringService;
        this.attachmentService = attachmentService;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
    }

    @Override
    public void start() {
        if (serviceTogglesConfig.isSpanLlmAsJudgeEnabled()) {
            super.start();
        } else {
            log.info("Online Scoring Span LLM as Judge consumer won't start as it is disabled");
        }
    }

    /**
     * Use AI Proxy to score the span and store it as a FeedbackScore.
     * If the evaluator has multiple score definitions, it calls the LLM once per score definition.
     *
     * @param message a Redis message with the Span to score with an Evaluator code, workspace and username.
     */
    @Override
    protected Mono<Void> score(@NonNull SpanToScoreLlmAsJudge message) {
        var span = message.span();
        log.info("Message received with spanId '{}', userName '{}', to be scored in '{}'",
                span.id(), message.userName(), message.llmAsJudgeCode().model().name());

        var mdc = Map.of(
                UserLog.MARKER, UserLog.AUTOMATION_RULE_EVALUATOR.name(),
                UserLog.WORKSPACE_ID, message.workspaceId(),
                UserLog.SPAN_ID, span.id().toString(),
                UserLog.RULE_ID, message.ruleId().toString());

        // The {{span}} variable is the declarative agentic trigger. Detection is independent of the
        // agentic-tools toggle so the variable is always substituted (never leaking the bare "span"
        // sentinel as a literal):
        //   - toggle ON  → build the real span structure (span id + attachment file_names); if the
        //                  provider supports tools, run the agentic loop so the judge can get_attachment.
        //   - toggle OFF → skip the attachment fetch and inject a null structure, so {{span}} renders as
        //                  "{}" inline (handled by the inline branch in prepareEvaluation).
        boolean referencesSpan = OnlineScoringEngine.templateReferencesSpanStructure(
                message.llmAsJudgeCode().messages(),
                message.llmAsJudgeCode().variables(),
                PromptType.MUSTACHE);
        boolean agenticToolsEnabled = serviceTogglesConfig.isAgenticToolsEnabled();

        // Monitoring recorder (OPIK-6994): one hidden source=evaluator trace per span evaluation with an
        // llm span for the scoring call. NOOP when the toggle is off — no extra writes.
        EvaluationRecorder recorder = serviceTogglesConfig.isOnlineScoringTracingEnabled()
                ? onlineEvaluationRecorder.begin(span, message.ruleId(), message.ruleName(),
                        message.llmAsJudgeCode().model().name(), message.workspaceId(), message.userName())
                : EvaluationRecorder.NOOP;

        Mono<List<FeedbackScoreBatchItem>> scoresMono = (referencesSpan && agenticToolsEnabled)
                ? buildSpanStructure(span, message)
                        .flatMap(structure -> evaluate(message, structure, true, mdc, recorder))
                : evaluate(message, null, referencesSpan, mdc, recorder);

        return recorder.monitor(scoresMono)
                .flatMap(scores -> storeSpanScores(scores, span, message.userName(), message.workspaceId()))
                .doOnNext(withMdc(mdc, loggedScores -> userFacingLogger
                        .info("Scores for spanId '{}' stored successfully:\n\n{}", span.id(), loggedScores)))
                .doOnError(withMdc(mdc, error -> userFacingLogger
                        .error("Unexpected error while scoring spanId '{}' with rule '{}': \n\n{}",
                                span.id(), message.ruleName(),
                                Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                                        .orElse(error.getMessage()))))
                .then();
    }

    /**
     * Builds the {@code {{span}}} structure injected into the prompt: a small envelope
     * ({@code span_id} + the span's own {@code attachments} list + {@code data} = the span JSON) so the
     * judge has the real span id and attachment {@code file_name}s inline and can call
     * {@code get_attachment(type=span, id=<span_id>, file_name=...)} with correct values. The attachment
     * metadata isn't carried on the {@link Span} object, so it's fetched via a single best-effort lookup
     * — a listing failure degrades to no attachments rather than blocking scoring.
     */
    private Mono<String> buildSpanStructure(Span span, SpanToScoreLlmAsJudge message) {
        return fetchSpanAttachments(span, message)
                .map(spanAttachments -> {
                    ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
                    envelope.put("span_id", span.id() != null ? span.id().toString() : null);
                    envelope.set("attachments", AttachmentSummaries.toJsonArray(spanAttachments));
                    envelope.set("data", JsonUtils.getMapper().valueToTree(span));
                    return envelope.toString();
                });
    }

    /**
     * Lists the span's own attachments, tolerating the upload race via
     * {@link AgenticScoringService#listAttachmentsToleratingUploadRace} (gated on the span body
     * referencing an attachment).
     */
    private Mono<List<AttachmentInfo>> fetchSpanAttachments(Span span, SpanToScoreLlmAsJudge message) {
        Mono<List<AttachmentInfo>> fetch = attachmentService
                .getAttachmentInfoByEntity(span.id(), com.comet.opik.api.attachment.EntityType.SPAN,
                        span.projectId())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
        return agenticScoringService.listAttachmentsToleratingUploadRace(fetch, message.workspaceId(), span.id(),
                span.input(), span.output(), span.metadata());
    }

    private Mono<List<FeedbackScoreBatchItem>> evaluate(SpanToScoreLlmAsJudge message,
            String spanStructureJson, boolean referencesSpan, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        var span = message.span();
        // Sync prep (prompt rendering) is CPU-bound — schedule on Schedulers.parallel() so we don't tax
        // the R2DBC scheduler that emits the attachment fetch upstream. MDC is re-applied via withMdc()
        // on the reactive operators below because they may run on a different thread.
        return Mono.fromCallable(() -> prepareEvaluation(message, spanStructureJson, referencesSpan, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> {
                    // No span-fetch or token estimate on the span path; record the mode decision so the
                    // parent monitoring trace still reflects agentic vs inline.
                    recorder.recordPreparation(0, 0, prepared.useTools());
                    return scoreSpanReactive(prepared.scoreRequest(), message, recorder)
                            .doOnNext(withMdc(mdc, chatResponse -> {
                                if (userFacingLogger.isInfoEnabled()) {
                                    userFacingLogger.info("Received response for spanId '{}': '{}'",
                                            span.id(), agenticScoringService.summarizeResponse(chatResponse));
                                }
                            }))
                            .flatMap(initialResponse -> prepared.useTools()
                                    ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                            prepared.structuredRequest(), message, mdc, recorder)
                                    : Mono.just(initialResponse));
                })
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        var parsed = OnlineScoringEngine.toFeedbackScores(chatResponse);
                        OnlineScoringEngine.logSkippedNullScores(userFacingLogger, parsed, "spanId", span.id());
                        return parsed.scores().stream()
                                .map(item -> (FeedbackScoreBatchItem) item.toBuilder()
                                        .id(span.id())
                                        .projectId(span.projectId())
                                        .projectName(span.projectName())
                                        .build())
                                .toList();
                    }
                });
    }

    private PreparedEvaluation prepareEvaluation(SpanToScoreLlmAsJudge message, String spanStructureJson,
            boolean referencesSpan, Map<String, String> mdc) {
        var span = message.span();
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating spanId '{}' sampled by rule '{}'", span.id(), message.ruleName());

            String modelName = message.llmAsJudgeCode().model().name();
            boolean agenticToolsEnabled = serviceTogglesConfig.isAgenticToolsEnabled();
            boolean providerSupportsTools = agenticScoringService.supportsToolCalling(
                    llmProviderFactory.getLlmProvider(modelName));
            // Tools require the {{span}} trigger AND the agentic-tools toggle AND a tool-calling provider.
            // The {{span}} substitution itself is independent of tools — see the inline branch below.
            boolean useTools = referencesSpan && agenticToolsEnabled && providerSupportsTools;

            if (referencesSpan && agenticToolsEnabled && !providerSupportsTools) {
                // Actionable misconfiguration: the prompt references {{span}} (so the user expects
                // tool-driven inspection / attachment loading) but the chosen model's provider can't
                // call tools. We still inject the structure inline so the judge at least sees the ids.
                userFacingLogger.warn(
                        "Span '{}' rule references {{span}} but provider for model '{}' does not support tool"
                                + " calling; falling back to inline path — the judge cannot load attachments."
                                + " Pick a tool-calling provider (OpenAI / Anthropic / Gemini / OpenRouter /"
                                + " Vertex / Bedrock).",
                        span.id(), modelName);
            }

            LlmRequests requests;
            try {
                if (useTools) {
                    requests = buildToolCallingRequests(message, span, modelName, spanStructureJson);
                } else if (referencesSpan && spanStructureJson != null) {
                    requests = buildInlineStructureRequests(message, span, modelName, spanStructureJson);
                } else if (referencesSpan) {
                    requests = buildSentinelStructureRequests(message, span, modelName, spanStructureJson);
                } else {
                    requests = buildPlainRequests(message, span, modelName);
                }
            } catch (Exception exception) {
                userFacingLogger.error("Error preparing LLM request for spanId '{}':",
                        span.id(), exception);
                throw exception;
            }

            userFacingLogger.info("Sending spanId '{}' to LLM: {}",
                    span.id(), agenticScoringService.summarizeRequest(requests.score(), modelName, useTools));

            return PreparedEvaluation.builder()
                    .scoreRequest(requests.score())
                    .structuredRequest(requests.structured())
                    .useTools(useTools)
                    .build();
        }
    }

    /** Score and structured-output requests produced by one of the {@code build*Requests} branches. */
    @Builder(toBuilder = true)
    private record LlmRequests(ChatRequest score, ChatRequest structured) {
    }

    /**
     * {@code useTools}: {{span}} + agentic tools + tool-calling provider. The tool-loop request uses the
     * soft InstructionStrategy; the wrap-up uses the provider-native structured-output strategy (same
     * asymmetry as the trace scorer — Anthropic in particular returns prose at the wrap-up turn under
     * InstructionStrategy).
     */
    private LlmRequests buildToolCallingRequests(SpanToScoreLlmAsJudge message, Span span, String modelName,
            String spanStructureJson) {
        String drillDownHint = ("call read(type=span, id=%s, tier=MEDIUM) for the full span"
                + " with per-string truncation hints, or jq(type=span, id=%s,"
                + " expression='<path>') for a specific section")
                .formatted(span.id(), span.id());
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span, new InstructionStrategy(),
                onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spanStructureJson);
        ChatRequest structuredRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName),
                onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spanStructureJson);
        // REQUIRED on the first call only forces ≥1 tool call; follow-ups switch to AUTO in
        // handleToolCalls so the model can decide when to stop investigating.
        scoreRequest = agenticScoringService.addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
        return LlmRequests.builder().score(scoreRequest).structured(structuredRequest).build();
    }

    /**
     * Inline fallback: {{span}} on a non-tool-calling provider (toggle ON, real structure). No read/jq
     * tools to drill in, so cap the substitutions to bound the context window — otherwise a large span
     * would inject uncapped and could overflow the model's context. No drill-down hint: the model can't
     * act on one, so over-cap values are just truncated.
     */
    private LlmRequests buildInlineStructureRequests(SpanToScoreLlmAsJudge message, Span span,
            String modelName, String spanStructureJson) {
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName),
                onlineScoringConfig.getMaxPromptFieldChars(), INLINE_TRUNCATION_HINT, spanStructureJson);
        return LlmRequests.builder().score(scoreRequest).structured(scoreRequest).build();
    }

    /**
     * Inline path that still injects the {{span}} structure so the variable renders rather than leaking
     * the bare sentinel. Toggle OFF: spanStructureJson is null → renders "{}" (tiny, no cap needed; user
     * variables stay uncapped as on the normal inline path).
     */
    private LlmRequests buildSentinelStructureRequests(SpanToScoreLlmAsJudge message, Span span,
            String modelName, String spanStructureJson) {
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName), spanStructureJson);
        return LlmRequests.builder().score(scoreRequest).structured(scoreRequest).build();
    }

    /** Normal inline path: no {{span}} reference, so no structure injection. */
    private LlmRequests buildPlainRequests(SpanToScoreLlmAsJudge message, Span span, String modelName) {
        ChatRequest scoreRequest = OnlineScoringEngine.prepareSpanLlmRequest(
                message.llmAsJudgeCode(), span,
                llmProviderFactory.getStructuredOutputStrategy(modelName));
        return LlmRequests.builder().score(scoreRequest).structured(scoreRequest).build();
    }

    /**
     * Wraps the synchronous {@code ChatLanguageModel.chat} call in a Mono scheduled on
     * {@link Schedulers#boundedElastic()} so the blocking Jersey-client I/O doesn't pin the per-stream
     * worker scheduler thread.
     */
    private Mono<ChatResponse> scoreSpanReactive(ChatRequest request, SpanToScoreLlmAsJudge message,
            EvaluationRecorder recorder) {
        var call = Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.llmAsJudgeCode().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
        return recorder.recordLlmCall(request, call);
    }

    // Package-private for unit tests.
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, SpanToScoreLlmAsJudge message, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        var span = message.span();
        // Shared loop orchestration lives in the base scorer; here we provide only the span-specific
        // context seeding — pre-seed the active span so read(type=span) / jq(type=span) resolve it
        // without a re-fetch. This method runs the same multi-turn agentic tool loop as the trace/thread
        // scorers, but the span evaluator DTO exposes no maxCostUsd field (unlike trace/thread), so there
        // is no per-evaluation spend budget to enforce — always pass the unlimited guard.
        return agenticScoringService.runToolCallLoop(chatResponse, toolRequest, structuredRequest,
                () -> {
                    var ctx = TraceToolContext.forActiveSpan(span, message.workspaceId(),
                            message.userName(), onlineScoringConfig.getAgenticToolsMaxInjectedBytes());
                    ctx.cache(new EntityRef(EntityType.SPAN, span.id().toString()),
                            JsonUtils.getMapper().valueToTree(span));
                    return ctx;
                },
                request -> scoreSpanReactive(request, message, recorder),
                BudgetGuard.UNLIMITED,
                () -> message.llmAsJudgeCode().model().name(), span.id().toString(), userFacingLogger, mdc,
                recorder);
    }

    /**
     * Carry from {@link #prepareEvaluation} to {@link #evaluate}. {@code useTools} drives whether
     * {@code handleToolCalls} runs the agentic loop after the first response.
     */
    @Builder(toBuilder = true)
    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools) {
    }
}
