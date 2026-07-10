package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceCompressor;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.Logger;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * Encapsulates the agentic-tools capability shared by the trace-, span- and thread-level
 * LLM-as-judge scorers: running the {@code read}/{@code jq}/{@code search}/{@code get_attachment}
 * tool-call loop, discovering an entity's attachments while tolerating the upload race, and the
 * small collection of tool-loop-adjacent request/response helpers (tool-spec attachment, provider
 * capability check, size estimation, sanitized request/response summaries).
 *
 * <p>Previously this logic lived on {@code OnlineScoringBaseScorer} and was propagated to
 * subclasses via inheritance. Pulling it into an injected service means the online scorers are
 * users of the capability rather than owners of it — plain scorers that don't need agentic tools
 * (e.g. the Python-metric scorers) no longer inherit it, and the trace/span/thread scorers all
 * share one implementation instead of the thread scorer hand-rolling its own copy of the loop.
 */
@ImplementedBy(AgenticScoringServiceImpl.class)
public interface AgenticScoringService {

    /**
     * Shared agentic tool-loop orchestration for the trace-, span- and thread-level scorers.
     * Returns the initial response untouched when it carries no tool calls; otherwise defers to
     * subscription time and runs {@link ToolCallLoop#runWithWrapUp} with {@code ToolChoice.AUTO}
     * follow-ups, surfacing any injected-media failure as a user-facing log before propagating.
     *
     * <p>The entity-specific parts — building + pre-seeding the {@link TraceToolContext} (trace,
     * span, or thread) and the per-message scoring call — are supplied by the caller as
     * {@code contextSupplier} and {@code scoreFn}. The supplier is invoked inside the internal
     * {@code defer} so context creation and cache pre-seed happen exactly once per subscription.
     *
     * @param contextSupplier builds and pre-seeds the tool context (invoked at subscription time)
     * @param scoreFn         issues a single LLM call (e.g. {@code request -> scoreTraceReactive(...)})
     * @param costGuard       per-evaluation spend guard threaded into {@link ToolCallLoop} so the loop
     *                        stops starting new turns once the budget is reached; pass
     *                        {@link BudgetGuard#UNLIMITED} when the scope enforces no budget (e.g. span)
     * @param modelNameSupplier the judge model name, read only on the error-surfacing path — lazy so a
     *                          caller whose model accessor can throw/NPE on an incomplete message (e.g.
     *                          thread evals before the routing decision resolves it) never pays for it on
     *                          the (far more common) no-error path
     * @param logId           the trace/span/thread id used as the tool-loop log correlation id
     * @param recorder        evaluation monitoring recorder threaded into {@link ToolCallLoop} so each
     *                        tool call is recorded (OPIK-6994); pass {@link EvaluationRecorder#NOOP} when
     *                        monitoring is off
     */
    Mono<ChatResponse> runToolCallLoop(ChatResponse initialResponse,
            ChatRequest toolRequest, ChatRequest structuredRequest,
            Supplier<TraceToolContext> contextSupplier,
            Function<ChatRequest, Mono<ChatResponse>> scoreFn,
            BudgetGuard costGuard,
            Supplier<String> modelNameSupplier, String logId,
            Logger userFacingLogger, Map<String, String> mdc,
            EvaluationRecorder recorder);

