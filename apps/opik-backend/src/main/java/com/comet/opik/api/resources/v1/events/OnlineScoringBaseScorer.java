package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.RedisSubscriberMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.SpanService;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.domain.evaluators.OnlineScorePublisher;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringStreamConfigurationAdapter;
import com.comet.opik.infrastructure.auth.RequestContext;
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
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
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
    protected final SpanService spanService;
    protected final AutomationRuleEvaluatorType type;

    protected OnlineScoringBaseScorer(@NonNull @Config OnlineScoringConfig config,
            @NonNull RedissonReactiveClient redisson,
            @NonNull FeedbackScoreService feedbackScoreService,
            @NonNull TraceService traceService,
            @NonNull SpanService spanService,
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
        this.spanService = spanService;
        this.type = type;
    }

    /**
     * Sentinel emitted by {@link #spansSizeOrUnavailable} when the span-size aggregate could not be
     * computed. Deliberately distinct from a genuine {@code 0} bytes so callers can skip the bounded
     * preload on this path: without a size there is no way to tell a small thread from one that would
     * blow the heap, and an aggregate that just failed is itself a reason not to follow it with a bulk
     * fetch. See OPIK-7454.
     */
    protected static final long SPAN_SIZE_UNAVAILABLE = -1L;

    /**
     * The thread's span size from the cheap ClickHouse aggregate, with sizing treated as <em>advisory</em>
     * rather than a prerequisite. Letting a failure propagate would abort the caller's chain, and
     * {@link BaseRedisSubscriber} would retry {@code maxRetries} times and then acknowledge the message —
     * permanently dropping the evaluation. On failure this emits {@link #SPAN_SIZE_UNAVAILABLE} instead, so
     * the caller can route conservatively and still score.
     *
     * <p>Shared by both trace-thread scorers so the sentinel semantics and the recovery boundary cannot
     * drift apart between them.
     */
    protected Mono<Long> spansSizeOrUnavailable(Set<UUID> traceIds, String workspaceId, String userName,
            String threadId) {
        return spanService.getSpansSizeByTraceIds(traceIds)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .onErrorResume(error -> {
                    log.warn("Span-size aggregate failed for workspace '{}' and thread '{}'; scoring without"
                            + " span enrichment", workspaceId, threadId, error);
                    return Mono.just(SPAN_SIZE_UNAVAILABLE);
                });
    }

    /**
     * The trace-thread span-preload cap in bytes. Single place that converts the MB-denominated config
     * ({@code onlineScoring.agenticToolsMaxPreloadMb}) so the trace-thread scorers pass a consistent value
     * to both the size gate and the bounded preload. See OPIK-7454.
     */
    protected long agenticToolsMaxPreloadBytes() {
        return (long) onlineScoringConfig.getAgenticToolsMaxPreloadMb() * 1024 * 1024;
    }

    /**
     * Returns the buffered spans from a bounded preload and, as a side effect, emits a user-facing warning
     * when the preload {@link ThreadSpanPreload#overflowed()} the byte cap even though the size estimate had
     * routed the thread to the enriched path — i.e. the cheap size aggregate under-counted the real
     * serialized size. The overflow is already handled safely upstream (the buffer was dropped, so the
     * returned list is empty and the thread scores with the unenriched context); the warning just makes the
     * otherwise-silent fallback visible. See OPIK-7454.
     */
    protected List<Span> getSpansFromPreloadAndLogOverflow(@NonNull ThreadSpanPreload preload,
            @NonNull Logger userFacingLogger, String threadId, Map<String, String> mdc) {
        if (preload.overflowed()) {
            try (var logContext = wrapWithMdc(mdc)) {
                userFacingLogger.warn("""
                        Thread span preload exceeded the enrichment cap despite a fitting size estimate; \
                        scoring with the unenriched context. threadId='{}', approxBytes='{}', capBytes='{}'""",
                        threadId, preload.approxBytes(), agenticToolsMaxPreloadBytes());
            }
        }
        return preload.spans();
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

    /**
     * Routes a trace-thread entry down one of two branches: an entry carrying several thread ids was
     * written by an older build and is <b>migrated</b> — republished as one entry per id, never scored —
     * while the single-id entries this build writes are <b>scored</b> normally. Completion therefore does
     * not mean "scored", so per-outcome logging belongs inside {@code scoreThread} or in the migrate
     * branch, never on the returned Mono.
     *
     * <p>Migrating rather than scoring avoids needing a rule for reducing N per-thread outcomes into the
     * one verdict a stream entry gets, which must mis-serve somebody when a permanent failure and a
     * retryable one land together. Each replacement entry gets its own retry budget instead.
     *
     * <p>The ack is implicit: this returns the republish, so a failure leaves the entry unacked and it
     * redelivers. If the republish succeeds but the ack fails, the entry splits twice and some threads are
     * scored twice — tolerable because {@code feedback_scores} is a {@code ReplacingMergeTree} versioned on
     * {@code last_updated_at}, so the second score overwrites rather than duplicating.
     *
     * <p>The migrate branch is a temporary shim, deletable once no pre-split entry can be in flight.
     */
    protected Mono<Void> migrateOrScoreThreadIds(@NonNull M message, @NonNull List<String> threadIds,
            @NonNull OnlineScorePublisher publisher, @NonNull Function<String, M> singleThreadIdCopy,
            @NonNull Function<String, Mono<Void>> scoreThread) {
        if (threadIds.size() > 1) {
            // Logged on success, and worded so it can never be mistaken for a scoring log.
            return publisher.enqueueMessage(threadIds.stream().map(singleThreadIdCopy).toList(), type)
                    .doOnSuccess(unused -> log.info(
                            "Migrated '{}' legacy thread ids to single-id entries for workspace '{}'; "
                                    + "no scoring performed for this entry",
                            threadIds.size(), message.workspaceId()));
        }
        // @NotEmpty says this cannot happen; if it does, no retry would help, so let it be acked away.
        if (threadIds.isEmpty()) {
            log.warn("Discarding trace-thread entry with no thread ids for workspace '{}'", message.workspaceId());
            return Mono.empty();
        }
        return scoreThread.apply(threadIds.getFirst())
                .contextWrite(context -> context.put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.USER_NAME, message.userName())
                        .put(RequestContext.VISIBILITY, Visibility.PRIVATE));
    }
}
