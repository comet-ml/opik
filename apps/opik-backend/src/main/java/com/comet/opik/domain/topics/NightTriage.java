package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The night's triage of its due rows ({@link TopicProjectDAO#findNightDue}): the ones it skips without counting their
 * window, by reason, and the order in which it counts the rest.
 */
final class NightTriage {

    private NightTriage() {
    }

    /** A project the night counts and may queue. */
    @Builder(toBuilder = true)
    record NightCandidate(@NonNull String workspaceId, @NonNull UUID projectId, Instant windowUntil) {
    }

    /** The candidates in {@link #queueOrder}, and the skips that need no count. */
    record NightDue(@NonNull List<NightCandidate> candidates,
            @NonNull Map<NightSkipReason, List<TopicProjectKey>> skipped) {
    }

    /**
     * Sorts {@code rows} into skips and candidates. {@code existing} holds the projects that still exist: a deleted
     * project keeps its rows until the sweep. A project is paused when its last {@code pauseFailures} runs failed, the
     * latest within {@code pauseNights} nights before the night.
     */
    static NightDue triage(@NonNull Collection<TopicProject> rows, @NonNull Set<TopicProjectKey> existing,
            @NonNull TopicsNight night, int pauseFailures, int pauseNights) {
        var pausedSince = night.date().minusDays(pauseNights).atStartOfDay(ZoneOffset.UTC).toInstant();
        var skipped = new EnumMap<NightSkipReason, List<TopicProjectKey>>(NightSkipReason.class);
        var due = new ArrayList<NightCandidate>();
        for (var row : rows) {
            var key = new TopicProjectKey(row.workspaceId(), row.projectId());
            skipReason(row, existing.contains(key), night, pauseFailures, pausedSince).ifPresentOrElse(
                    reason -> skipped.computeIfAbsent(reason, __ -> new ArrayList<>()).add(key),
                    () -> due.add(NightCandidate.builder().workspaceId(row.workspaceId()).projectId(row.projectId())
                            .windowUntil(row.windowUntil()).build()));
        }
        due.sort(queueOrder());
        return new NightDue(due, skipped);
    }

    /** Why the night skips the project without counting its window; empty when it counts it. */
    private static Optional<NightSkipReason> skipReason(TopicProject row, boolean exists, TopicsNight night,
            int pauseFailures, Instant pausedSince) {
        if (!exists) {
            return Optional.of(NightSkipReason.DELETED);
        }
        if (covers(row, night.windowTo())) {
            return Optional.of(NightSkipReason.ALREADY_SAVED);
        }
        return paused(row, pauseFailures, pausedSince) ? Optional.of(NightSkipReason.PAUSED) : Optional.empty();
    }

    /**
     * Whether the project's last {@code failures} discovery runs or updates or more failed in a row, the latest at or
     * after {@code since}. A labelling pass changes neither. A run whose pod died counts once the planner's reconciler
     * records it {@code abandoned}, which each plan does before its night pass. With no {@code last_failed_at} the
     * project is not paused.
     */
    private static boolean paused(TopicProject row, int failures, Instant since) {
        return row.failedRuns() >= failures && row.lastFailedAt() != null && !row.lastFailedAt().isBefore(since);
    }

    /** Whether the saved list's window already ends at or after {@code windowTo}: the night would count it twice. */
    static boolean covers(TopicProject row, Instant windowTo) {
        return row.windowUntil() != null && !row.windowUntil().isBefore(windowTo);
    }

    /**
     * The candidates, sorted in {@link #queueOrder}, one workspace at a time in turn: each workspace's own stalest
     * first, and the workspaces in the order of their stalest. So one workspace with many due projects does not take
     * every free slot.
     */
    static List<NightCandidate> inTurn(@NonNull List<NightCandidate> sorted) {
        var byWorkspace = sorted.stream().collect(Collectors.groupingBy(NightCandidate::workspaceId,
                LinkedHashMap::new, Collectors.toList()));
        return TopicsDiscoveryCandidates.roundRobin(List.copyOf(byWorkspace.values()));
    }

    /**
     * The night's queue, stalest first: the oldest {@code window_until}, so a project a night did not reach goes first
     * the next night. A project with no {@code window_until} sorts first; among equals, by project id.
     */
    static Comparator<NightCandidate> queueOrder() {
        return Comparator.comparing(NightCandidate::windowUntil, Comparator.nullsFirst(Comparator.naturalOrder()))
                .thenComparing(NightCandidate::projectId);
    }
}
