package com.comet.opik.domain.stats;

import com.comet.opik.api.ProjectStats;
import lombok.experimental.UtilityClass;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.domain.stats.StatsMapper.ERROR_COUNT;
import static com.comet.opik.domain.stats.StatsMapper.GUARDRAILS_FAILED_COUNT;
import static com.comet.opik.domain.stats.StatsMapper.PAST_PERIOD_ERROR_COUNT;
import static com.comet.opik.domain.stats.StatsMapper.RECENT_ERROR_COUNT;
import static java.util.stream.Collectors.toMap;

// Merges trace+span stats (split-A) with feedback-score stats (split-B). The trace side
// defines result scope: projects absent from it are not resurrected by feedback rows.
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

    // Splices feedback into base at the canonical slot (before guardrails / error-count) so the
    // merged list matches the pre-split mapper's order.
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

    // When aggregates is empty, return empty — feedback alone is never surfaced (matches the
    // traces-driven scope of the map overload).
    public static Mono<ProjectStats> zipAndMerge(Mono<ProjectStats> aggregates, Mono<ProjectStats> feedback) {
        return aggregates
                .flatMap(agg -> feedback.switchIfEmpty(Mono.fromSupplier(ProjectStats::empty))
                        .map(fb -> merge(agg, fb)))
                .switchIfEmpty(Mono.fromSupplier(ProjectStats::empty));
    }

}