    /**
     * Lists an entity's attachments while tolerating the upload race (bounded retry configured by
     * {@link OnlineScoringConfig#getAttachmentFetchMaxRetries()} /
     * {@link OnlineScoringConfig#getAttachmentFetchRetryDelay()}). Shared by the trace- and span-level
     * scorers when building the injected {@code {{trace}}} / {@code {{span}}} structure.
     *
     * <p>An upload exists transiently as an <em>auto-stripped</em> copy ({@code input-attachment-N-ts.ext},
     * no {@code -sdk}) that is <strong>deleted</strong> once the persistent copy (e.g. {@code …-sdk.jpg})
     * lands. A listing taken mid-race can therefore contain only the soon-to-404 transient name. So when
     * any of {@code bodyNodes} (the entity's input/output/metadata) references an attachment, the cold
     * lookup is resubscribed a few times with a short delay until a <em>persistent</em> (non-auto-stripped)
     * attachment appears, and transient copies are dropped whenever a persistent one is present (so the
     * judge is never handed a name that will 404). Entities with no attachment reference skip the retry
     * (the common case). If the retry budget is exhausted — e.g. a REST-ingested image whose only copy is
     * auto-stripped and never replaced — it falls back to a best-effort final read rather than dropping it.
     *
     * <p>A genuine lookup failure is logged once (with the workspace/entity identifiers and the stack
     * trace) before degrading to an empty list, so the best-effort behavior is still operator-visible.
     * The benign retry-exhaustion path (no persistent copy ever appears) completes empty rather than in
     * error, so it is <em>not</em> logged as a failure.
     *
     * @param coldFetch   the attachment lookup — must be cold (re-runs the query on each subscription)
     * @param workspaceId workspace id, included in the failure log for observability
     * @param entityId    the trace/span id whose attachments are being listed, included in the failure log
     * @param bodyNodes   the entity's content nodes scanned for attachment references
     */
    Mono<List<AttachmentInfo>> listAttachmentsToleratingUploadRace(
            Mono<List<AttachmentInfo>> coldFetch, String workspaceId, UUID entityId,
            JsonNode... bodyNodes);

    /**
     * Batched, upload-race-tolerant span-attachment lookup for the {@code {{trace}}} structure. Groups a
     * single batched listing of the trace's spans' attachments by span id (preferring persistent copies
     * per span, so a transient auto-stripped name is never surfaced). For spans whose body references an
     * attachment ({@code spanIdsExpectingAttachment}) it resubscribes the (cold) batched lookup a few
     * times until <em>every</em> such span has a persistent attachment visible, tolerating the
     * attachment-upload race; on an exhausted budget it falls back to a best-effort grouping (so a
     * REST-/backend-only auto-stripped copy is still surfaced). A listing failure is logged once and
     * degrades to no span attachments rather than blocking scoring.
     *
     * <p>One batched query per attempt (not one per span), so it scales to large traces. The single-entity
     * analogue is {@link #listAttachmentsToleratingUploadRace}.
     *
     * @param coldBatchedFetch           the batched lookup — must be cold (re-runs on each subscription)
     * @param workspaceId                workspace id, for the failure log
     * @param traceId                    the trace id, for the failure log
     * @param spanIdsExpectingAttachment span ids whose body references an attachment (drives the retry)
     * @param referencedNamesBySpan      per-span set of attachment filenames referenced in that span's body,
     *                                   used to keep referenced auto-stripped copies
     */
    Mono<Map<UUID, List<AttachmentInfo>>> listSpanAttachmentsToleratingUploadRace(
            Mono<List<AttachmentInfo>> coldBatchedFetch, String workspaceId, UUID traceId,
            Set<UUID> spanIdsExpectingAttachment,
            Map<UUID, Set<String>> referencedNamesBySpan);

    /**
     * Whether the given provider is known to support tool-calling. Used to gate the agentic-tools
     * path: providers that don't support tools fall back to the inline path even when the context
     * exceeds the size threshold (which may overflow the model's window — in that case the operator
     * should pick a different model for those workloads).
     */
    boolean supportsToolCalling(LlmProvider provider);

    /**
     * Attach the tool specs from the registered {@link ToolRegistry} and the given {@code toolChoice} to
     * {@code request}'s parameters. Tool specs live inside {@link ChatRequestParameters}, so we copy the
     * existing parameters via {@code overrideWith} and layer tool specs on top — setting
     * {@code toolSpecifications} directly on the {@link ChatRequest} builder would conflict with
     * parameters. {@code toBuilder()} (rather than a fresh builder + .messages()) preserves any other
     * top-level fields on ChatRequest, present or future, guarding against a "silently dropped fields"
     * regression between the initial scoring call and the structured re-issue in the tool-call wrap-up.
     */
    ChatRequest addToolSpecs(ChatRequest request, ToolChoice toolChoice);

