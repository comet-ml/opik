package com.comet.opik.domain.topics;

import com.google.common.base.Preconditions;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Per k-means group, what a prompt shows. For the author prompt (the discovery run), its size and share of the window
 * ({@link #summarize(int[], int)}) and its examples ({@link #authorExamples}). For the nightly prompts, also its
 * tightness (mean cosine of its traces to the centre) and examples by the same rule
 * ({@link #summarize(MergedIntents, int[], float[], int, int)}); the caller sets the shares.
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
     * Per group, the nightly summary of the points k-means grouped: its size (a text counts as its weight), tightness
     * and examples ({@code frequent} repeated, {@code examples} in all, by the rule on {@link #authorExamples}). The
     * shares stay 0 for the caller to set.
     *
     * @param merged  the texts, merged as for the fit
     * @param labels  per unique text, the group in {@code [0, k)}
     * @param centres {@code k * dimensions}, unit length
     */
    static List<NightGroup> summarize(@NonNull MergedIntents merged, @NonNull int[] labels,
            @NonNull float[] centres, int frequent, int examples) {
        var scored = Scored.of(merged, labels, centres);
        var counts = merged.weights();
        return IntStream.range(0, scored.k()).mapToObj(j -> {
            int size = 0;
            double simSum = 0;
            for (int m = scored.groups().start()[j]; m < scored.groups().start()[j + 1]; m++) {
                int u = scored.groups().order()[m];
                size += counts[u];
                simSum += counts[u] * scored.sims()[u];
            }
            return NightGroup.builder()
                    .group(j)
                    .size(size)
                    .tightness(size == 0 ? 0 : simSum / size)
                    .examples(scored.pick(merged, j, counts, frequent, examples))
                    .build();
        }).toList();
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
    public static List<List<String>> authorExamples(@NonNull MergedIntents merged, @NonNull int[] labels,
            @NonNull float[] centres, int frequent, int extra) {
        Preconditions.checkArgument(frequent >= 0 && extra >= 0,
                "frequent and extra must not be negative, frequent '%s', extra '%s'", frequent, extra);
        var scored = Scored.of(merged, labels, centres);
        return IntStream.range(0, scored.k())
                .mapToObj(j -> scored.pick(merged, j, merged.weights(), frequent, frequent + extra))
                .toList();
    }

    /** The groups' members and every text's nearness to its own group's centre, read once for all groups. */
    private record Scored(Members groups, double[] sims, int k) {

        static Scored of(MergedIntents merged, int[] labels, float[] centres) {
            int d = merged.dimensions();
            Preconditions.checkArgument(labels.length == merged.size(),
                    "labels must hold one group per unique text, texts '%s', labels '%s'", merged.size(),
                    labels.length);
            Preconditions.checkArgument(d > 0 && centres.length % d == 0,
                    "centres must hold whole rows, dimensions '%s', values '%s'", d, centres.length);
            int k = centres.length / d;
            var groups = Members.of(labels, k);
            var sims = new double[merged.size()];
            for (int u = 0; u < sims.length; u++) {
                sims[u] = VectorMath.dot(merged.vectors(), u * d, centres, labels[u] * d, d);
            }
            return new Scored(groups, sims, k);
        }

        List<String> pick(MergedIntents merged, int group, int[] counts, int frequent, int total) {
            return GroupSummaries.pick(merged, groups.order(), groups.start()[group], groups.start()[group + 1],
                    counts, sims, frequent, total);
        }
    }

    /**
     * One group's examples, by the rule on {@link #authorExamples}. It keeps only the best {@code total} texts while
     * it reads the group, so it never sorts the group.
     *
     * @param members the group's unique texts are {@code members[from..to)}, ascending, so in code point order
     * @param counts  per unique text, its traces in the group; read for the members only. Not always the merge weight:
     *                a caller that splits one text's traces across groups passes each group's own count
     * @param sims    per unique text, its nearness to the group; read for the members only
     */
    static List<String> pick(MergedIntents merged, int[] members, int from, int to, int[] counts,
            double[] sims, int frequent, int total) {
        int capacity = Math.max(0, Math.min(total, to - from));
        if (capacity == 0) {
            return List.of();
        }
        var first = merged.firstIndex();
        Order byCount = (a, b) -> counts[a] > counts[b];
        Order byNearness = (a, b) -> nearer(a, b, sims, first);
        var best = new Best(capacity);
        // The repeated texts. Members come in code point order, so one replaces an earlier one only on a higher count.
        int slots = Math.min(frequent, capacity);
        best.open(0, slots);
        for (int m = from; m < to && slots > 0; m++) {
            int u = members[m];
            if (counts[u] >= MIN_REPEATS) {
                best.offer(u, byCount);
            }
        }
        // The nearest of the rest.
        int repeated = best.size;
        best.open(repeated, capacity);
        for (int m = from; m < to && repeated < capacity; m++) {
            int u = members[m];
            if (!best.holds(u, repeated)) {
                best.offer(u, byNearness);
            }
        }
        var texts = merged.texts();
        var out = new String[best.size];
        for (int e = 0; e < best.size; e++) {
            out[e] = texts[best.items[e]];
        }
        return List.of(out);
    }

    private static boolean nearer(int a, int b, double[] sims, int[] first) {
        return sims[a] > sims[b] || (sims[a] == sims[b] && first[a] < first[b]);
    }

    @FunctionalInterface
    private interface Order {
        boolean better(int a, int b);
    }

    /** The best items offered, best first, in slots {@code [floor, limit)}; an earlier offer wins a tie. */
    private static final class Best {

        private final int[] items;
        private int size;
        private int floor;
        private int limit;

        Best(int capacity) {
            items = new int[capacity];
        }

        /** Later offers fill slots {@code [floor, limit)}; the slots before {@code floor} stay as they are. */
        void open(int floor, int limit) {
            this.floor = floor;
            this.limit = limit;
        }

        void offer(int u, Order order) {
            if (size == limit && (limit == floor || !order.better(u, items[size - 1]))) {
                return;
            }
            int at = size < limit ? size++ : size - 1;
            while (at > floor && order.better(u, items[at - 1])) {
                items[at] = items[at - 1];
                at--;
            }
            items[at] = u;
        }

        /** Whether {@code u} is among the first {@code count} items. */
        boolean holds(int u, int count) {
            for (int e = 0; e < count; e++) {
                if (items[e] == u) {
                    return true;
                }
            }
            return false;
        }
    }

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
}
