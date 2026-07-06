package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.api.resources.v1.events.tools.ToolRegistry;
import com.comet.opik.api.resources.v1.events.tools.TraceToolContext;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.domain.evaluation.EvaluationRecorder;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringStreamConfigurationAdapter;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.response.ChatResponse;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.redisson.api.RedissonReactiveClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.infrastructure.log.LogContextAware.wrapWithMdc;

/**
 * Base online scorer for all particular implementations to extend. It listens to a Redis stream for
 * Traces/Spans/Threads to be scored. Subclasses provide a particular {@link #score(Object)} implementation that
 * returns a {@link Mono} so the entire processing chain stays non-blocking from Redis read to feedback-score
 * persistence. The Reactor pipeline owned by {@link BaseRedisSubscriber} schedules execution on the per-stream
 * worker scheduler; subclasses should NOT call {@code .block()} from {@code score()}.
 */
public abstract class OnlineScoringBaseScorer<M extends RedisSubscriberMessage> extends BaseRedisSubscriber<M> {

    public static final int TRACE_PAGE_LIMIT = 2000;

    /**
     * Truncation marker hint for the no-tools inline {@code {{trace}}} / {@code {{span}}} fallback. There
     * are no {@code read}/{@code jq} tools to drill in, so the hint just flags that the value was
     * truncated rather than pointing at a (non-existent) follow-up tool.
     */
    protected static final String INLINE_TRUNCATION_HINT = "full content not shown";

    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final OnlineScoringConfig onlineScoringConfig;
    protected final FeedbackScoreService feedbackScoreService;
    protected final TraceService traceService;
    protected final AutomationRuleEvaluatorType type;

    protected OnlineScoringBaseScorer(@NonNull @Config OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull TraceService traceService,
            @NonNull AutomationRuleEvaluatorType type,
            @NonNull String metricsBaseName) {
        super(OnlineScoringStreamConfigurationAdapter.create(config, type),
                redisson,
                OnlineScoringConfig.PAYLOAD_FIELD,
                ONLINE_SCORING_NAMESPACE,
                metricsBaseName);
        this.onlineScoringConfig = config;
        this.feedbackScoreService = feedbackScoreService;
        this.traceService = traceService;
        this.type = type;
    }