    /**
     * Rough character-based token estimate for a pre-built JSON payload. Used to decide whether the
     * inline-rendered prompt would risk overflowing the model's window — which flips the scorer into
     * the read/jq/search agentic-tools path.
     *
     * <p>{@code charsPerToken} is the chars-per-token ratio operators configure via
     * {@code onlineScoring.agenticToolsCharsPerToken} (default 4 = natural-language English).
     */
    int estimateTokensFromJson(JsonNode fullJson, int charsPerToken);

    /**
     * Same as {@link #estimateTokensFromJson} but builds the {@code {trace, spans}} JSON first. Prefer
     * {@link #estimateTokensFromJson} directly when the caller already has the full JSON in hand (e.g.
     * because it's going to be pre-seeded into the tool context's cache anyway) — avoids serializing the
     * trace twice on big-trace evaluations.
     */
    int estimateTraceContextTokens(Trace trace, List<Span> spans,
            TraceCompressor traceCompressor, int charsPerToken);

    /**
     * Rough character-based token estimate for the thread context as it would be rendered on the inline
     * path. Estimates the enriched shape — trace input/output plus the assistant turn's child spans (tool
     * calls + I/O) — so the agentic-tools routing decision reflects what the inline render will actually
     * serialize. Pass an empty {@code spans} list when the toggle is off; the enriched serializer omits
     * the {@code spans} field via {@code @JsonInclude(NON_NULL)}, so the estimate then matches the
     * original trace-bodies-only shape exactly.
     *
     * <p>Same {@code charsPerToken} contract as {@link #estimateTraceContextTokens}.
     */
    int estimateThreadContextTokens(List<Trace> traces, List<Span> spans, int charsPerToken);

    /**
     * Build a sanitized one-line description of the outgoing LLM request for user-facing logs. The full
     * {@link ChatRequest} contains the rendered prompt, the user message with the trace's input/output,
     * request parameters, and tool specs — surfacing all of it in a stored log lands trace content (and
     * any tokens or PII it carries) in clear text downstream of whatever sinks the log feeds. Shape-only
     * summary instead.
     */
    String summarizeRequest(ChatRequest request, String modelName, boolean useTools);

