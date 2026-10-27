package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Per k-means group, what the author prompt shows: size, share of the window, share of the last 7 days, tightness (mean
 * cosine of the members to the centre) and up to {@value #EXAMPLES} examples: the {@value #FREQUENT} most frequent texts
 * (ties by code point order), then the texts nearest to the centre not already listed (ties by trace order). As the
 * stand's {@code summarize_clusters}, with stable tie-breaks.
 */
@UtilityClass
public class GroupSummaries {

    static final int EXAMPLES = 5;
    static final int FREQUENT = Math.max(2, EXAMPLES - 2);

    /**
     * @param texts   per trace
     * @param vectors per trace, unit length, {@code texts.length * dimensions}
     * @param labels  per trace, the group in {@code [0, k)}
     * @param centres {@code k * dimensions}, unit length
     * @param recent  per trace, whether it falls in the window's last 7 days
     */
    public static List<GroupSummary> summarize(@NonNull String[] texts, @NonNull float[] vectors,
            @NonNull int[] labels, @NonNull float[] centres, @NonNull boolean[] recent, int dimensions) {
        int n = texts.length;
        int k = centres.length / dimensions;
        Preconditions.checkArgument(labels.length == n && recent.length == n && vectors.length == n * dimensions,
                "per-trace arrays must have one entry per text, texts '%s'", n);
        var members = new ArrayList<List<Integer>>(k);
        for (int j = 0; j < k; j++) {
            members.add(new ArrayList<>());
        }
        int recentTotal = 0;
        for (int i = 0; i < n; i++) {
            members.get(labels[i]).add(i);
            if (recent[i]) {
                recentTotal++;
            }
        }
        var out = new ArrayList<GroupSummary>(k);
        for (int j = 0; j < k; j++) {
            var idx = members.get(j);
            if (idx.isEmpty()) {
                out.add(GroupSummary.builder().group(j).examples(List.of()).build());
                continue;
            }
            var sims = new double[n];
            double simSum = 0;
            int recentMembers = 0;
            var counts = new TreeMap<String, Integer>(TopicText.CODE_POINT_ORDER);
            for (int i : idx) {
                sims[i] = dot(vectors, i, centres, j, dimensions);
                simSum += sims[i];
                if (recent[i]) {
                    recentMembers++;
                }
                counts.merge(texts[i], 1, Integer::sum);
            }
            var byCount = new ArrayList<>(counts.entrySet());
            byCount.sort(Comparator.comparingInt(entry -> -entry.getValue()));
            var examples = new ArrayList<String>(EXAMPLES);
            for (int e = 0; e < Math.min(FREQUENT, byCount.size()); e++) {
                examples.add(byCount.get(e).getKey());
            }
            var bySim = new ArrayList<>(idx);
            bySim.sort((a, b) -> sims[a] > sims[b] ? -1 : sims[a] < sims[b] ? 1 : 0);
            for (int i : bySim) {
                if (!examples.contains(texts[i])) {
                    examples.add(texts[i]);
                }
                if (examples.size() >= EXAMPLES) {
                    break;
                }
            }
            out.add(GroupSummary.builder()
                    .group(j)
                    .size(idx.size())
                    .share((double) idx.size() / n)
                    .recentShare((double) recentMembers / Math.max(1, recentTotal))
                    .tightness(simSum / idx.size())
                    .examples(List.copyOf(examples))
                    .uniqueTexts(counts.size())
                    .build());
        }
        return out;
    }

    static double dot(float[] a, int i, float[] b, int j, int dimensions) {
        double s = 0;
        int ai = i * dimensions;
        int bj = j * dimensions;
        for (int t = 0; t < dimensions; t++) {
            s += (double) a[ai + t] * b[bj + t];
        }
        return s;
    }

    @Builder(toBuilder = true)
    public record GroupSummary(int group, int size, double share, double recentShare, double tightness,
            @NonNull List<String> examples, int uniqueTexts) {
    }
}
