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
 * Per k-means group, what a prompt shows. For the author prompt (the first build), its size and share of the window
 * ({@link #summarize(int[], int)}); the examples come from {@link #authorExamples}. For the nightly naming and verify
 * prompts, also its tightness (mean cosine of the members to the centre) and up to {@value #EXAMPLES} examples: up to
 * {@value #FREQUENT} repeated texts, then the most typical ones ({@link #summarize(String[], float[], int[], float[],
 * int)}). The recent share stays 0: {@link CandidateGrouper} sets both shares itself, over the whole window.
 */
@UtilityClass
public class GroupSummaries {

    /** A text takes a "repeated" example slot only when it occurs at least this often in its group. */
    static final int MIN_REPEATS = 2;
    static final int EXAMPLES = 5;
    static final int FREQUENT = Math.max(2, EXAMPLES - 2);

    /**
     * Sizes and shares only: what the author prompt reads.
     *
     * @param labels per trace, the group in {@code [0, k)}
     * @param k      the number of groups
     */
    public static List<GroupSummary> summarize(@NonNull int[] labels, int k) {
        int n = labels.length;
        var sizes = new int[k];
        for (int label : labels) {
            sizes[label]++;
        }
        var out = new ArrayList<GroupSummary>(k);
        for (int j = 0; j < k; j++) {
            out.add(GroupSummary.builder()
                    .group(j)
                    .size(sizes[j])
                    .share(sizes[j] == 0 ? 0 : (double) sizes[j] / n)
                    .examples(List.of())
                    .build());
        }
        return out;
    }

    /**
     * Sizes, shares, tightness and examples: what the nightly naming and verify prompts read.
     *
     * @param texts   per trace
     * @param vectors per trace, unit length, {@code texts.length * dimensions}
     * @param labels  per trace, the group in {@code [0, k)}
     * @param centres {@code k * dimensions}, unit length
     */
    public static List<GroupSummary> summarize(@NonNull String[] texts, @NonNull float[] vectors,
            @NonNull int[] labels, @NonNull float[] centres, int dimensions) {
        int n = texts.length;
        int k = centres.length / dimensions;
        Preconditions.checkArgument(labels.length == n && vectors.length == n * dimensions,
                "per-trace arrays must have one entry per text, texts '%s'", n);
        var members = members(labels, k);
        var out = new ArrayList<GroupSummary>(k);
        // One array for all groups: a group's sort reads only its own members' scores, which it writes first.
        var sims = new double[n];
        for (int j = 0; j < k; j++) {
            var idx = members.get(j);
            if (idx.isEmpty()) {
                out.add(GroupSummary.builder().group(j).examples(List.of()).build());
                continue;
            }
            double simSum = 0;
            for (int i : idx) {
                sims[i] = dot(vectors, i, centres, j, dimensions);
                simSum += sims[i];
            }
            out.add(GroupSummary.builder()
                    .group(j)
                    .size(idx.size())
                    .share((double) idx.size() / n)
                    .tightness(simSum / idx.size())
                    .examples(pick(texts, idx, sims, FREQUENT, EXAMPLES))
                    .build());
        }
        return out;
    }

    /**
     * The author's examples per group, {@code frequent + extra} in all (fewer when the group has fewer distinct
     * texts): up to {@code frequent} texts that repeat, most frequent first (ties by code point order), then the texts
     * nearest to the centre not already listed (ties by trace order). A group where no text repeats gets only the
     * nearest texts. Empty for an empty group.
     */
    public static List<List<String>> authorExamples(@NonNull String[] texts, @NonNull float[] vectors,
            @NonNull int[] labels, @NonNull float[] centres, int dimensions, int frequent, int extra) {
        int n = texts.length;
        int k = centres.length / dimensions;
        var members = members(labels, k);
        var out = new ArrayList<List<String>>(k);
        // One array for all groups: a group's sort reads only its own members' scores, which it writes first.
        var sims = new double[n];
        for (int j = 0; j < k; j++) {
            var idx = members.get(j);
            if (idx.isEmpty()) {
                out.add(List.of());
                continue;
            }
            for (int i : idx) {
                sims[i] = dot(vectors, i, centres, j, dimensions);
            }
            out.add(pick(texts, idx, sims, frequent, frequent + extra));
        }
        return out;
    }

    /** Per group in {@code [0, k)}, its traces in trace order. */
    private static List<List<Integer>> members(int[] labels, int k) {
        var members = new ArrayList<List<Integer>>(k);
        for (int j = 0; j < k; j++) {
            members.add(new ArrayList<>());
        }
        for (int i = 0; i < labels.length; i++) {
            members.get(labels[i]).add(i);
        }
        return members;
    }

    /**
     * One group's examples: up to {@code frequent} texts that occur at least {@value #MIN_REPEATS} times, most frequent
     * first (ties by code point order), then the most similar texts not already listed (ties by trace order), up to
     * {@code total} in all. A text seen once is not "frequent": its slot goes to the most similar texts.
     *
     * @param members the group's traces, in trace order
     * @param sims    per trace, its similarity to the group: for a k-means group, its dot product with the centre;
     *                read for the members only
     */
    static List<String> pick(String[] texts, List<Integer> members, double[] sims, int frequent, int total) {
        var counts = new TreeMap<String, Integer>(TopicText.CODE_POINT_ORDER);
        for (int i : members) {
            counts.merge(texts[i], 1, Integer::sum);
        }
        var byCount = new ArrayList<>(counts.entrySet());
        byCount.sort(Comparator.comparingInt(entry -> -entry.getValue()));
        var examples = new ArrayList<String>(total);
        for (int e = 0; e < Math.min(frequent, byCount.size()); e++) {
            if (byCount.get(e).getValue() < MIN_REPEATS) {
                break;
            }
            examples.add(byCount.get(e).getKey());
        }
        var bySim = new ArrayList<>(members);
        bySim.sort((a, b) -> sims[a] > sims[b] ? -1 : sims[a] < sims[b] ? 1 : 0);
        for (int i : bySim) {
            if (examples.size() >= total) {
                break;
            }
            if (!examples.contains(texts[i])) {
                examples.add(texts[i]);
            }
        }
        return List.copyOf(examples);
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
            @NonNull List<String> examples) {
    }
}