    /**
     * Build a sanitized one-line description of the LLM response. The full {@link ChatResponse} carries
     * the assistant text and any tool-call arguments, both of which can echo trace content the model is
     * reasoning about — surfacing the raw response in a user-facing log lands trace content (and any
     * tokens or PII it carries) downstream of whatever sinks the log feeds. Shape-only summary instead.
     */
    String summarizeResponse(ChatResponse response);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AgenticScoringServiceImpl implements AgenticScoringService {

    private final @NonNull @Config("onlineScoring") OnlineScoringConfig onlineScoringConfig;
    private final @NonNull ToolRegistry toolRegistry;

    @Override
    public Mono<ChatResponse> runToolCallLoop(@NonNull ChatResponse initialResponse,
            @NonNull ChatRequest toolRequest, @NonNull ChatRequest structuredRequest,
            @NonNull Supplier<TraceToolContext> contextSupplier,
            @NonNull Function<ChatRequest, Mono<ChatResponse>> scoreFn,
            @NonNull BudgetGuard costGuard,
            @NonNull Supplier<String> modelNameSupplier, String logId,
            @NonNull Logger userFacingLogger, @NonNull Map<String, String> mdc,
            @NonNull EvaluationRecorder recorder) {

        if (!initialResponse.aiMessage().hasToolExecutionRequests()) {
            return Mono.just(initialResponse);
        }

        // Defer so the context build + cache pre-seed + message-list allocation happen exactly once per
        // subscription. Follow-up rounds use ToolChoice.AUTO so the model can stop once it has enough
        // info; the initial REQUIRED forcing was applied by the caller when preparing the request.
        return Mono.defer(() -> {
            var ctx = contextSupplier.get();
            var followUpParameters = ChatRequestParameters.builder()
                    .overrideWith(toolRequest.parameters())
                    .toolChoice(ToolChoice.AUTO)
                    .build();
            var messages = new ArrayList<ChatMessage>(toolRequest.messages());
            var budget = new ToolCallLoop.Budget();

            return ToolCallLoop.runWithWrapUp(
                    initialResponse, toolRequest, structuredRequest, followUpParameters, toolRegistry,
                    scoreFn, messages, ctx, budget, costGuard, logId, mdc, recorder)
                    .onErrorResume(error -> surfaceInjectedMediaFailure(error, ctx, modelNameSupplier.get(),
                            userFacingLogger, mdc));
        });
    }

    /**
     * Shared error surfacing for the agentic-tools path: when the tool-call loop fails after at
     * least one attachment was injected as multimodal content, the most likely cause is the judge
     * model rejecting that media type (we attempt all types rather than pre-gating). Emit a clear,
     * attachment-attributed user-facing message before propagating, so a vision-incapable model
     * produces an understandable error rather than a raw provider stack trace. With no injected
     * media the failure passes through untouched.
     */
    // Package-private for unit tests.
    static <T> Mono<T> surfaceInjectedMediaFailure(@NonNull Throwable error,
            @NonNull TraceToolContext ctx, String modelName, @NonNull Logger userFacingLogger,
            @NonNull Map<String, String> mdc) {
        if (ctx.hasInjectedMedia()) {
            String attachments = ctx.getInjectedAttachments().stream()
                    .map(a -> "'%s' (%s)".formatted(a.fileName(), a.category().name().toLowerCase()))
                    .collect(Collectors.joining(", "));
            String detail = Optional.ofNullable(error.getCause()).map(Throwable::getMessage)
                    .orElse(error.getMessage());
            try (var logContext = wrapWithMdc(mdc)) {
                userFacingLogger.error(
                        "Scoring failed after loading attachment(s) '{}'; the judge model '{}' may not support this"
                                + " attachment type. Use a model that supports the attachment's media type. Details: '{}'",
                        attachments, modelName, detail, error);
            }
        }
        return Mono.error(error);
    }

    @Override
    public Mono<List<AttachmentInfo>> listAttachmentsToleratingUploadRace(
            @NonNull Mono<List<AttachmentInfo>> coldFetch, String workspaceId, UUID entityId,
            JsonNode... bodyNodes) {
        // Attach the failure log to the cold fetch itself so it fires only on a real lookup error — not
        // on the empty-completion-driven retries or the benign retry-exhaustion path below.
        Mono<List<AttachmentInfo>> fetch = coldFetch.doOnError(error -> log.warn(
                "Failed to list attachments for workspace '{}', entity '{}'; degrading to best-effort"
                        + " attachment discovery (online scoring will proceed without them)",
                workspaceId, entityId, error));

        Set<String> referencedNames = AttachmentUtils.collectAttachmentReferences(JsonUtils.getMapper(), bodyNodes);
        Function<List<AttachmentInfo>, List<AttachmentInfo>> group = attachments -> preferPersistentAttachments(
                attachments, referencedNames);
        if (referencedNames.isEmpty()) {
            return fetch.map(group).onErrorReturn(List.of());
        }
        return resolveWithUploadRaceTolerance(fetch, coldFetch, group,
                AgenticScoringServiceImpl::hasPersistentAttachment, List.of(),
                error -> log.warn(
                        "Best-effort attachment re-read failed for workspace '{}', entity '{}';"
                                + " online scoring will proceed without attachments",
                        workspaceId, entityId, error));
    }

    @Override
    public Mono<Map<UUID, List<AttachmentInfo>>> listSpanAttachmentsToleratingUploadRace(
            @NonNull Mono<List<AttachmentInfo>> coldBatchedFetch, String workspaceId, UUID traceId,
            @NonNull Set<UUID> spanIdsExpectingAttachment,
            @NonNull Map<UUID, Set<String>> referencedNamesBySpan) {
        Mono<List<AttachmentInfo>> logged = coldBatchedFetch.doOnError(error -> log.warn(
                "Failed to list span attachments for trace '{}' (workspace '{}'); degrading to none",
                traceId, workspaceId, error));
        Function<List<AttachmentInfo>, Map<UUID, List<AttachmentInfo>>> group = attachments -> groupBySpanPreferringPersistent(
                attachments, referencedNamesBySpan);
        if (spanIdsExpectingAttachment.isEmpty()) {
            return logged.map(group).onErrorReturn(Map.of());
        }
        return resolveWithUploadRaceTolerance(logged, coldBatchedFetch, group,
                bySpan -> spanIdsExpectingAttachment.stream()
                        .allMatch(id -> hasPersistentAttachment(bySpan.getOrDefault(id, List.of()))),
                Map.of(),
                error -> log.warn(
                        "Best-effort span-attachment re-read failed for trace '{}' (workspace '{}');"
                                + " online scoring will proceed without span attachments",
                        traceId, workspaceId, error));
    }

    /**
     * Shared retry/best-effort pipeline behind {@link #listAttachmentsToleratingUploadRace} and
     * {@link #listSpanAttachmentsToleratingUploadRace} — the two differ only in the grouping shape (a
     * single entity's list vs. a per-span map) and their logging identifiers, so both delegate here to
     * keep the retry-budget / fallback plumbing from drifting between them.
     *
     * <p>Resubscribes {@code fetch} (the caller's already-failure-logged cold fetch) via
     * {@code repeatWhenEmpty} until {@code group}'s output satisfies {@code isSatisfied}, up to
     * {@link OnlineScoringConfig#getAttachmentFetchMaxRetries()} attempts. If the budget is exhausted,
     * falls back to one more best-effort raw {@code coldFetch} read — logged via
     * {@code onBestEffortFailure} — before degrading to {@code emptyResult}.
     *
     * @param fetch              the primary-failure-logged cold fetch driving the retry loop
     * @param coldFetch          the raw cold fetch, resubscribed for the best-effort final read
     * @param group              maps the raw attachment list to the caller's shape
     * @param isSatisfied        whether {@code group}'s output means the expected attachment(s) landed
     * @param emptyResult        degraded value once retries are exhausted and the final read also fails
     * @param onBestEffortFailure logs the best-effort re-read failure with the caller's own identifiers
     */
    private <T> Mono<T> resolveWithUploadRaceTolerance(
            @NonNull Mono<List<AttachmentInfo>> fetch, @NonNull Mono<List<AttachmentInfo>> coldFetch,
            @NonNull Function<List<AttachmentInfo>, T> group, @NonNull Predicate<T> isSatisfied,
            @NonNull T emptyResult, @NonNull Consumer<Throwable> onBestEffortFailure) {
        return fetch
                .map(group)
                .filter(isSatisfied)
                .repeatWhenEmpty(onlineScoringConfig.getAttachmentFetchMaxRetries(),
                        repeats -> repeats.delayElements(
                                onlineScoringConfig.getAttachmentFetchRetryDelay().toJavaDuration()))
                .onErrorResume(error -> Mono.empty())
                // Retries exhausted: best-effort final read (raw coldFetch, so it needs its own failure
                // log — the primary attempt's log above does not cover this second subscription) rather
                // than dropping the attachment(s) outright.
                .switchIfEmpty(Mono.defer(() -> coldFetch
                        .map(group)
                        .doOnError(onBestEffortFailure)
                        .onErrorReturn(emptyResult)));
    }

    private static Map<UUID, List<AttachmentInfo>> groupBySpanPreferringPersistent(
            List<AttachmentInfo> attachments, Map<UUID, Set<String>> referencedNamesBySpan) {
        return attachments.stream()
                .filter(a -> a.entityId() != null)
                .collect(Collectors.groupingBy(AttachmentInfo::entityId)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> preferPersistentAttachments(e.getValue(),
                        referencedNamesBySpan.getOrDefault(e.getKey(), Set.of()))));
    }

