package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Step b of the nightly update: the traces no prototype took are grouped when they number at least
 * {@code nightlyMinCandidate}: k = unique texts / {@code nightlyTextsPerGroup} within {@code nightlyMinGroups} and
 * {@code nightlyMaxGroups} (the discovery run's rule), seeded with the night's {@code rebuilds}. Per group a
 * {@link NightGroup}: size, tightness and examples, with the share of the whole window and the share of the window's
 * last {@code recentDays} days. A candidate is a group of at least {@code nightlyMinCandidate} traces.
 */
@UtilityClass
class CandidateGrouper {

    /**
     * @param unassigned the window's traces without an owner, in window order
     */
    static Grouping group(@NonNull NightWindow window, @NonNull int[] unassigned, long seed,
            @NonNull AlgorithmConfig config) {
        if (unassigned.length < config.getNightlyMinCandidate()) {
            return Grouping.builder().unassigned(unassigned).labels(new int[0]).centres(new float[0])
                    .groups(List.of()).candidates(List.of()).build();
        }
        int d = window.dimensions();
        var merged = IntentMerger.merge(window.texts(unassigned), window.rows(unassigned), d);
        int k = KMeans.groupCount(merged.size(), config.getNightlyTextsPerGroup(), config.getNightlyMinGroups(),
                config.getNightlyMaxGroups(), merged.size());
        var weights = Arrays.stream(merged.weights()).asDoubleStream().toArray();
        var result = KMeans.fit(merged.vectors(), weights, d, k, seed, config.getKmeansMaxIterations(),
                config.getKmeansRestarts());
        var labels = new int[unassigned.length];
        for (int r = 0; r < labels.length; r++) {
            labels[r] = result.labels()[merged.textOfTrace()[r]];
        }
        var windowRecent = window.recent(config.getRecentDays());
        int recentTotal = 0;
        for (boolean recent : windowRecent) {
            recentTotal += recent ? 1 : 0;
        }
        var recentPerGroup = new int[k];
        for (int r = 0; r < unassigned.length; r++) {
            if (windowRecent[unassigned[r]]) {
                recentPerGroup[labels[r]]++;
            }
        }
        int n = window.size();
        int recentDenominator = Math.max(1, recentTotal);
        var groups = GroupSummaries.summarize(merged, result.labels(), result.centres(), config.getNightlyFrequent(),
                config.getNightlyExamples()).stream()
                .map(group -> group.toBuilder()
                        .share((double) group.size() / n)
                        .recentShare((double) recentPerGroup[group.group()] / recentDenominator)
                        .build())
                .toList();
        return Grouping.builder()
                .unassigned(unassigned)
                .labels(labels)
                .centres(result.centres())
                .groups(groups)
                .candidates(groups.stream().filter(group -> group.size() >= config.getNightlyMinCandidate()).toList())
                .build();
    }

    /**
     * The grouping of the unassigned traces: per unassigned trace its group, the groups' unit centres
     * ({@code k * dimensions}), every group's summary and the candidates among them, in group order. The arrays are
     * not copied (callers read them inside per-trace loops): callers must not change them.
     */
    @Builder(toBuilder = true)
    record Grouping(@NonNull int[] unassigned, @NonNull int[] labels, @NonNull float[] centres,
            @NonNull List<NightGroup> groups, @NonNull List<NightGroup> candidates) {

        Grouping {
            groups = List.copyOf(groups);
            candidates = List.copyOf(candidates);
        }

        float[] centre(int group, int dimensions) {
            return Arrays.copyOfRange(centres, group * dimensions, (group + 1) * dimensions);
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Grouping other
                    && Arrays.equals(unassigned, other.unassigned)
                    && Arrays.equals(labels, other.labels)
                    && Arrays.equals(centres, other.centres)
                    && groups.equals(other.groups)
                    && candidates.equals(other.candidates);
        }

        @Override
        public int hashCode() {
            return Objects.hash(Arrays.hashCode(unassigned), Arrays.hashCode(labels), Arrays.hashCode(centres),
                    groups, candidates);
        }

        @Override
        public String toString() {
            return "Grouping[unassigned=%s, groups=%s, candidates=%s]".formatted(unassigned.length, groups.size(),
                    candidates.size());
        }
    }
}
