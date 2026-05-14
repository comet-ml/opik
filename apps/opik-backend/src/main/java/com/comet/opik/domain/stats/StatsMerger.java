package com.comet.opik.domain.stats;

import com.comet.opik.api.ProjectStats;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.stats.StatsMapper.ERROR_COUNT;
import static com.comet.opik.domain.stats.StatsMapper.GUARDRAILS_FAILED_COUNT;
import static com.comet.opik.domain.stats.StatsMapper.PAST_PERIOD_ERROR_COUNT;
import static com.comet.opik.domain.stats.StatsMapper.RECENT_ERROR_COUNT;
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
     * Single-project variant — splices the feedback stats into the trace+span stats at the
     * canonical position (just before guardrails / error-count entries) so the merged output
     * matches what the pre-split single-query mapper produced. Returns {@code base} unchanged
     * when there's nothing to attach.
     */
    public static ProjectStats merge(ProjectStats base, ProjectStats feedback) {
        if (base == null) {
            return null;
        }
        if (CollectionUtils.isEmpty(feedback.stats())) {
            return base;
        }

        var combined = new ArrayList<ProjectStats.ProjectStatItem<?>>(base.stats().size() + feedback.stats().size());
        boolean inserted = false;
        for (var stat : base.stats()) {
            if (!inserted && TRAILING_STATS.contains(stat.getName())) {
                combined.addAll(feedback.stats());
                inserted = true;
            }
            combined.add(stat);
        }
        if (!inserted) {
            combined.addAll(feedback.stats());
        }
        return new ProjectStats(combined);
    }

    private static final Set<String> TRAILING_STATS = Set.of(
            GUARDRAILS_FAILED_COUNT, RECENT_ERROR_COUNT, PAST_PERIOD_ERROR_COUNT, ERROR_COUNT);

}
