package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.CancelReason;
import com.comet.opik.domain.topics.TopicProject.RunMode;
import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Settles the runs in flight before the planner counts them: a run whose project was deleted or whose workspace opted
 * out is cancelled, a run past its deadline is cancelled, a run whose pod stopped writing its heartbeat is recorded
 * {@code abandoned} (it counts toward the retry wait: it can be the run's own out-of-memory), and a run queued long
 * ago is published again, as its stream entry may be lost.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsRunReconciler {

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TopicRunStore store;
    private final @NonNull TopicsRunPublisher publisher;
    private final @NonNull TopicsDiscoveryCandidates candidates;

    /** Returns the runs still in flight once settled. A run whose step fails stays in flight, for the next plan. */
    Mono<List<InFlightRun>> reconcile(@NonNull List<InFlightRun> runs, @NonNull Collection<String> enabledWorkspaces,
            @NonNull LocalDate night, @NonNull Instant now) {
        var enabled = Set.copyOf(enabledWorkspaces);
        return Flux.fromIterable(runs)
                .concatMap(run -> settle(run, enabled, night, now)
                        .onErrorResume(error -> {
                            log.warn("Topics run '{}' not reconciled, project '{}', status '{}'", run.runId(),
                                    run.projectId(), run.runStatus().getValue(), error);
                            return Mono.just(true);
                        })
                        .filter(inFlight -> inFlight)
                        .map(inFlight -> run))
                .collectList();
    }

    /** True while the run stays in flight. */
    private Mono<Boolean> settle(InFlightRun run, Set<String> enabled, LocalDate night, Instant now) {
        var runs = config.getRuns();
        var reason = leftReason(run, enabled);
        var stale = now.minus(runs.getAbandonGrace().toJavaDuration());
        return switch (run.runStatus()) {
            case QUEUED -> reason.isPresent()
                    ? store.dropQueued(run.workspaceId(), run.projectId(), run.runId(), reason.get())
                            .map(dropped -> !dropped)
                    : republishIfStale(run, night, now);
            case RUNNING -> {
                if (run.heartbeatAt() == null || run.heartbeatAt().isBefore(stale)) {
                    yield store.abandon(run, stale).map(abandoned -> !abandoned);
                }
                var cancel = reason.or(() -> run.deadlineAt() != null && run.deadlineAt().isBefore(now)
                        ? Optional.of(CancelReason.DEADLINE)
                        : Optional.empty());
                if (cancel.isEmpty()) {
                    yield Mono.just(true);
                }
                log.info("Topics run '{}' cancelled, project '{}', reason '{}'", run.runId(), run.projectId(),
                        cancel.get().getValue());
                yield store.write(dao -> dao.cancel(run.workspaceId(), run.projectId(), run.runId(), cancel.get()))
                        .thenReturn(true);
            }
            // Its pod died before it ended the run: end it as that pod would have.
            case CANCELLING -> run.heartbeatAt() == null || run.heartbeatAt().isBefore(stale)
                    ? store.endCancelled(run.workspaceId(), run.projectId(), run.runId()).map(Optional::isEmpty)
                    : Mono.just(true);
            case SAVED, FAILED -> Mono.just(false);
        };
    }

    private static Optional<CancelReason> leftReason(InFlightRun run, Set<String> enabled) {
        if (run.projectDeleted()) {
            return Optional.of(CancelReason.PROJECT_DELETED);
        }
        return enabled.contains(run.workspaceId()) ? Optional.empty() : Optional.of(CancelReason.WORKSPACE_DISABLED);
    }

    /**
     * Publishes a run queued before {@code topics.runs.republishAfter} again. A discovery run is counted again for
     * {@code night}, as the stream entry carries the window's count.
     */
    private Mono<Boolean> republishIfStale(InFlightRun run, LocalDate night, Instant now) {
        var queuedBefore = now.minus(config.getRuns().getRepublishAfter().toJavaDuration());
        if (run.runQueuedAt() != null && !run.runQueuedAt().isBefore(queuedBefore)) {
            return Mono.just(true);
        }
        var message = TopicsRunMessage.builder().runId(run.runId()).workspaceId(run.workspaceId())
                .projectId(run.projectId()).mode(run.runMode());
        var ready = run.runMode() == RunMode.DISCOVERY
                ? candidates.windowVectors(run.workspaceId(), run.projectId(), night)
                        .map(vectors -> message.night(night).windowTraces(vectors).build())
                : Mono.just(message.build());
        return ready.flatMap(built -> store.write(dao -> dao.republish(run.workspaceId(), run.projectId(),
                run.runId(), queuedBefore, now))
                .flatMap(marked -> {
                    if (!marked) {
                        return Mono.just(true);
                    }
                    log.info("Topics run '{}' published again, project '{}'", run.runId(), run.projectId());
                    return publisher.publish(built).thenReturn(true);
                }));
    }
}
