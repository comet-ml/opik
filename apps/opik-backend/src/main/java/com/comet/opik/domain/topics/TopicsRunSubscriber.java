package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.v1.events.BaseRedisSubscriber;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;

/**
 * Runs the Topics runs the planner queued ({@link TopicsRunPlanner}), {@code topics.runs.consumerBatchSize} at a time
 * per pod. A pod runs a run only once it claims its row ({@code queued -> running}); a duplicate or late stream entry
 * finds the row claimed or ended and is acknowledged with no work. While a run goes on, its pod writes the row's
 * heartbeat every {@code topics.runs.heartbeatInterval}; a heartbeat that finds the row no longer {@code running}
 * (cancelled, or recorded abandoned) stops the run. A pod that stops puts its runs back to {@code queued}, so another
 * pod runs them, and the stop is not counted as a failure.
 */
@Slf4j
@EagerSingleton
public class TopicsRunSubscriber extends BaseRedisSubscriber<TopicsRunMessage> {

    private static final String METRICS_NAMESPACE = "opik";
    private static final String METRICS_BASE_NAME = "topics_runs";
    private static final String RUN_USER = "topics-runs";

    private final TopicsConfig config;
    private final TopicRunStore store;
    private final TopicsRunService runService;
    private final TopicWindowLabelService labelService;
    private final TopicsRunPlanner planner;

    @Inject
    TopicsRunSubscriber(@NonNull @Config("topics") TopicsConfig config, @NonNull RedissonReactiveClient redisson,
            @NonNull TopicRunStore store, @NonNull TopicsRunService runService,
            @NonNull TopicWindowLabelService labelService, @NonNull TopicsRunPlanner planner) {
        super(config.getRuns(), redisson, TopicsConfig.RunsConfig.PAYLOAD_FIELD, METRICS_NAMESPACE,
                METRICS_BASE_NAME);
        this.config = config;
        this.store = store;
        this.runService = runService;
        this.labelService = labelService;
        this.planner = planner;
    }

    @Override
    public void start() {
        if (config.isEnabled()) {
            super.start();
        }
    }

    @Override
    public void stop() {
        if (config.isEnabled()) {
            super.stop();
        }
    }

