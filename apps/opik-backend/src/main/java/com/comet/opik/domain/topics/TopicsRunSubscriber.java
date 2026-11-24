package com.comet.opik.domain.topics;

import com.comet.opik.api.resources.v1.events.BaseRedisSubscriber;
import com.comet.opik.domain.topics.TopicProject.RunError;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
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
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

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
    private static final Duration REQUEUE_WAIT = Duration.ofSeconds(5);

    private final TopicsConfig config;
    private final TopicRunStore store;
    private final TopicsRebuildService rebuildService;
    private final TopicWindowLabelService labelService;
    private final TopicsRunPlanner planner;
    private final Map<UUID, TopicsRunMessage> running = new ConcurrentHashMap<>();

    @Inject
    TopicsRunSubscriber(@NonNull @Config("topics") TopicsConfig config, @NonNull RedissonReactiveClient redisson,
            @NonNull TopicRunStore store, @NonNull TopicsRebuildService rebuildService,
            @NonNull TopicWindowLabelService labelService, @NonNull TopicsRunPlanner planner) {
        super(config.getRuns(), redisson, TopicsConfig.RunsConfig.PAYLOAD_FIELD, METRICS_NAMESPACE,
                METRICS_BASE_NAME);
        this.config = config;
        this.store = store;
        this.rebuildService = rebuildService;
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
        if (!config.isEnabled()) {
            return;
        }
        // Before the base stop cancels the runs: a cancelled run's own failure write then finds the row queued.
        running.values().forEach(message -> {
            try {
                log.info("Topics run '{}' requeued '{}', project '{}', its pod stops", message.runId(),
                        store.requeue(message).block(REQUEUE_WAIT), message.projectId());
            } catch (RuntimeException exception) {
                log.warn("Topics run '{}' not requeued, project '{}'; the planner records it abandoned",
                        message.runId(), message.projectId(), exception);
            }
        });
        super.stop();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull TopicsRunMessage message) {
        var limit = runLimit(message.mode());
        var deadline = Instant.now().plus(limit);
        return claim(message, deadline)
                .flatMap(claimed -> {
                    if (!claimed) {
                        log.info("Topics run '{}' not claimed, project '{}': another pod has it, or it ended",
                                message.runId(), message.projectId());
                        return Mono.empty();
                    }
                    log.info("Topics run '{}' claimed, workspace '{}', project '{}', mode '{}'", message.runId(),
                            message.workspaceId(), message.projectId(), message.mode().getValue());
                    running.put(message.runId(), message);
                    return run(message, deadline)
                            .takeUntilOther(stopped(message))
                            // A backstop: the run ends itself by its deadline, and the planner cancels it after.
                            .timeout(limit.plus(config.getRuns().getAbandonGrace().toJavaDuration()),
                                    Mono.defer(() -> fail(message, RunError.TIMEOUT)))
                            .then(Mono.defer(() -> store.endCancelled(message.workspaceId(), message.projectId(),
                                    message.runId())))
                            .doOnNext(code -> code.ifPresent(value -> log.info(
                                    "Topics run '{}' stopped, project '{}', code '{}'", message.runId(),
                                    message.projectId(), value.getValue())))
                            .doFinally(signal -> running.remove(message.runId()));
                })
                .onErrorResume(error -> {
                    log.error("Topics run '{}' ended with an error, project '{}'; the planner settles its row",
                            message.runId(), message.projectId(), error);
                    return Mono.empty();
                })
                .then();
    }

    /** How long a run of {@code mode} may last: its deadline is the claim plus this. */
    private Duration runLimit(RunMode mode) {
        var build = config.getBuild();
        var timeout = build.getProjectTimeout().toJavaDuration();
        return mode == RunMode.LABELS ? timeout : timeout.plus(TopicRunStore.insertBound(build));
    }

    /** A nightly update's claim also re-checks that its night is still to do (see {@link TopicRunStore#claimNightly}). */
    private Mono<Boolean> claim(TopicsRunMessage message, Instant deadline) {
        return message.mode() == RunMode.NIGHTLY_UPDATE
                ? store.claimNightly(message, deadline).map(claim -> claim == TopicRunStore.NightClaim.CLAIMED)
                : store.claim(message, deadline);
    }

    private Mono<Void> run(TopicsRunMessage message, Instant deadline) {
        return switch (message.mode()) {
            case FIRST_BUILD -> labelsAfter(message, rebuildService.firstBuild(message.runId(),
                    message.workspaceId(), message.projectId(), message.night(), message.windowTraces()));
            case NIGHTLY_UPDATE -> labelsAfter(message, rebuildService.nightlyUpdate(message.runId(),
                    message.workspaceId(), message.projectId(), message.night(), message.windowTraces()));
            case LABELS -> label(message, deadline);
        };
    }

    /** The saved list's labelling pass is a run of its own: one place keeps it apart from a build. */
    Mono<Void> labelsAfter(TopicsRunMessage message, Mono<TopicsRebuildService.Outcome> build) {
        return build.filter(outcome -> outcome.status() == TopicProject.RunStatus.SAVED)
                // A list saved after its project was deleted is discarded: nothing is left to label. When the check
                // fails, the labelling run is queued, and the planner cancels it if the project is gone.
                .filterWhen(outcome -> store.projectExists(message.workspaceId(), message.projectId())
                        .onErrorReturn(true))
                .flatMap(outcome -> planner.queueOne(TopicsRunPlanner.Planned.builder()
                        .workspaceId(message.workspaceId()).projectId(message.projectId()).mode(RunMode.LABELS)
                        .build()))
                .then();
    }

    /**
     * Goes on with the saved list's labelling pass until the deadline. A pass that finished or ran out of time ends
     * the run saved, and the next plan queues the rest; a pass that failed ends it failed, and waits to retry.
     */
    private Mono<Void> label(TopicsRunMessage message, Instant deadline) {
        var passDeadline = deadline.minus(config.getRuns().getHeartbeatInterval().toJavaDuration());
        return labelService.labelWindow(message.workspaceId(), message.projectId(), passDeadline,
                TopicWindowLabelService.Trigger.RESUME)
                .flatMap(outcome -> outcome == TopicWindowLabelService.PassOutcome.FAILED
                        ? fail(message, RunError.INTERNAL)
                        : store.endLabels(message).then());
    }

    private Mono<Void> fail(TopicsRunMessage message, RunError code) {
        return store.write(dao -> dao.markFailed(message.workspaceId(), message.projectId(), message.runId(),
                Instant.now(), code)).then();
    }

    /**
     * Emits once a heartbeat finds the run cancelled, abandoned or requeued; a run that saved its row goes on to its
     * end. A heartbeat that fails is retried at the next tick.
     */
    private Mono<Boolean> stopped(TopicsRunMessage message) {
        var interval = config.getRuns().getHeartbeatInterval().toJavaDuration();
        return Flux.interval(interval, interval)
                .onBackpressureDrop()
                .concatMap(tick -> store.heartbeat(message).onErrorResume(error -> {
                    log.warn("Topics run '{}' heartbeat failed, project '{}'", message.runId(), message.projectId(),
                            error);
                    return Mono.just(true);
                }))
                .filter(alive -> !alive)
                .next()
                .doOnNext(stop -> log.info("Topics run '{}' stops, project '{}': its row is no longer running",
                        message.runId(), message.projectId()));
    }
}
