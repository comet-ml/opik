package com.comet.opik.domain.stats;

import com.comet.opik.api.ProjectStats;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import static java.util.stream.Collectors.toMap;

/**
 * Merges per-project trace+span stats (from SELECT_TRACES_SPANS_STATS) with per-project
 * feedback-score stats (from SELECT_FEEDBACK_SCORES_STATS).
 * <p>
 * Merge rule: the traces map is the source of truth for which projects appear in the output.
 * Feedback stats are enrichment; projects absent from the traces side are never resurrected
 * by feedback data, even if feedback rows exist for them. Trace-side filters define the
 * result scope.
 * <p>
 * This class only concatenates already-mapped stats — row-to-stats conversion lives in
 * {@link StatsMapper}.
 */
@UtilityClass
public class StatsMerger {

    public static Map<UUID, ProjectStats> merge(
            Map<UUID, ProjectStats> tracesSpansByProject,
            Map<UUID, ProjectStats> feedbackByProject) {

        if (MapUtils.isEmpty(tracesSpansByProject)) {
            return Map.of();
        }
        if (MapUtils.isEmpty(feedbackByProject)) {
            return tracesSpansByProject;
        }

        return tracesSpansByProject.entrySet().stream()
                .collect(toMap(
                        Map.Entry::getKey,
                        entry -> merge(entry.getValue(),
                                feedbackByProject.getOrDefault(entry.getKey(), ProjectStats.empty()))));
    }

    /**
     * Single-project variant — attaches the feedback stats to the trace+span stats by
     * concatenating the lists. Returns {@code base} unchanged when there's nothing to attach.
     */
    public static ProjectStats merge(ProjectStats base, ProjectStats feedback) {
        if (base == null) {
            return null;
        }
        if (CollectionUtils.isEmpty(feedback.stats())) {
            return base;
        }

        var combined = new ArrayList<>(base.stats());
        combined.addAll(feedback.stats());
        return new ProjectStats(combined);
    }

}