    /**
     * Keeps every persistent attachment plus any auto-stripped attachment still referenced in the entity
     * body, and drops only <em>orphaned</em> auto-stripped copies (no longer referenced). A superseded
     * transient — one replaced by a persistent copy — is dropped because the body reference now points at
     * the persistent name, so surfacing the transient (which 404s once it is cleaned up) is avoided.
     *
     * <p>This is a per-attachment decision keyed on the body reference rather than an entity-wide "any
     * persistent ⇒ drop all auto-stripped" gate: the latter dropped a legitimate transient-only attachment
     * (e.g. a REST-ingested image) whenever an <em>unrelated</em> persistent attachment coexisted on the
     * same entity. Filenames can't pair a transient to its persistent twin (the backend transient name and
     * the SDK {@code -sdk} name share no key), so the body reference is the reliable signal.
     *
     * @param referencedNames the attachment filenames referenced in the entity body (see
     *                        {@link AttachmentUtils#collectAttachmentReferences})
     */
    private static List<AttachmentInfo> preferPersistentAttachments(List<AttachmentInfo> attachments,
            Set<String> referencedNames) {
        // When no persistent copy coexists, every auto-stripped copy is the real attachment (a backend-/
        // REST-ingested image with no SDK replacement) — keep them all.
        if (!hasPersistentAttachment(attachments)) {
            return attachments;
        }
        // A persistent copy coexists: keep every persistent attachment plus any auto-stripped copy still
        // referenced in the body, and drop only orphaned auto-stripped copies (no longer referenced).
        return attachments.stream()
                .filter(attachment -> !AttachmentUtils.isAutoStrippedAttachment(attachment.fileName())
                        || referencedNames.contains(attachment.fileName()))
                .collect(Collectors.toList());
    }

