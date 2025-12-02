package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.filter.TraceField;
import com.comet.opik.api.filter.TraceFilter;
import com.comet.opik.domain.FeedbackScoreService;
import com.comet.opik.domain.TraceSearchCriteria;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.OnlineScoringConfig;
import com.comet.opik.infrastructure.OnlineScoringStreamConfigurationAdapter;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.validation.constraints.NotNull;
import lombok.NonNull;
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
 * This is the base online scorer, for all particular implementations to extend. It listens to a Redis stream for
 * Traces to be scored. Extending classes must provide a particular implementation for the score method.
 * This class extends BaseRedisSubscriber to reuse common Redis stream handling functionality.
 */
public abstract class OnlineScoringBaseScorer<M> extends BaseRedisSubscriber<M> {

    public static final int TRACE_PAGE_LIMIT = 2000;
    private static final String ONLINE_SCORING_NAMESPACE = "online_scoring";

    /**
     * Logger for the actual subclass, in order to have the correct class name in the logs.
     */
    private final Logger log = LoggerFactory.getLogger(this.getClass());

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
        this.feedbackScoreService = feedbackScoreService;
        this.traceService = traceService;
        this.type = type;
    }

    @Override
    protected Mono<Void> processEvent(M message) {
        return Mono.fromRunnable(() -> score(message));
    }

    /**
     * Provide a particular implementation to score the trace and store it as a FeedbackScore.
     * @param message a Redis message with Trace to score, workspace and username.
     */
    protected abstract void score(M message);

    protected Map<String, List<BigDecimal>> storeScores(
            List<FeedbackScoreBatchItem> scores, Trace trace, String userName, String workspaceId) {
        log.info("Received '{}' scores for traceId '{}' in workspace '{}'. Storing them",
                scores.size(), trace.id(), workspaceId);
        feedbackScoreService.scoreBatchOfTraces(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
        return scores.stream()
                .collect(Collectors.groupingBy(FeedbackScoreItem::name,
                        Collectors.mapping(FeedbackScoreItem::value, Collectors.toList())));
    }

    protected Map<String, List<BigDecimal>> storeSpanScores(
            List<FeedbackScoreBatchItem> scores, com.comet.opik.api.Span span, String userName, String workspaceId) {
        log.info("Received '{}' scores for spanId '{}' in workspace '{}'. Storing them",
                scores.size(), span.id(), workspaceId);
        feedbackScoreService.scoreBatchOfSpans(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
        return scores.stream()
                .collect(Collectors.groupingBy(FeedbackScoreItem::name,
                        Collectors.mapping(FeedbackScoreItem::value, Collectors.toList())));
    }

    protected Map<String, List<BigDecimal>> storeThreadScores(
            List<FeedbackScoreBatchItemThread> scores, String threadId, String userName, String workspaceId) {
        log.info("Received '{}' scores for threadId '{}' in workspace '{}'. Storing them",
                scores.size(), threadId, workspaceId);
        feedbackScoreService.scoreBatchOfThreads(scores)
                .contextWrite(ctx -> ctx.put(RequestContext.USER_NAME, userName)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();
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
