package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
     * The author's examples per group, {@code frequent + extra} in all (fewer when the group has fewer texts): up to
     * {@code frequent} texts that repeat, most frequent first (ties by code point order), then the texts nearest to the
     * centre not already listed (ties by first trace). A group where no text repeats gets only the nearest texts. Empty
     * for an empty group. It reads the points k-means grouped: a text's count is its weight, its nearness is that of
     * its vector, the first trace's.
     *
     * @param merged  the window's texts, merged as for the fit
     * @param labels  per unique text, the group in {@code [0, k)}
     * @param centres {@code k * dimensions}, unit length
     */
    public static List<List<String>> authorExamples(@NonNull IntentMerger.Merged merged, @NonNull int[] labels,
            @NonNull float[] centres, int frequent, int extra) {
        int d = merged.dimensions();
        int k = groupCount(centres, d);
        Preconditions.checkArgument(labels.length == merged.size(),
                "labels must hold one group per unique text, texts '%s', labels '%s'", merged.size(), labels.length);
        var groups = Members.of(labels, k);
        var out = new ArrayList<List<String>>(k);
        // One array for all groups: a group's pick reads only its own members' scores, which it writes first.
        var sims = new double[merged.size()];
        for (int j = 0; j < k; j++) {
            for (int m = groups.start()[j]; m < groups.start()[j + 1]; m++) {
                int u = groups.order()[m];
                sims[u] = dot(merged.vectors(), u, centres, j, d);
            }
            out.add(pick(merged, groups.order(), groups.start()[j], groups.start()[j + 1], merged.weights(), sims,
                    frequent, frequent + extra));
        }
        return out;
    }

    /**
     * One group's examples, by the rule on {@link #authorExamples}. It keeps only the best {@code total} texts while
     * it reads the group, so it never sorts the group.
     *
     * @param members the group's unique texts are {@code members[from..to)}, ascending, so in code point order
     * @param counts  per unique text, its traces in the group; read for the members only
     * @param sims    per unique text, its nearness to the group; read for the members only
     */
    static List<String> pick(IntentMerger.Merged merged, int[] members, int from, int to, int[] counts,
            double[] sims, int frequent, int total) {
        var chosen = new int[Math.max(0, Math.min(total, to - from))];
        if (chosen.length == 0) {
            return List.of();
        }
        var first = merged.firstIndex();
        int size = 0;
        int slots = Math.min(frequent, chosen.length);
        // The repeated texts. Members come in code point order, so one replaces an earlier one only on a higher count.
        for (int m = from; m < to && slots > 0; m++) {
            int u = members[m];
            if (counts[u] < MIN_REPEATS || (size == slots && counts[u] <= counts[chosen[size - 1]])) {
                continue;
            }
            int at = size < slots ? size++ : size - 1;
            while (at > 0 && counts[chosen[at - 1]] < counts[u]) {
                chosen[at] = chosen[at - 1];
                at--;
            }
            chosen[at] = u;
        }
        // The nearest of the rest.
        int repeated = size;
        for (int m = from; m < to && repeated < chosen.length; m++) {
            int u = members[m];
            if (contains(chosen, repeated, u)
                    || (size == chosen.length && !nearer(u, chosen[size - 1], sims, first))) {
                continue;
            }
            int at = size < chosen.length ? size++ : size - 1;
            while (at > repeated && nearer(u, chosen[at - 1], sims, first)) {
                chosen[at] = chosen[at - 1];
                at--;
            }
            chosen[at] = u;
        }
        var texts = merged.texts();
        var out = new String[size];
        for (int e = 0; e < size; e++) {
            out[e] = texts[chosen[e]];
        }
        return List.of(out);
    }

    private static boolean nearer(int a, int b, double[] sims, int[] first) {
        return sims[a] > sims[b] || (sims[a] == sims[b] && first[a] < first[b]);
    }

    private static boolean contains(int[] values, int size, int value) {
        for (int e = 0; e < size; e++) {
            if (values[e] == value) {
                return true;
            }
        }
        return false;
    }

    /** The number of groups {@code centres} holds, {@code dimensions} values each. */
    private static int groupCount(float[] centres, int dimensions) {
        Preconditions.checkArgument(dimensions > 0 && centres.length % dimensions == 0,
                "centres must hold whole rows, values '%s', dimensions '%s'", centres.length, dimensions);
        return centres.length / dimensions;
    }

    /** Per group, its unique texts: group {@code j}'s are {@code order[start[j]..start[j + 1])}, ascending. */
    private record Members(int[] order, int[] start) {

        static Members of(int[] labels, int k) {
            var start = new int[k + 1];
            for (int label : labels) {
                Preconditions.checkArgument(label >= 0 && label < k, "label '%s' is not a group in [0, %s)", label, k);
                start[label + 1]++;
            }
            for (int j = 0; j < k; j++) {
                start[j + 1] += start[j];
            }
            var next = Arrays.copyOf(start, k);
            var order = new int[labels.length];
            for (int u = 0; u < labels.length; u++) {
                order[next[labels[u]]++] = u;
            }
            return new Members(order, start);
        }
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
    public record GroupSummary(int group, int size, double share) {
    }
}
