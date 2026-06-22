package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.events.WorkspaceScopedMessage;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringStreamConfigurationAdapter;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.metrics.LongCounter;
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
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;

/**
 * Base online scorer for all particular implementations to extend. It listens to a Redis stream for
 * Traces/Spans/Threads to be scored. Subclasses provide a particular {@link #score(Object)} implementation that
 * returns a {@link Mono} so the entire processing chain stays non-blocking from Redis read to feedback-score
 * persistence. The Reactor pipeline owned by {@link BaseRedisSubscriber} schedules execution on the per-stream
 * worker scheduler; subclasses should NOT call {@code .block()} from {@code score()}.
 */
public abstract class OnlineScoringBaseScorer<M extends WorkspaceScopedMessage> extends BaseRedisSubscriber<M> {

    public static final int TRACE_PAGE_LIMIT = 2000;
    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";
    private static final AttributeKey<String> WORKSPACE_ID_KEY = AttributeKey.stringKey("workspace_id");
    private static final AttributeKey<String> WORKSPACE_NAME_KEY = AttributeKey.stringKey("workspace_name");

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

    protected final FeedbackScoreService feedbackScoreService;
    protected final TraceService traceService;
    protected final AutomationRuleEvaluatorType type;

    /**
     * Per-workspace count of messages successfully processed (scored) by this stream. Together with
     * {@code online_scoring_<scorer>_processing_errors_total} (failures) this gives the consumer-side
     * processed-vs-failed split per workspace. Exported as {@code online_scoring_<scorer>_processed_total}.
     */
    private final LongCounter processedCounter;

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
        this.feedbackScoreService = feedbackScoreService;
        this.traceService = traceService;
        this.type = type;
        this.processedCounter = GlobalOpenTelemetry.getMeter(ONLINE_SCORING_NAMESPACE)
                .counterBuilder("%s_%s_processed".formatted(ONLINE_SCORING_NAMESPACE, metricsBaseName))
                .setDescription("Messages successfully processed (scored), by workspace")
                .build();
    }

    /**
     * Records the per-workspace processed count only after the FULL processing chain succeeds.
     * The counter wraps {@link #doScore(Object)} (which subclasses may extend with post-scoring
     * work), so a failure anywhere in the chain leaves the message unacked/retried and does NOT
     * count as processed. Failures are attributed via {@link #messageContext(Object)}.
     */
    @Override
    protected final Mono<Void> processEvent(M message) {
        var workspaceName = StringUtils.defaultIfBlank(message.workspaceName(), message.workspaceId());
        return doScore(message)
                .doOnSuccess(ignored -> processedCounter.add(1, Attributes.of(
                        WORKSPACE_ID_KEY, message.workspaceId(),
                        WORKSPACE_NAME_KEY, workspaceName)))
                // Carry both workspace id and name on the reactive context for the whole chain, sourced
                // from the message (resolved from RequestContext.WORKSPACE_NAME at trace-event publish time).
                .contextWrite(ctx -> ctx
                        .put(RequestContext.WORKSPACE_ID, message.workspaceId())
                        .put(RequestContext.WORKSPACE_NAME, workspaceName)
                        .put(RequestContext.USER_NAME, message.userName()));
    }

    /**
     * Full per-message processing chain. Defaults to {@link #score(Object)}, deferred so any
     * synchronous work runs at subscription time on the per-stream worker scheduler. Subclasses
     * that need post-scoring steps (e.g. test-suite assertion finalization) override this — not
     * {@code processEvent} — so the processed-success counter fires only once the whole chain
     * completes successfully.
     */
    protected Mono<Void> doScore(M message) {
        return Mono.defer(() -> score(message));
    }

    /**
     * Attributes processing-error metrics to the workspace/user the message belongs to. Without this
     * override the base class falls back to {@link MessageContext#UNKNOWN}, which is why
     * {@code online_scoring_*_processing_errors_total} historically reported {@code workspace_id="unknown"}.
     * The workspace name is carried on the message (resolved from RequestContext.WORKSPACE_NAME at
     * trace-event publish time); falls back to the id when absent.
     */
    @Override
    protected MessageContext messageContext(M message) {
        return new MessageContext(message.workspaceId(),
                StringUtils.defaultIfBlank(message.workspaceName(), message.workspaceId()),
                message.userName());
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