    private static boolean hasPersistentAttachment(List<AttachmentInfo> attachments) {
        return attachments.stream().anyMatch(a -> !AttachmentUtils.isAutoStrippedAttachment(a.fileName()));
    }

    @Override
    public boolean supportsToolCalling(@NonNull LlmProvider provider) {
        return switch (provider) {
            case OPEN_AI, ANTHROPIC, GEMINI, OPEN_ROUTER, VERTEX_AI, BEDROCK -> true;
            case OLLAMA, CUSTOM_LLM, OPIK_FREE -> false;
        };
    }

    @Override
    public ChatRequest addToolSpecs(@NonNull ChatRequest request, @NonNull ToolChoice toolChoice) {
        var parameters = ChatRequestParameters.builder()
                .overrideWith(request.parameters())
                .toolSpecifications(toolRegistry.specs())
                .toolChoice(toolChoice)
                .build();
        return request.toBuilder()
                .parameters(parameters)
                .build();
    }

    @Override
    public int estimateTokensFromJson(@NonNull JsonNode fullJson, int charsPerToken) {
        Preconditions.checkArgument(charsPerToken >= 1, "charsPerToken must be >= 1, got %s", charsPerToken);
        return fullJson.toString().length() / charsPerToken;
    }

    @Override
    public int estimateTraceContextTokens(@NonNull Trace trace, @NonNull List<Span> spans,
            @NonNull TraceCompressor traceCompressor, int charsPerToken) {
        return estimateTokensFromJson(traceCompressor.buildFullJson(trace, spans), charsPerToken);
    }

    @Override
    public int estimateThreadContextTokens(@NonNull List<Trace> traces, @NonNull List<Span> spans,
            int charsPerToken) {
        Preconditions.checkArgument(charsPerToken >= 1, "charsPerToken must be >= 1, got %s", charsPerToken);
        return JsonUtils.writeValueAsString(OnlineScoringEngine.fromTraceToThreadEnriched(traces, spans)).length()
                / charsPerToken;
    }

    @Override
    public String summarizeRequest(@NonNull ChatRequest request, @NonNull String modelName, boolean useTools) {
        // Intentionally NOT computing total chars: m.toString() on a multi-MB rendered prompt
        // allocates the full string just to measure its length, which would add ~2x prompt-size
        // heap churn per evaluation. Message count + tool count are enough to identify what's
        // happening; an operator who needs byte-level detail can hit the rule's debug log.
        int messageCount = request.messages() == null ? 0 : request.messages().size();
        int toolSpecCount = request.toolSpecifications() == null ? 0 : request.toolSpecifications().size();
        return String.format("model='%s', messages=%d, tools=%d, toolsEnabled=%s",
                modelName, messageCount, toolSpecCount, useTools);
    }

    @Override
    public String summarizeResponse(@NonNull ChatResponse response) {
        var ai = response.aiMessage();
        int textLength = ai.text() == null ? 0 : ai.text().length();
        int toolCallCount = ai.toolExecutionRequests() == null ? 0 : ai.toolExecutionRequests().size();
        var finishReason = response.metadata() == null ? null : response.metadata().finishReason();
        return String.format("textChars=%d, toolCalls=%d, finishReason=%s",
                textLength, toolCallCount, finishReason);
    }
}
