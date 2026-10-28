package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.List;

/**
 * Step d of the nightly update: the traces no prototype took are grouped when they number at least
 * {@value #MIN_CANDIDATE}: k = min({@value #MAX_GROUPS}, max(2, n / {@value #TRACES_PER_GROUP})), at most the unique
 * texts, seeded with the night's {@code rebuilds}. Per group the summary of 2a, except that the share is of the whole
 * window and the recent share of the whole window's last 7 days. A candidate is a group of at least
 * {@value #MIN_CANDIDATE} traces.
 */
@UtilityClass
public class CandidateGrouper {

    static final int MIN_CANDIDATE = 20;
    static final int MAX_GROUPS = 20;
    static final int TRACES_PER_GROUP = 40;

    /**
     * @param unassigned the window's traces without an owner, in window order
     */
    public static Grouping group(@NonNull NightWindow window, @NonNull int[] unassigned, long seed,
            @NonNull NightClusterer clusterer) {
        if (unassigned.length < MIN_CANDIDATE) {
            return Grouping.builder().unassigned(unassigned).labels(new int[0]).centres(new float[0])
                    .groups(List.of()).candidates(List.of()).build();
        }
        int d = window.dimensions();
        var texts = window.texts(unassigned);
        var vectors = window.rows(unassigned);
        var merged = IntentMerger.merge(texts, vectors, d);
        int k = Math.min(Math.min(MAX_GROUPS, Math.max(2, unassigned.length / TRACES_PER_GROUP)), merged.size());
        var result = clusterer.fit(NightClusterer.Request.builder()
                .kind(NightClusterer.Kind.UNASSIGNED)
                .points(merged)
                .k(k)
                .seed(seed)
                .build());
        var labels = new int[unassigned.length];
        for (int r = 0; r < labels.length; r++) {
            labels[r] = result.labels()[merged.inverse()[r]];
        }
        var windowRecent = window.recent();
        var recent = new boolean[unassigned.length];
        int recentTotal = 0;
        for (boolean r : windowRecent) {
            recentTotal += r ? 1 : 0;
        }
        for (int r = 0; r < unassigned.length; r++) {
            recent[r] = windowRecent[unassigned[r]];
        }
        var recentPerGroup = new int[k];
        for (int r = 0; r < labels.length; r++) {
            if (recent[r]) {
                recentPerGroup[labels[r]]++;
            }
        }
        int n = window.size();
        int recentDenominator = Math.max(1, recentTotal);
        var groups = GroupSummaries.summarize(texts, vectors, labels, result.centres(), recent, d).stream()
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
                .candidates(groups.stream().filter(group -> group.size() >= MIN_CANDIDATE).toList())
                .build();
    }

    /**
     * The grouping of the unassigned traces: per unassigned trace its group, the groups' unit centres
     * ({@code k * dimensions}), every group's summary and the candidates among them, in group order.
     */
    @Builder
    public record Grouping(@NonNull int[] unassigned, @NonNull int[] labels, @NonNull float[] centres,
            @NonNull List<GroupSummary> groups, @NonNull List<GroupSummary> candidates) {

        public float[] centre(int group, int dimensions) {
            var out = new float[dimensions];
            System.arraycopy(centres, group * dimensions, out, 0, dimensions);
            return out;
        }
    }
}
