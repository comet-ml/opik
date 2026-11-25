package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

import java.time.Duration;
import java.time.Instant;

/**
 * The discovery run's gate: a project's first list waits until the topic vectors backfill of that project has drained,
 * so the list is not built from the part of the window that has vectors so far. It reads only what the planner's read
 * of the project returns ({@link ListlessProject}): the backfill columns, which only the vectors tick writes, and the
 * last time the tick checked the workspace. A workspace is fresh when the tick checked it within
 * {@link TopicVectorBackfillService#staleAfter}: the tick still runs for it.
 *
 * <p>A project's state:
 * <ul>
 * <li>{@link State#WAITING}: the tick never checked the workspace (it was just opted in), or the backfill has run for
 * at most {@code topics.discovery.backfillMaxWait}.</li>
 * <li>{@link State#OPEN}: the workspace is fresh, and the project has no backfill or its backfill is drained. A
 * project with no backfill has nothing to wait for: the tick starts a backfill for every project it finds done
 * statuses of.</li>
 * <li>{@link State#TIMED_OUT_OPEN}: the workspace is fresh, the backfill started more than {@code backfillMaxWait}
 * ago, and at least {@code timedOutShare} of its traces have a vector.</li>
 * <li>{@link State#TIMED_OUT_WAITING}: the workspace is stale, whatever the backfill: the tick stopped checking it,
 * so its newest traces may have no vector. Or the backfill started more than {@code backfillMaxWait} ago and fewer
 * have one, or none is counted. A list is never built from clearly partial data, and an operator is told.</li>
 * </ul>
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TopicsBackfillGate {

    private final @NonNull @Config("topics") TopicsConfig config;

    @Getter
    @RequiredArgsConstructor
    enum State {
        OPEN(true),
        WAITING(false),
        TIMED_OUT_OPEN(true),
        TIMED_OUT_WAITING(false);

        private final boolean open;
    }

    /** Whether a discovery run of the project may start at {@code now}, from its backfill as read at {@code now}. */
    State discoveryGate(@NonNull ListlessProject project, @NonNull Instant now) {
        var checkedAt = project.vectorsCheckedAt();
        if (checkedAt == null) {
            return State.WAITING;
        }
        if (checkedAt.isBefore(now.minus(TopicVectorBackfillService.staleAfter(config.getVectors())))) {
            return State.TIMED_OUT_WAITING;
        }
        if (project.backfillStartedAt() == null || project.backfillDrainedAt() != null) {
            return State.OPEN;
        }
        if (waited(project, now).compareTo(config.getDiscovery().getBackfillMaxWait().toJavaDuration()) <= 0) {
            return State.WAITING;
        }
        return project.backfillTracesTotal() > 0 && project.backfillTracesDone() >= config.getDiscovery()
                .getTimedOutShare() * project.backfillTracesTotal()
                        ? State.TIMED_OUT_OPEN
                        : State.TIMED_OUT_WAITING;
    }

    /** How long the project has waited: since its backfill started, or since its workspace's last check without one. */
    static Duration waited(@NonNull ListlessProject project, @NonNull Instant now) {
        var started = project.backfillStartedAt() != null ? project.backfillStartedAt() : project.vectorsCheckedAt();
        return started == null || started.isAfter(now) ? Duration.ZERO : Duration.between(started, now);
    }
}
