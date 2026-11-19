package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.Builder;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;

/**
 * The first build's gate: a project's first list waits until the topic vectors backfill of that project has drained,
 * so the list is not built from the part of the window that has vectors so far. Read from the {@code backfill_*}
 * columns of {@code topic_projects} and {@code topic_workspaces.vectors_checked_at}, which only the vectors tick writes:
 * two MySQL reads for all the workspaces asked, and no ClickHouse read. Nothing is kept between reads. A workspace is
 * fresh when the tick checked it within twice {@code topics.vectors.longWindowInterval}: the tick still runs for it.
 *
 * <p>A project's state, from its backfill:
 * <ul>
 * <li>{@link State#OPEN}: the backfill is drained, and its workspace is fresh.</li>
 * <li>{@link State#TIMED_OUT_OPEN}: not so, but the backfill started more than
 * {@code topics.firstBuild.backfillMaxWait} ago and at least {@link #TIMED_OUT_SHARE} of its traces have a vector.</li>
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

    /**
     * The backfills of some workspaces as read at {@code now}, by workspace and project, and the time the tick last
     * checked each workspace.
     */
    @Builder(toBuilder = true)
    public record Gate(@NonNull Instant now, @NonNull Instant freshSince, @NonNull Duration maxWait,
            @NonNull Map<String, Map<UUID, TopicVectorBackfillDAO.Backfill>> backfills,
            @NonNull Map<String, Instant> checkedAt) {

        public State state(@NonNull String workspaceId, @NonNull UUID projectId) {
            boolean fresh = Optional.ofNullable(checkedAt.get(workspaceId)).filter(at -> !at.isBefore(freshSince))
                    .isPresent();
            var backfill = backfills.getOrDefault(workspaceId, Map.of()).get(projectId);
            if (backfill == null) {
                return fresh ? State.OPEN : State.WAITING;
            }
            if (backfill.backfillDrainedAt() != null && fresh) {
                return State.OPEN;
            }
            if (Duration.between(backfill.backfillStartedAt(), now).compareTo(maxWait) <= 0) {
                return State.WAITING;
            }
            return backfill.backfillTracesTotal() > 0
                    && backfill.backfillTracesDone() >= TIMED_OUT_SHARE * backfill.backfillTracesTotal()
                            ? State.TIMED_OUT_OPEN
                            : State.TIMED_OUT_WAITING;
        }

        /** How long the project's backfill has run: zero for a project with none, which has no start. */
        public Duration waited(@NonNull String workspaceId, @NonNull UUID projectId) {
            var backfill = backfills.getOrDefault(workspaceId, Map.of()).get(projectId);
            return backfill == null || backfill.backfillStartedAt().isAfter(now)
                    ? Duration.ZERO
                    : Duration.between(backfill.backfillStartedAt(), now);
        }
    }

    public Gate read(@NonNull Collection<String> workspaceIds, @NonNull Instant now) {
        var interval = config.getVectors().getLongWindowInterval().toJavaDuration();
        var gate = Gate.builder()
                .now(now)
                .freshSince(now.minus(interval.multipliedBy(2)))
                .maxWait(config.getFirstBuild().getBackfillMaxWait().toJavaDuration());
        if (workspaceIds.isEmpty()) {
            return gate.backfills(Map.of()).checkedAt(Map.of()).build();
        }
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(TopicVectorBackfillDAO.class);
            return gate
                    .backfills(dao.findByWorkspaceIds(workspaceIds).stream()
                            .collect(Collectors.groupingBy(TopicVectorBackfillDAO.Backfill::workspaceId,
                                    Collectors.toMap(TopicVectorBackfillDAO.Backfill::projectId,
                                            Function.identity()))))
                    .checkedAt(dao.findWorkspaces(workspaceIds).stream()
                            .collect(Collectors.toMap(TopicWorkspace::workspaceId,
                                    TopicWorkspace::vectorsCheckedAt)))
                    .build();
        });
    }
}
