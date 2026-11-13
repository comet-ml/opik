package com.comet.opik.domain.topics;

import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.TreeMap;

/**
 * Per k-means group, what the author prompt shows: its size and share of the window, and its example texts, with
 * stable tie-breaks.
 */
@UtilityClass
public class GroupSummaries {

    /** A text takes a "repeated" example slot only when it occurs at least this often in its group. */
    static final int MIN_REPEATS = 2;

    /**
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
     * first (ties by code point order), then the texts nearest to the centre not already listed (ties by trace order),
     * up to {@code total} in all. A text seen once is not "frequent": its slot goes to the nearest texts.
     *
     * @param members the group's traces, in trace order
     * @param sims    per trace, its dot product with the group's centre; read for the members only
     */
    private static List<String> pick(String[] texts, List<Integer> members, double[] sims, int frequent, int total) {
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

    @Builder
    public record GroupSummary(int group, int size, double share) {
    }
}