    /**
     * Shared agentic tool-loop orchestration for the trace- and span-level scorers. Returns the initial
     * response untouched when it carries no tool calls; otherwise defers to subscription time and runs
     * {@link ToolCallLoop#runWithWrapUp} with {@code ToolChoice.AUTO} follow-ups, surfacing any
     * injected-media failure via {@link #surfaceInjectedMediaFailure}.
     *
     * <p>The entity-specific parts — building + pre-seeding the {@link TraceToolContext} (trace vs span)
     * and the per-message scoring call — are supplied by the caller as {@code contextSupplier} and
     * {@code scoreFn}. The supplier is invoked inside the {@code defer} so context creation and cache
     * pre-seed happen exactly once per subscription.
     *
     * @param contextSupplier builds and pre-seeds the tool context (invoked at subscription time)
     * @param scoreFn         issues a single LLM call (e.g. {@code request -> scoreTraceReactive(...)})
     * @param logId           the trace/span id used as the tool-loop log correlation id
     * @param recorder        evaluation monitoring recorder threaded into {@link ToolCallLoop} so each
     *                        tool call is recorded (OPIK-6994); pass {@link EvaluationRecorder#NOOP} when
     *                        monitoring is off
     */
    protected Mono<ChatResponse> runToolCallLoop(@NonNull ChatResponse initialResponse,
            @NonNull ChatRequest toolRequest, @NonNull ChatRequest structuredRequest,
            @NonNull Supplier<TraceToolContext> contextSupplier, @NonNull ToolRegistry toolRegistry,
            @NonNull Function<ChatRequest, Mono<ChatResponse>> scoreFn, String modelName, String logId,
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
                    scoreFn, messages, ctx, budget, logId, mdc, recorder)
                    .onErrorResume(error -> surfaceInjectedMediaFailure(error, ctx, modelName,
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
     *
     * <p>Static + parameterized on {@code userFacingLogger} / {@code modelName} so the trace-, span-
     * and thread-level scorers can all reuse it despite each owning its own logger and model accessor.
     */
    protected static <T> Mono<T> surfaceInjectedMediaFailure(@NonNull Throwable error,
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
                        "Scoring failed after loading attachment(s) {}; the judge model '{}' may not support this"
                                + " attachment type. Use a model that supports the attachment's media type. Details: {}",
                        attachments, modelName, detail);
            }
        }
        return Mono.error(error);
    }

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
    protected Mono<List<AttachmentInfo>> listAttachmentsToleratingUploadRace(
            @NonNull Mono<List<AttachmentInfo>> coldFetch, String workspaceId, UUID entityId,
            JsonNode... bodyNodes) {
        // Attach the failure log to the cold fetch itself so it fires only on a real lookup error — not
        // on the empty-completion-driven retries or the benign retry-exhaustion path below.
        Mono<List<AttachmentInfo>> fetch = coldFetch.doOnError(error -> log.warn(
                "Failed to list attachments for workspace '{}', entity '{}'; degrading to best-effort"
                        + " attachment discovery (online scoring will proceed without them)",
                workspaceId, entityId, error));

        boolean expectsAttachment = AttachmentUtils.hasAttachmentReferences(JsonUtils.getMapper(), bodyNodes);
        if (!expectsAttachment) {
            return fetch.map(OnlineScoringBaseScorer::preferPersistentAttachments).onErrorReturn(List.of());
        }
        return fetch
                .map(OnlineScoringBaseScorer::preferPersistentAttachments)
                .filter(OnlineScoringBaseScorer::hasPersistentAttachment)
                .repeatWhenEmpty(onlineScoringConfig.getAttachmentFetchMaxRetries(),
                        repeats -> repeats.delayElements(
                                onlineScoringConfig.getAttachmentFetchRetryDelay().toJavaDuration()))
                .onErrorResume(error -> Mono.empty())
                // Retries exhausted (no persistent copy will come): best-effort final read so a
                // backend-/REST-only auto-stripped attachment is still surfaced rather than dropped. Uses
                // the raw coldFetch, so log its own failure here (the primary attempt's log above does not
                // cover this second subscription) before degrading to an empty list.
                .switchIfEmpty(Mono.defer(() -> coldFetch
                        .map(OnlineScoringBaseScorer::preferPersistentAttachments)
                        .doOnError(error -> log.warn(
                                "Best-effort attachment re-read failed for workspace '{}', entity '{}';"
                                        + " online scoring will proceed without attachments",
                                workspaceId, entityId, error))
                        .onErrorReturn(List.of())));
    }

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
     * @param coldBatchedFetch          the batched lookup — must be cold (re-runs on each subscription)
     * @param workspaceId               workspace id, for the failure log
     * @param traceId                   the trace id, for the failure log
     * @param spanIdsExpectingAttachment span ids whose body references an attachment (drives the retry)
     */
    protected Mono<Map<UUID, List<AttachmentInfo>>> listSpanAttachmentsToleratingUploadRace(
            @NonNull Mono<List<AttachmentInfo>> coldBatchedFetch, String workspaceId, UUID traceId,
            @NonNull Set<UUID> spanIdsExpectingAttachment) {
        Mono<List<AttachmentInfo>> logged = coldBatchedFetch.doOnError(error -> log.warn(
                "Failed to list span attachments for trace '{}' (workspace '{}'); degrading to none",
                traceId, workspaceId, error));
        if (spanIdsExpectingAttachment.isEmpty()) {
            return logged.map(OnlineScoringBaseScorer::groupBySpanPreferringPersistent).onErrorReturn(Map.of());
        }
        return logged
                .map(OnlineScoringBaseScorer::groupBySpanPreferringPersistent)
                .filter(bySpan -> spanIdsExpectingAttachment.stream()
                        .allMatch(id -> hasPersistentAttachment(bySpan.getOrDefault(id, List.of()))))
                .repeatWhenEmpty(onlineScoringConfig.getAttachmentFetchMaxRetries(),
                        repeats -> repeats.delayElements(
                                onlineScoringConfig.getAttachmentFetchRetryDelay().toJavaDuration()))
                .onErrorResume(error -> Mono.empty())
                // Retries exhausted (some referenced attachment never got a persistent copy — e.g. a
                // REST-ingested image): best-effort grouping. Raw fetch, so log its own failure here (the
                // primary attempt's log does not cover this second subscription) before degrading to none.
                .switchIfEmpty(Mono.defer(() -> coldBatchedFetch
                        .map(OnlineScoringBaseScorer::groupBySpanPreferringPersistent)
                        .doOnError(error -> log.warn(
                                "Best-effort span-attachment re-read failed for trace '{}' (workspace '{}');"
                                        + " online scoring will proceed without span attachments",
                                traceId, workspaceId, error))
                        .onErrorReturn(Map.of())));
    }

    private static Map<UUID, List<AttachmentInfo>> groupBySpanPreferringPersistent(
            List<AttachmentInfo> attachments) {
        return attachments.stream()
                .filter(a -> a.entityId() != null)
                .collect(Collectors.groupingBy(AttachmentInfo::entityId)).entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, e -> preferPersistentAttachments(e.getValue())));
    }

    /**
     * When both a transient auto-stripped copy and a persistent copy of an upload are present, drops the
     * auto-stripped one (it is deleted once the persistent copy lands, so surfacing it hands the judge a
     * name that 404s). When only auto-stripped copies exist (a backend-/REST-ingested image with no SDK
     * copy), they are the real attachments and are kept as-is.
     */
    protected static List<AttachmentInfo> preferPersistentAttachments(List<AttachmentInfo> attachments) {
        if (!hasPersistentAttachment(attachments)) {
            return attachments;
        }
        return attachments.stream()
                .filter(attachment -> !AttachmentUtils.isAutoStrippedAttachment(attachment.fileName()))
                .collect(Collectors.toList());
    }

    private static boolean hasPersistentAttachment(List<AttachmentInfo> attachments) {
        return attachments.stream().anyMatch(a -> !AttachmentUtils.isAutoStrippedAttachment(a.fileName()));
    }

    /**
     * Propagates the workspace/user the message belongs to onto the reactive context for the whole
     * scoring chain (feedback-score persistence reads it). Per-message throughput and error metrics are
     * attributed automatically by {@link BaseRedisSubscriber} from {@link #messageContext(Object)}.
     */
    @Override
    protected final Mono<Void> processEvent(M message) {
        var workspaceName = StringUtils.defaultIfBlank(message.workspaceName(), message.workspaceId());
        return doScore(message)
                // Sourced from the message (resolved from RequestContext.WORKSPACE_NAME at trace-event
                // publish time).
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.WORKSPACE_NAME, workspaceName)
                        .put(RequestContext.USER_NAME, message.userName()));
    }

    /**
     * Full per-message processing chain. Defaults to {@link #score(Object)}, deferred so any
     * synchronous work runs at subscription time on the per-stream worker scheduler. Subclasses
     * that need post-scoring steps (e.g. test-suite assertion finalization) override this — not
     * {@code processEvent} — so the base class records the message as processed only once the whole
     * chain completes successfully.
     */
    protected Mono<Void> doScore(M message) {
        return Mono.defer(() -> score(message));
    }

    /**
     * Scores the message and persists the resulting feedback scores. Implementations must compose
     * reactive operators (no {@code .block()}); see {@link #storeScores}, {@link #storeSpanScores},
     * {@link #storeThreadScores}.
     */
    protected abstract Mono<Void> score(M message);

    protected Mono<Map<String, List<BigDecimal>>> storeScores(
            List<FeedbackScoreBatchItem> scores, Trace trace, String userName, String workspaceId) {
        log.info("Received '{}' scores for traceId '{}' in workspace '{}'. Storing them",
                scores.size(), trace.id(), workspaceId);
        return feedbackScoreService.scoreBatchOfTraces(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .thenReturn(groupScoresByName(scores));
    }

    protected Mono<Map<String, List<BigDecimal>>> storeSpanScores(
            List<FeedbackScoreBatchItem> scores, com.comet.opik.api.Span span, String userName, String workspaceId) {
        log.info("Received '{}' scores for spanId '{}' in workspace '{}'. Storing them",
                scores.size(), span.id(), workspaceId);
        return feedbackScoreService.scoreBatchOfSpans(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .thenReturn(groupScoresByName(scores));
    }

    protected Mono<Map<String, List<BigDecimal>>> storeThreadScores(
            List<FeedbackScoreBatchItemThread> scores, String threadId, String userName, String workspaceId) {
        log.info("Received '{}' scores for threadId '{}' in workspace '{}'. Storing them",
                scores.size(), threadId, workspaceId);
        return feedbackScoreService.scoreBatchOfThreads(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .thenReturn(groupScoresByName(scores));
    }

    private static <T extends FeedbackScoreItem> Map<String, List<BigDecimal>> groupScoresByName(List<T> scores) {
        return scores.stream()
                .collect(Collectors.groupingBy(FeedbackScoreItem::name,
                        Collectors.mapping(FeedbackScoreItem::value, Collectors.toList())));
    }

    /**
     * Retrieves the full thread context for a given thread ID, recursively fetching traces until no more are found.
     *
     * @param threadId the ID of the thread to retrieve context for
     * @param lastReceivedIdRef a reference to store the last received trace ID
     * @param projectId the ID of the project to which the thread belongs
     * @return a Flux of Trace objects representing the full thread context
     */
    //TODO: Move this to a common service or utility class
    protected Flux<Trace> retrieveFullThreadContext(@NotNull String threadId,
            @NotNull AtomicReference<UUID> lastReceivedIdRef, @NotNull UUID projectId) {

        return Flux.defer(() -> traceService.search(TRACE_PAGE_LIMIT, TraceSearchCriteria.builder()
                .projectId(projectId)
                .filters(List.of(TraceFilter.builder()
                        .field(TraceField.THREAD_ID)
                        .operator(Operator.EQUAL)
                        .value(threadId)
                        .build()))
                .lastReceivedId(lastReceivedIdRef.get())
                .build())
                .collectList()
                .flatMapMany(results -> {
                    if (results.isEmpty()) {
                        return Flux.empty();
                    }
                    lastReceivedIdRef.set(results.getLast().id());
                    return Flux.fromIterable(results)
                            .concatWith(Flux
                                    .defer(() -> retrieveFullThreadContext(threadId, lastReceivedIdRef, projectId)));
                }));
    }
}
