package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The first build's gate: a project's first list waits until the topic vectors backfill of that project has drained,
 * so the list is not built from the part of the window that has vectors so far. Read from
 * {@code topic_vector_backfills}, which only the vectors tick writes: one MySQL read for all the workspaces asked, and
 * no ClickHouse read. Nothing is kept between reads.
 *
 * <p>A project's state, from its row:
 * <ul>
 * <li>{@link State#OPEN}: the row is drained, and checked within twice {@code topics.vectors.longWindowInterval}, so
 * the tick still runs for the workspace.</li>
 * <li>{@link State#TIMED_OUT_OPEN}: not so, but the backfill started more than
 * {@code topics.firstBuild.backfillMaxWait} ago and at least {@link #TIMED_OUT_SHARE} of its traces have a vector.</li>
 * <li>{@link State#TIMED_OUT_WAITING}: the backfill started more than {@code backfillMaxWait} ago and fewer have one,
 * or none is counted: a list is never built from clearly partial data, and an operator is told.</li>
 * <li>{@link State#WAITING}: any other row.</li>
 * </ul>
 * A project with no row is open when its workspace has a row checked within twice the interval: the tick has seen the
 * workspace, and gives a row to every project it finds done statuses of, so it found none to backfill for this one.
 * With no such row in the workspace, the tick has not seen it yet, and the project waits.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsBackfillGate {

    /** Past {@code backfillMaxWait}, the share of a backfill's traces with a vector that opens the gate. */
    static final double TIMED_OUT_SHARE = 0.99;

    private final @NonNull @Config("topics") TopicsConfig config;
    private final @NonNull TransactionTemplate template;

    /** A project's gate state; {@code value} is the outcome of {@code topics.first_build.gate}. */
    @Getter
    @RequiredArgsConstructor
    public enum State {
        OPEN("open", true),
        WAITING("waiting", false),
        TIMED_OUT_OPEN("timed_out_open", true),
        TIMED_OUT_WAITING("timed_out_waiting", false);

        private final String value;
        private final boolean open;
    }

    /** The backfill rows of some workspaces as read at {@code now}, by workspace and project. */
    public record Gate(@NonNull Instant now, @NonNull Instant freshSince, @NonNull Duration maxWait,
            @NonNull Map<String, Map<UUID, TopicVectorBackfill>> rows) {

        public State state(@NonNull String workspaceId, @NonNull UUID projectId) {
            var workspace = rows.getOrDefault(workspaceId, Map.of());
            var row = workspace.get(projectId);
            if (row == null) {
                return workspace.values().stream().anyMatch(this::fresh) ? State.OPEN : State.WAITING;
            }
            if (row.drainedAt() != null && fresh(row)) {
                return State.OPEN;
            }
            if (Duration.between(row.startedAt(), now).compareTo(maxWait) <= 0) {
                return State.WAITING;
            }
            return row.tracesTotal() > 0 && row.tracesDone() >= TIMED_OUT_SHARE * row.tracesTotal()
                    ? State.TIMED_OUT_OPEN
                    : State.TIMED_OUT_WAITING;
        }

        /** How long the project's backfill has run: zero for a project with no row, which has no start. */
        public Duration waited(@NonNull String workspaceId, @NonNull UUID projectId) {
            var row = rows.getOrDefault(workspaceId, Map.of()).get(projectId);
            return row == null || row.startedAt().isAfter(now) ? Duration.ZERO : Duration.between(row.startedAt(), now);
        }

        private boolean fresh(TopicVectorBackfill row) {
            return !row.checkedAt().isBefore(freshSince);
        }
    }

    /** Reads the rows of {@code workspaceIds}, once. */
    public Gate read(@NonNull Collection<String> workspaceIds, @NonNull Instant now) {
        Map<String, Map<UUID, TopicVectorBackfill>> rows = workspaceIds.isEmpty()
                ? Map.of()
                : template.inTransaction(READ_ONLY,
                        handle -> handle.attach(TopicVectorBackfillDAO.class).findByWorkspaceIds(workspaceIds))
                        .stream()
                        .collect(Collectors.groupingBy(TopicVectorBackfill::workspaceId,
                                Collectors.toMap(TopicVectorBackfill::projectId, Function.identity())));
        var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
        return new Gate(now, now.minus(interval.multipliedBy(2)),
                config.getFirstBuild().getBackfillMaxWait().toJavaDuration(), rows);
    }
}