    @Override
    protected Mono<Void> processEvent(@NonNull TopicsRunMessage message) {
        var limit = runLimit(message.mode());
        var deadline = Instant.now().plus(limit);
        return store.claim(message, deadline)
                .flatMap(claimed -> {
                    if (!claimed) {
                        log.info("Topics run '{}' not claimed, project '{}': another pod has it, or it ended",
                                message.runId(), message.projectId());
                        return Mono.empty();
                    }
                    log.info("Topics run '{}' claimed, workspace '{}', project '{}', mode '{}'", message.runId(),
                            message.workspaceId(), message.projectId(), message.mode().getValue());
                    return store.resolveWorkspace(message.workspaceId())
                            .flatMap(workspace -> run(message, deadline)
                                    .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspace.id())
                                            .put(RequestContext.WORKSPACE_NAME, workspace.name())
                                            .put(RequestContext.USER_NAME, RUN_USER)))
                            .takeUntilOther(stopped(message))
                            // A backstop: the run ends itself by its deadline, and the planner cancels it after.
                            .timeout(limit.plus(config.getRuns().getAbandonGrace().toJavaDuration()),
                                    Mono.defer(() -> fail(message, RunError.TIMEOUT)))
                            .then(Mono.defer(() -> store.endCancelled(message.workspaceId(), message.projectId(),
                                    message.runId())))
                            .doOnNext(code -> log.info("Topics run '{}' stopped, project '{}', code '{}'",
                                    message.runId(), message.projectId(), code.getValue()))
                            // Only the pod's stop cancels a run from outside: give it back to the queue.
                            .doOnCancel(() -> requeueOnStop(message));
                })
                .onErrorResume(error -> {
                    log.error("Topics run '{}' ended with an error, project '{}'; the planner settles its row",
                            message.runId(), message.projectId(), error);
                    return Mono.empty();
                })
                .then();
    }

    /** Blocks the stop until the row is queued again, so the next pod's claim does not wait for the abandon. */
    private void requeueOnStop(TopicsRunMessage message) {
        try {
            log.info("Topics run '{}' requeued '{}', project '{}', its pod stops", message.runId(),
                    store.requeue(message).block(config.getRuns().getRequeueWait().toJavaDuration()),
                    message.projectId());
        } catch (RuntimeException exception) {
            log.warn("Topics run '{}' not requeued, project '{}'; the planner records it abandoned",
                    message.runId(), message.projectId(), exception);
        }
    }

    /** How long a run of {@code mode} may last: its deadline is the claim plus this. */
    private Duration runLimit(RunMode mode) {
        var timeout = config.getRun().getProjectTimeout().toJavaDuration();
        return mode == RunMode.LABELS ? timeout : timeout.plus(insertBound(config.getRun()));
    }

    /** The longest a saved run's {@code trace_topics} insert takes: every attempt to its stop, the backoffs, the slack. */
    static Duration insertBound(@NonNull TopicsConfig.RunConfig run) {
        int retries = run.getInsertRetries();
        var backoffs = run.getInsertBackoff().toJavaDuration().multipliedBy((1L << retries) - 1);
        return run.getReadTimeout().toJavaDuration().multipliedBy(retries + 1L).plus(backoffs)
                .plus(run.getInsertSlack().toJavaDuration());
    }

    /** The run's workspace, which the subscriber puts in the Reactor context. */
    private static Mono<TopicsMetrics.Workspace> workspace() {
        return Mono.deferContextual(ctx -> Mono.just(new TopicsMetrics.Workspace(
                ctx.get(RequestContext.WORKSPACE_ID), ctx.get(RequestContext.WORKSPACE_NAME))));
    }

    private Mono<Void> run(TopicsRunMessage message, Instant deadline) {
        return workspace().flatMap(workspace -> switch (message.mode()) {
            case DISCOVERY -> runService.discover(message.runId(), workspace, message.projectId(), message.night(),
                    message.windowTraces())
                    .filter(outcome -> outcome.status() == TopicProject.RunStatus.SAVED)
                    // A build saved after its project was deleted is discarded: nothing is left to label. When the
                    // check fails, the labelling run is queued, and the planner cancels it if the project is gone.
                    .filterWhen(outcome -> store.projectExists(message.projectId()).onErrorReturn(true))
                    // The saved list's labelling pass is a run of its own: one place keeps it apart from a build.
                    .flatMap(outcome -> planner.queueOne(TopicsRunPlanner.Planned.builder()
                            .workspaceId(message.workspaceId()).projectId(message.projectId()).mode(RunMode.LABELS)
                            .build()))
                    .then();
            case LABELS -> label(message, workspace, deadline);
            case UPDATE -> Mono.error(
                    new IllegalStateException("no handler for run mode '%s'".formatted(message.mode())));
        });
    }

    /**
     * Goes on with the saved list's labelling pass until the deadline. A pass that finished or ran out of time ends
     * the run saved, and the next plan queues the rest while the labels are incomplete: a pass cut right after it
     * labelled everything is resumed too, and its resume marks them. A pass that failed ends it failed, and waits to
     * retry.
     */
    private Mono<Void> label(TopicsRunMessage message, TopicsMetrics.Workspace workspace, Instant deadline) {
        var passDeadline = deadline.minus(config.getRuns().getHeartbeatInterval().toJavaDuration());
        return labelService.labelWindow(workspace, message.projectId(), passDeadline, LabelTrigger.RESUME)
                .flatMap(outcome -> outcome == LabelPassOutcome.FAILED
                        ? fail(message, RunError.INTERNAL)
                        : store.endLabels(message.projectId(), message.runId()).then());
    }

    private Mono<Void> fail(TopicsRunMessage message, RunError code) {
        return store.write(dao -> dao.markFailed(message.workspaceId(), message.projectId(), message.runId(),
                Instant.now(), code)).then();
    }

    /** A heartbeat's answer, and when the last one that reached MySQL was written; null for one that failed. */
    private record Beat(boolean alive, Instant at) {
    }

    /**
     * Emits once a heartbeat finds the run cancelled, abandoned or requeued, or once the last heartbeat that reached
     * MySQL is older than {@code topics.runs.abandonGrace}: the planner records such a run abandoned and may queue the
     * project again. A run that saved its row goes on to its end. A heartbeat that fails is retried at the next tick.
     */
    private Mono<Beat> stopped(TopicsRunMessage message) {
        var interval = config.getRuns().getHeartbeatInterval().toJavaDuration();
        var abandonGrace = config.getRuns().getAbandonGrace().toJavaDuration();
        return Flux.interval(interval, interval)
                .onBackpressureDrop()
                .concatMap(tick -> store.heartbeat(message)
                        .map(alive -> new Beat(alive, Instant.now()))
                        .onErrorResume(error -> {
                            log.warn("Topics run '{}' heartbeat failed, project '{}'", message.runId(),
                                    message.projectId(), error);
                            return Mono.just(new Beat(true, null));
                        }))
                .scan(new Beat(true, Instant.now()),
                        (last, beat) -> beat.at() != null ? beat : new Beat(true, last.at()))
                .filter(beat -> !beat.alive() || beat.at().plus(abandonGrace).isBefore(Instant.now()))
                .next()
                .doOnNext(beat -> log.info("Topics run '{}' stops, project '{}': {}", message.runId(),
                        message.projectId(), beat.alive()
                                ? "no heartbeat reached MySQL within topics.runs.abandonGrace"
                                : "its row is no longer running"));
    }
}
