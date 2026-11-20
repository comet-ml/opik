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
 * The first build's gate: a project's first list waits until the topic vectors backfill of that project has drained,
 * so the list is not built from the part of the window that has vectors so far. It reads only what the planner's read
 * of the project returns ({@link ListlessProject}): the backfill columns, which only the vectors tick writes, and the
 * last time the tick checked the workspace. A workspace is fresh when the tick checked it within
 * {@link TopicVectorBackfillService#staleAfter}: the tick still runs for it.
 *
 * <p>A project's state, from its backfill:
 * <ul>
 * <li>{@link State#OPEN}: the backfill is drained, and its workspace is fresh.</li>
 * <li>{@link State#TIMED_OUT_OPEN}: not so, but the backfill started more than
 * {@code topics.firstBuild.backfillMaxWait} ago and at least {@code timedOutShare} of its traces have a vector.</li>
 * <li>{@link State#TIMED_OUT_WAITING}: the backfill started more than {@code backfillMaxWait} ago and fewer have one,
 * or none is counted: a list is never built from clearly partial data, and an operator is told.</li>
 * <li>{@link State#WAITING}: any other row.</li>
 * </ul>
 * A project with no backfill is open when its workspace is fresh: the tick has seen the workspace, and starts a
 * backfill for every project it finds done statuses of, so it found none to backfill for this one. When the workspace
 * is not fresh, the tick has not seen it lately, and the project waits.
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

    /** Whether a first build of the project may start at {@code now}, from its backfill as read at {@code now}. */
    State firstBuildGate(@NonNull ListlessProject project, @NonNull Instant now) {
        var checkedAt = project.vectorsCheckedAt();
        boolean fresh = checkedAt != null
                && !checkedAt.isBefore(now.minus(TopicVectorBackfillService.staleAfter(config.getVectors())));
        if (project.backfillStartedAt() == null) {
            return fresh ? State.OPEN : State.WAITING;
        }
        if (project.backfillDrainedAt() != null && fresh) {
            return State.OPEN;
        }
        if (waited(project, now).compareTo(config.getFirstBuild().getBackfillMaxWait().toJavaDuration()) <= 0) {
            return State.WAITING;
        }
        return project.backfillTracesTotal() > 0 && project.backfillTracesDone() >= config.getFirstBuild()
                .getTimedOutShare() * project.backfillTracesTotal()
                        ? State.TIMED_OUT_OPEN
                        : State.TIMED_OUT_WAITING;
    }

    /** How long the project's backfill has run: zero for a project with none, which has no start. */
    static Duration waited(@NonNull ListlessProject project, @NonNull Instant now) {
        var started = project.backfillStartedAt();
        return started == null || started.isAfter(now) ? Duration.ZERO : Duration.between(started, now);
    }
}
