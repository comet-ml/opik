package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import com.comet.opik.api.events.TraceToScoreLlmAsJudge;
import com.comet.opik.api.resources.v1.events.tools.CompressionTier;
import com.comet.opik.api.resources.v1.events.tools.EntityRef;
import com.comet.opik.api.resources.v1.events.tools.EntityType;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TestSuiteAssertionCounterService;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.WorkspaceNameService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.domain.evaluation.OnlineEvaluationRecorder;
import com.comet.opik.domain.evaluators.UserLog;
import com.comet.opik.domain.llm.ChatCompletionService;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.InstructionStrategy;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.log.UserFacingLoggingFactory;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.Constants;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluatorType.LLM_AS_JUDGE;
import static com.comet.opik.infrastructure.log.LogContextAware.withMdc;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

@EagerSingleton
@Slf4j
public class OnlineScoringLlmAsJudgeScorer extends OnlineScoringBaseScorer<TraceToScoreLlmAsJudge> {

    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = AttributeKey.stringKey("workspace_name");
    private static final AttributeKey<String> PATH_KEY = AttributeKey.stringKey("path");
    private static final AttributeKey<String> TRIGGER_KEY = AttributeKey.stringKey("trigger");

    private final ChatCompletionService aiProxyService;
    private final Logger userFacingLogger;
    private final LlmProviderFactory llmProviderFactory;
    private final TestSuiteAssertionCounterService testSuiteAssertionCounterService;
    private final SpanService spanService;
    private final AgenticScoringService agenticScoringService;
    private final TraceCompressor traceCompressor;
    private final WorkspaceNameService workspaceNameService;
    private final OpikConfiguration opikConfiguration;
    private final ServiceTogglesConfig serviceTogglesConfig;
    private final OnlineEvaluationRecorder onlineEvaluationRecorder;
    private final AttachmentService attachmentService;
    private final LongCounter routingDecisions;

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
            @NonNull AgenticScoringService agenticScoringService,
            @NonNull TraceCompressor traceCompressor,
            @NonNull WorkspaceNameService workspaceNameService,
            @NonNull OpikConfiguration opikConfiguration,
            @NonNull OnlineEvaluationRecorder onlineEvaluationRecorder,
            @NonNull AttachmentService attachmentService) {
        super(config, redisson, feedbackScoreService, traceService,
                LLM_AS_JUDGE, Constants.LLM_AS_JUDGE);
        this.aiProxyService = aiProxyService;
        this.userFacingLogger = UserFacingLoggingFactory.getLogger(OnlineScoringLlmAsJudgeScorer.class);
        this.llmProviderFactory = llmProviderFactory;
        this.testSuiteAssertionCounterService = testSuiteAssertionCounterService;
        this.spanService = spanService;
        this.agenticScoringService = agenticScoringService;
        this.traceCompressor = traceCompressor;
        this.workspaceNameService = workspaceNameService;
        this.opikConfiguration = opikConfiguration;
        this.serviceTogglesConfig = serviceTogglesConfig;
        this.onlineEvaluationRecorder = onlineEvaluationRecorder;
        this.attachmentService = attachmentService;
        this.routingDecisions = GlobalOpenTelemetry.getMeter(ONLINE_SCORING_NAMESPACE)
                .counterBuilder("online_scoring_agentic_routing_total")
                .setDescription("Agentic vs inline routing decisions per evaluation, by trigger and workspace")
                .build();
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
    protected Mono<Void> doScore(TraceToScoreLlmAsJudge message) {
        UUID experimentId = message.experimentId();
        if (experimentId != null) {
            // Resolve workspaceName lazily on subscription. ExperimentService.finishExperiments
            // (reached via decrementAndFinishIfComplete when the assertion counter hits zero) reads
            // WORKSPACE_NAME from the reactive context; without it the post-scoring chain throws
            // NoSuchElementException, the message isn't ack'd, and Redis Streams retries the whole
            // scoring run — re-running the LLM and re-inserting assertion rows.
            // Overriding doScore (not processEvent) keeps this post-scoring step inside the chain the
            // base wraps with the processed-success counter, so a failure here is not counted as processed.
            return super.doScore(message)
                    .then(Mono.fromCallable(() -> resolveWorkspaceName(message.workspaceId()))
                            .subscribeOn(Schedulers.boundedElastic())
                            .flatMap(workspaceName -> testSuiteAssertionCounterService
                                    .decrementAndFinishIfComplete(message.workspaceId(), experimentId)
                                    .contextWrite(ctx -> ctx
                                            .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                                            .put(RequestContext.WORKSPACE_NAME, workspaceName)
                                            .put(RequestContext.USER_NAME, message.userName()))));
        }
        return super.doScore(message);
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

        // Presence of the {{trace}} variable is the declarative agentic trigger: it injects the
        // trace structure (trace id, span ids, attachment file_names) into the prompt so the judge
        // can call get_attachment with real ids instead of guessing. Gated by the agentic-tools
        // toggle, same as the size-based path.
        boolean referencesTrace = serviceTogglesConfig.isAgenticToolsEnabled()
                && OnlineScoringEngine.templateReferencesTraceStructure(
                        message.llmAsJudgeCode().messages(),
                        message.llmAsJudgeCode().variables(),
                        message.promptType());

        // Monitoring recorder for the evaluation loop (OPIK-6994): one hidden source=evaluator trace per
        // evaluation, one llm span per LLM round. NOOP when the toggle is off — the evaluation then runs
        // exactly as before with no extra writes.
        EvaluationRecorder recorder = serviceTogglesConfig.isOnlineScoringTracingEnabled()
                ? onlineEvaluationRecorder.begin(trace, message.ruleId(), message.ruleName(),
                        message.llmAsJudgeCode().model().name(), message.workspaceId(), message.userName())
                : EvaluationRecorder.NOOP;

        Mono<List<FeedbackScoreBatchItem>> scoring = spansMono
                .flatMap(spans -> referencesTrace
                        ? buildTraceStructure(trace, spans, message)
                                .flatMap(structure -> evaluate(message, spans, structure.envelopeJson(),
                                        structure.fullJson(), true, mdc, recorder))
                        : evaluate(message, spans, null, null, false, mdc, recorder));

        return recorder.monitor(scoring)
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

    /**
     * Builds the {@code {{trace}}} structure injected into the prompt: the trace+spans content
     * (compressed via {@link TraceCompressor#compress} — the same path the {@code read} tool uses)
     * enriched with attachment {@code file_name}s at both levels — the trace's own attachments on the
     * trace node and each span's attachments on its {@code span_tree} / {@code spans[]} entry — wrapped
     * in a small id envelope ({@code trace_id} + {@code tier} + {@code data}). So the judge can call
     * {@code get_attachment} with the correct {@code (type, id, file_name)} for any attachment in the
     * trace, whether it lives on the trace or on one of its spans.
     *
     * <p>Trace-level attachment metadata isn't carried on the {@link Trace} object, and per-span
     * metadata isn't on the {@link Span} objects, so both are fetched: the trace's own via a
     * race-tolerant single lookup, and the spans' via a race-tolerant batched lookup. Both tolerate the
     * attachment-upload race (an attachment may not be persisted yet when scoring is enqueued) and
     * degrade to no attachments on a listing failure rather than blocking scoring.
     */
    private Mono<TraceStructure> buildTraceStructure(Trace trace, List<Span> spans, TraceToScoreLlmAsJudge message) {
        Mono<List<AttachmentInfo>> traceColdFetch = attachmentService
                .getAttachmentInfoByEntity(trace.id(), com.comet.opik.api.attachment.EntityType.TRACE,
                        trace.projectId())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
        // Trace's own attachments: race-tolerant (may not be uploaded yet), gated on the trace body
        // referencing an attachment.
        Mono<List<AttachmentInfo>> traceAttachmentsMono = agenticScoringService.listAttachmentsToleratingUploadRace(
                traceColdFetch, message.workspaceId(), trace.id(),
                trace.input(), trace.output(), trace.metadata());

        return Mono.zip(gatherSpanAttachments(trace, spans, message), traceAttachmentsMono)
                // buildFullJson + compress(FULL) is the most CPU-/GC-expensive part of routing (a
                // valueToTree of the trace + all its spans, plus a deepCopy for the enrich pass). The
                // zip above emits on the R2DBC/attachment scheduler thread that ran the attachment
                // lookups, so hop this serialization onto Schedulers.parallel() — mirroring evaluate()'s
                // prep hop — to avoid taxing the DB scheduler on the {{trace}} path.
                .flatMap(tuple -> Mono.fromCallable(() -> {
                    // Built once here and threaded downstream to prepareEvaluation (size estimate) and
                    // the tool-cache pre-seed, so the {{trace}} path serializes {trace, spans} a single
                    // time. compress() deep-copies before mutating (FULL/MEDIUM), so this node stays
                    // pristine and is safe to reuse.
                    JsonNode fullJson = traceCompressor.buildFullJson(trace, spans);
                    // Enrich with BOTH per-span and trace-level attachments so the judge can fetch any of them.
                    var compressed = traceCompressor.compress(fullJson, trace, spans, CompressionTier.FULL,
                            tuple.getT1(), tuple.getT2());
                    // compress() is id-agnostic; add the id envelope on top here, mirroring ReadTool.
                    ObjectNode envelope = JsonUtils.getMapper().createObjectNode();
                    envelope.put("trace_id", trace.id() != null ? trace.id().toString() : null);
                    envelope.put("tier", compressed.tier().name());
                    envelope.set("data", compressed.payload());
                    return TraceStructure.builder()
                            .envelopeJson(envelope.toString())
                            .fullJson(fullJson)
                            .build();
                }).subscribeOn(Schedulers.parallel()));
    }

    /**
     * Lists attachments for all of the trace's spans in one batched, upload-race-tolerant lookup, grouped
     * by span id. Spans whose body references an attachment drive a bounded retry until that attachment is
     * persisted and visible (so the injected {@code {{trace}}} payload isn't missing span-level
     * {@code file_name}s when scoring is enqueued immediately on trace create/update); transient
     * auto-stripped copies are dropped per span when a persistent copy exists. See
     * {@link AgenticScoringService#listSpanAttachmentsToleratingUploadRace}.
     */
    private Mono<Map<UUID, List<AttachmentInfo>>> gatherSpanAttachments(
            Trace trace, List<Span> spans, TraceToScoreLlmAsJudge message) {
        Set<UUID> spanIds = spans.stream()
                .map(Span::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        if (spanIds.isEmpty()) {
            return Mono.just(Map.of());
        }
        // Per-span set of attachment filenames referenced in that span's body. Used both to drive the
        // upload-race retry (spans expecting an attachment = non-empty set) and to keep referenced
        // auto-stripped copies when grouping (see AgenticScoringService#preferPersistentAttachments).
        Map<UUID, Set<String>> referencedNamesBySpan = spans.stream()
                .filter(span -> span.id() != null)
                .collect(Collectors.toMap(Span::id,
                        span -> AttachmentUtils.collectAttachmentReferences(
                                JsonUtils.getMapper(), span.input(), span.output(), span.metadata()),
                        (a, b) -> a));
        Set<UUID> spanIdsExpectingAttachment = referencedNamesBySpan.entrySet().stream()
                .filter(entry -> !entry.getValue().isEmpty())
                .map(Map.Entry::getKey)
                .collect(Collectors.toSet());
        Mono<List<AttachmentInfo>> coldBatchedFetch = attachmentService
                .getAttachmentInfoByEntityIds(com.comet.opik.api.attachment.EntityType.SPAN, spanIds)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName()));
        return agenticScoringService.listSpanAttachmentsToleratingUploadRace(
                coldBatchedFetch, message.workspaceId(), trace.id(), spanIdsExpectingAttachment,
                referencedNamesBySpan);
    }

    private Mono<List<FeedbackScoreBatchItem>> evaluate(TraceToScoreLlmAsJudge message, List<Span> spans,
            String traceStructureJson, JsonNode prebuiltFullJson, boolean referencesTrace, Map<String, String> mdc,
            EvaluationRecorder recorder) {
        var trace = message.trace();
        // One guard per trace evaluation; UNLIMITED when the rule sets no maxCostUsd. Charges every
        // LLM call (initial + tool rounds + wrap-up) and tells the tool loop when to start wrapping up.
        var costGuard = BudgetGuard.create(message.llmAsJudgeCode().maxCostUsd(),
                message.llmAsJudgeCode().model().name(), llmProviderFactory);
        // Sync prep is CPU-bound (JSON serialization for the size estimate + prompt rendering)
        // — schedule on Schedulers.parallel() so we don't tax the R2DBC scheduler that emits
        // the spans fetch upstream, and don't pin a workersScheduler thread on the inline path.
        // MDC is applied inside prepareEvaluation via try-with-resources; reactive operators
        // below re-apply MDC via withMdc() because they may run on a different thread than the
        // boundedElastic worker that emitted the chat response.
        return Mono.fromCallable(
                () -> prepareEvaluation(message, spans, traceStructureJson, prebuiltFullJson, referencesTrace, mdc))
                .subscribeOn(Schedulers.parallel())
                .flatMap(prepared -> {
                    // Record the upfront retrieval + context assembly (span fetch, size estimate, mode
                    // decision) as a preparation span before the first LLM round; the agentic flag also
                    // sets the parent trace's mode.
                    recorder.recordPreparation(spans.size(), prepared.estimatedTokens(), prepared.useTools());
                    return scoreTraceReactive(prepared.scoreRequest(), message, recorder, costGuard)
                            .doOnNext(withMdc(mdc, chatResponse -> {
                                if (userFacingLogger.isInfoEnabled()) {
                                    userFacingLogger.info("Received response for traceId '{}': '{}'",
                                            trace.id(), agenticScoringService.summarizeResponse(chatResponse));
                                }
                            }))
                            .flatMap(initialResponse -> prepared.useTools()
                                    ? handleToolCalls(initialResponse, prepared.scoreRequest(),
                                            prepared.structuredRequest(), message, spans, prepared.fullJson(), mdc,
                                            recorder, costGuard)
                                    : Mono.just(initialResponse));
                })
                .map(chatResponse -> {
                    try (var logContext = wrapWithMdc(mdc)) {
                        if (costGuard.wasBudgetEnforced()) {
                            // Keyed on wasBudgetEnforced() (the loop's budget gate actually cut the run
                            // short), not shouldWrapUp() (mere spend >= limit): this warn claims the
                            // investigation was stopped, so it must not fire on the inline single-call path
                            // or a natural stop that only crossed spend. Same authoritative signal as the
                            // budget_exceeded trace tag flagged inside ToolCallLoop.
                            userFacingLogger.warn(
                                    "Spend budget of '{}' USD reached for traceId '{}' (spent '{}'); "
                                            + "stopped investigating and wrapped up with the scores gathered so far.",
                                    costGuard.limitUsd(), trace.id(), costGuard.spentUsd());
                        }
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
            String traceStructureJson, JsonNode prebuiltFullJson, boolean referencesTrace, Map<String, String> mdc) {
        var trace = message.trace();
        // Logging tags for the rule + trace; covers every userFacingLogger call in this sync prep.
        try (var logContext = wrapWithMdc(mdc)) {
            userFacingLogger.info("Evaluating traceId '{}' sampled by rule '{}'", trace.id(), message.ruleName());

            // Spans were fetched reactively in score() and threaded through, so handleToolCalls
            // can seed the read-tool cache without a second query. estimateTraceContextTokens
            // works on the {trace, spans} composite — small trace with huge spans still trips
            // the size-based agentic-tools branch.
            //
            // The full JSON is serialized ONCE: on the {{trace}} path buildTraceStructure already
            // built it and threads it in here (prebuiltFullJson); otherwise we build it now for the
            // size estimate. If useTools resolves true, handleToolCalls reuses this same JsonNode to
            // pre-seed the cache instead of rebuilding — saving a full trace+spans serialization on
            // every big-trace run (the most CPU-/GC-expensive part of routing).
            String modelName = message.llmAsJudgeCode().model().name();
            JsonNode fullJson = prebuiltFullJson != null
                    ? prebuiltFullJson
                    : traceCompressor.buildFullJson(trace, spans);
            int estimatedContextTokens = agenticScoringService.estimateTokensFromJson(
                    fullJson, onlineScoringConfig.getAgenticToolsCharsPerToken());
            boolean useTools = shouldUseAgenticTools(message, estimatedContextTokens, modelName,
                    referencesTrace);

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
                            message.promptType(), onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spans,
                            traceStructureJson);
                    // The post-tool-loop wrap-up must use the provider-native structured-output
                    // strategy (e.g. response_format=json_schema on OpenAI). InstructionStrategy
                    // is a soft prompt and Anthropic in particular often returns conversational
                    // prose at the wrap-up turn ("Now let me check..."), which then fails JSON
                    // parsing in toFeedbackScores and yields zero scores.
                    structuredRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), onlineScoringConfig.getMaxPromptFieldChars(), drillDownHint, spans,
                            traceStructureJson);
                } else if (referencesTrace) {
                    // Inline fallback for a {{trace}} rule on a non-tool-calling provider: the structure was
                    // built at FULL tier for the (unavailable) tools path, and there are no read/jq tools to
                    // drill in here, so cap the substitutions to bound the context window — otherwise a large
                    // trace would inject uncapped and could overflow the model's context. No drill-down hint:
                    // the model can't act on one, so over-cap values are simply truncated.
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), onlineScoringConfig.getMaxPromptFieldChars(), INLINE_TRUNCATION_HINT,
                            spans,
                            traceStructureJson);
                    structuredRequest = scoreRequest;
                } else {
                    scoreRequest = OnlineScoringEngine.prepareLlmRequest(
                            message.llmAsJudgeCode(), trace,
                            llmProviderFactory.getStructuredOutputStrategy(modelName),
                            message.promptType(), spans, traceStructureJson);
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
                scoreRequest = agenticScoringService.addToolSpecs(scoreRequest, ToolChoice.REQUIRED);
            }

            // summarizeRequest is cheap (no per-message toString streaming since the chars-count
            // field was removed). At INFO so operators watching their rule's UI logs see a
            // matching "Sending" line between "Evaluating" and "Received response".
            userFacingLogger.info("Sending traceId '{}' to LLM: {}",
                    trace.id(), agenticScoringService.summarizeRequest(scoreRequest,
                            message.llmAsJudgeCode().model().name(), useTools));

            // fullJson is only useful downstream on the agentic-tools path (handleToolCalls
            // pre-seeds it into the tool cache). On the inline path we discard it — we already
            // paid for the build to compute the size estimate; skipping the carry avoids
            // holding a potentially-multi-MB JsonNode on the chain for an evaluation that
            // doesn't consume it.
            return new PreparedEvaluation(scoreRequest, structuredRequest, useTools,
                    useTools ? fullJson : null, estimatedContextTokens);
        }
    }

    /**
     * Wraps the synchronous {@code ChatLanguageModel.chat} call in a Mono and schedules it
     * on {@link Schedulers#boundedElastic()} so the blocking Jersey-client I/O doesn't
     * pin the per-stream worker scheduler thread (OPIK-6308).
     */
    private Mono<ChatResponse> scoreTraceReactive(ChatRequest request, TraceToScoreLlmAsJudge message,
            EvaluationRecorder recorder, BudgetGuard costGuard) {
        var call = Mono.fromCallable(() -> aiProxyService.scoreTrace(
                request, message.llmAsJudgeCode().model(), message.workspaceId()))
                .subscribeOn(Schedulers.boundedElastic());
        return costGuard.track(recorder.recordLlmCall(request, call));
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
        boolean agenticToolsPathPossible = agenticScoringService.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName))
                && (LlmAsJudgeToolsMode.shouldUseTools(message)
                        || serviceTogglesConfig.isAgenticToolsEnabled());
        boolean templateNeedsSpans = serviceTogglesConfig.isAgenticToolsEnabled()
                && (OnlineScoringEngine.templateReferencesSpans(
                        message.llmAsJudgeCode().messages(),
                        message.llmAsJudgeCode().variables(),
                        message.promptType())
                        || OnlineScoringEngine.templateReferencesTraceStructure(
                                message.llmAsJudgeCode().messages(),
                                message.llmAsJudgeCode().variables(),
                                message.promptType()));
        return agenticToolsPathPossible || templateNeedsSpans;
    }

    /**
     * Routing decision for whether to attach tool specs + run the tool-call loop. Tools fire
     * when ANY of (a) the experimentId-driven branch applies (test-suite assertion), (b) the
     * size-based branch applies (toggle on, context above threshold), or (c) the prompt references
     * the {@code {{trace}}} skeleton variable (toggle on) — AND the provider supports tool-calling.
     * Without the provider check, a non-tool-calling model (Ollama / Custom / OpikFree) selected
     * via {@code test_suite_model} metadata would crash inside the LangChain4j chat call when the
     * request carries {@code toolSpecifications}.
     *
     * <p>When a path wants tools but the provider can't handle them, we fall back to the inline
     * path with a user-facing warn — surfacing the misconfiguration loudly is better than the
     * silent crash the old code produced.
     *
     * <p>Side-effects: emits user-facing diagnostic logs whenever the decision is non-trivial,
     * so operators can correlate the routing with the trace.
     */
    // Package-private for unit tests.
    boolean shouldUseAgenticTools(TraceToScoreLlmAsJudge message, int estimatedContextTokens, String modelName,
            boolean referencesTrace) {
        boolean experimentIdPath = LlmAsJudgeToolsMode.shouldUseTools(message);
        boolean providerSupportsTools = agenticScoringService.supportsToolCalling(
                llmProviderFactory.getLlmProvider(modelName));
        boolean overSizeThreshold = serviceTogglesConfig.isAgenticToolsEnabled()
                && estimatedContextTokens >= onlineScoringConfig.getAgenticToolsThresholdTokens();
        // The {{trace}} variable forces the agentic-tools path regardless of context size: the prompt
        // carries the trace skeleton (ids + attachment file_names) and the judge uses get_attachment /
        // read to drill in. Gated by the same toggle as the size path.
        boolean traceVariablePath = serviceTogglesConfig.isAgenticToolsEnabled() && referencesTrace;
        boolean wantsTools = experimentIdPath || overSizeThreshold || traceVariablePath;
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
        } else if (!experimentIdPath && !overSizeThreshold && traceVariablePath && !providerSupportsTools) {
            // Surface to the user: their prompt references {{trace}} (so they expect tool-driven
            // inspection of the trace skeleton / attachments) but the chosen model's provider can't
            // call tools — an actionable misconfiguration, unlike the purely-internal routing
            // decisions which stay on the internal log.
            userFacingLogger.warn(
                    "Trace '{}' rule references {{trace}} but provider for model '{}' does not support tool"
                            + " calling; falling back to inline path — the judge cannot inspect the trace"
                            + " skeleton or load attachments. Pick a tool-calling provider"
                            + " (OpenAI / Anthropic / Gemini / OpenRouter / Vertex / Bedrock).",
                    message.trace().id(), modelName);
        } else if (!experimentIdPath && !overSizeThreshold && traceVariablePath && useTools) {
            log.debug("Trace '{}' rule references {{trace}}; switching to agentic-tools mode",
                    message.trace().id());
        }

        recordRoutingDecision(message, useTools, experimentIdPath, overSizeThreshold, traceVariablePath);
        return useTools;
    }

    private void recordRoutingDecision(TraceToScoreLlmAsJudge message, boolean useTools,
            boolean experimentIdPath, boolean overSizeThreshold, boolean traceVariablePath) {
        String path = useTools ? "agentic" : "inline";
        String trigger = "none";
        if (useTools) {
            if (experimentIdPath) {
                trigger = "experiment_id";
            } else if (overSizeThreshold) {
                trigger = "size_threshold";
            } else if (traceVariablePath) {
                trigger = "trace_variable";
            } else {
                trigger = "attachments";
            }
        }
        String wsId = message.workspaceId();
        String wsName = StringUtils.defaultIfBlank(message.workspaceName(), wsId);
        routingDecisions.add(1, Attributes.of(
                PATH_KEY, path,
                TRIGGER_KEY, trigger,
                WORKSPACE_ID_KEY, wsId,
                WORKSPACE_NAME_KEY, wsName));
    }

    // Package-private for unit tests.
    Mono<ChatResponse> handleToolCalls(ChatResponse chatResponse, ChatRequest toolRequest,
            ChatRequest structuredRequest, TraceToScoreLlmAsJudge message, List<Span> spans,
            JsonNode fullJson, Map<String, String> mdc, EvaluationRecorder recorder, BudgetGuard costGuard) {
        var trace = message.trace();
        // Shared loop orchestration lives in the base scorer; here we provide only the trace-specific
        // context seeding. The cache is pre-seeded with the JSON prepareEvaluation already built for the
        // size estimate (saves a rebuild on big traces); fall back to rebuilding when the caller didn't
        // supply one (e.g. unit tests that call handleToolCalls directly).
        return agenticScoringService.runToolCallLoop(chatResponse, toolRequest, structuredRequest,
                () -> {
                    var ctx = TraceToolContext.forActiveTrace(trace, spans, message.workspaceId(),
                            message.userName(), onlineScoringConfig.getAgenticToolsMaxInjectedBytes());
                    ctx.cache(new EntityRef(EntityType.TRACE, trace.id().toString()),
                            fullJson != null ? fullJson : traceCompressor.buildFullJson(trace, spans));
                    return ctx;
                },
                request -> scoreTraceReactive(request, message, recorder, costGuard),
                costGuard,
                () -> message.llmAsJudgeCode().model().name(), trace.id().toString(), userFacingLogger, mdc,
                recorder);
    }

    /**
     * Carry from {@link #prepareEvaluation} to {@link #evaluate}. {@code fullJson} is the
     * pre-built {@code {trace, spans}} JSON used both for the size estimate and (when
     * {@code useTools} is true) for pre-seeding the tool context's cache — null on the
     * inline path so we don't hold a multi-MB JsonNode for evaluations that won't consume it.
     */
    private record PreparedEvaluation(ChatRequest scoreRequest, ChatRequest structuredRequest, boolean useTools,
            JsonNode fullJson, int estimatedTokens) {
    }

    /**
     * Carry from {@link #buildTraceStructure} to {@link #evaluate} on the {@code {{trace}}} path.
     * {@code envelopeJson} is the rendered structure injected into the prompt; {@code fullJson} is the
     * {@code {trace, spans}} composite built alongside it, threaded down so {@link #prepareEvaluation}
     * reuses it for the size estimate / tool-cache pre-seed instead of serializing the trace again.
     */
    @Builder(toBuilder = true)
    private record TraceStructure(@NonNull String envelopeJson, @NonNull JsonNode fullJson) {
    }

}
