package com.comet.opik.domain.topics;

import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.List;

/**
 * Finds topics that cover the same traces. A topic's centre is the normalised mean of its member vectors. Two topics
 * whose centres have a cosine of at least {@code mergeCosine} are one theme split in two: their prototypes interleave,
 * so each trace goes to whichever happens to be nearer and the split carries no meaning. The smaller one is folded
 * into the larger (more members; a tie keeps the lower index), the pair with the highest cosine first. The folded
 * topic's members count for the one that took it, so a chain of three merges into one. A topic with no member has no
 * centre and is never merged.
 */
@UtilityClass
class TopicMerger {

    /** One merge: {@code absorbed} is folded into {@code kept}. */
    record Merge(int kept, int absorbed, double cosine) {
    }

    /**
     * @param vectors     per trace, {@code dimensions} values
     * @param labels      per trace, a topic index or {@link TopicAlgorithm#OTHER}
     * @param topicCount  the number of topic indices
     * @param candidates  the topic indices that may merge (the live ones)
     * @param mergeCosine {@code topics.algorithm.mergeCosine}; above 1 merges nothing
     * @return the merges, in the order made
     */
    static List<Merge> merges(@NonNull float[] vectors, @NonNull int[] labels, int topicCount,
            @NonNull int[] candidates, int dimensions, double mergeCosine) {
        var out = new ArrayList<Merge>();
        if (mergeCosine > 1 || candidates.length < 2) {
            return out;
        }
        var sums = new double[topicCount][];
        var counts = new long[topicCount];
        var open = new boolean[topicCount];
        for (int t : candidates) {
            sums[t] = new double[dimensions];
            open[t] = true;
        }
        for (int i = 0; i < labels.length; i++) {
            int t = labels[i];
            if (t >= 0 && t < topicCount && open[t]) {
                counts[t]++;
                var sum = sums[t];
                for (int j = 0; j < dimensions; j++) {
                    sum[j] += vectors[i * dimensions + j];
                }
            }
        }
        for (int t : candidates) {
            open[t] = counts[t] > 0;
        }
        while (true) {
            int bestA = -1;
            int bestB = -1;
            double best = Double.NEGATIVE_INFINITY;
            for (int x = 0; x < candidates.length; x++) {
                int a = candidates[x];
                if (!open[a]) {
                    continue;
                }
                for (int y = x + 1; y < candidates.length; y++) {
                    int b = candidates[y];
                    if (!open[b]) {
                        continue;
                    }
                    double cosine = cosine(sums[a], sums[b]);
                    if (cosine > best) {
                        best = cosine;
                        bestA = a;
                        bestB = b;
                    }
                }
            }
            if (bestA < 0 || best < mergeCosine) {
                return out;
            }
            boolean aKeeps = counts[bestA] >= counts[bestB];
            int kept = aKeeps ? bestA : bestB;
            int absorbed = aKeeps ? bestB : bestA;
            out.add(new Merge(kept, absorbed, best));
            for (int j = 0; j < dimensions; j++) {
                sums[kept][j] += sums[absorbed][j];
            }
            counts[kept] += counts[absorbed];
            open[absorbed] = false;
        }
    }

    private static double cosine(double[] a, double[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int j = 0; j < a.length; j++) {
            dot += a[j] * b[j];
            na += a[j] * a[j];
            nb += b[j] * b[j];
        }
        return na == 0 || nb == 0 ? 0 : dot / Math.sqrt(na * nb);
    }
}
