package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.IntFunction;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Finds topics that cover the same traces. A topic's centre is the normalised mean of its member vectors. Two topics
 * whose centres have a cosine of at least {@code mergeCosine} are one theme split in two: their prototypes interleave,
 * so each trace goes to whichever happens to be nearer and the split carries no meaning. The smaller one is folded
 * into the larger (more members; a tie keeps the lower index), the pair with the highest cosine first. The folded
 * topic's members count for the one that took it, so a chain of three merges into one. A topic with no member has no
 * centre and is never merged.
 *
 * <p>A merged topic covers what its former topics covered together, so neither former name fits it: {@link #rename}
 * asks one call for a name per merged topic. A merged topic the answer leaves out or names badly keeps its first
 * former name.
 */
@UtilityClass
class TopicMerger {

    /** One merge: {@code absorbed} is folded into {@code kept}. */
    record Merge(int kept, int absorbed, double cosine) {
    }

    /** A merged topic for the rename call: its former names and definitions, the kept one first. */
    record Merged(@NonNull List<String> names, @NonNull List<String> definitions, double share,
            @NonNull List<String> examples) {
    }

    record Name(@NonNull String name, @NonNull String definition) {
    }

    /** Per topic index, the index it ends up folded into after {@code merges}; itself when it was not folded. */
    static int[] roots(int topicCount, @NonNull List<Merge> merges) {
        var root = new int[topicCount];
        for (int t = 0; t < topicCount; t++) {
            root[t] = t;
        }
        for (var merge : merges) {
            for (int t = 0; t < topicCount; t++) {
                if (root[t] == merge.absorbed()) {
                    root[t] = merge.kept();
                }
            }
        }
        return root;
    }

    /** The topics folded into {@code root} after the merges that gave {@code roots}: {@code root} first, then by index. */
    static List<Integer> members(@NonNull int[] roots, int root) {
        return IntStream.range(0, roots.length).filter(t -> roots[t] == root).boxed()
                .sorted(Comparator.comparingInt(t -> t == root ? -1 : t)).toList();
    }

    /**
     * The rename call for {@code merges}, then per topic that took others its name. {@code merged} gives the call's
     * entry for such a topic from its {@link #members}; the names of the other {@code live} topics still standing are
     * taken.
     *
     * @param live the live topic indices; a folded one is skipped
     */
    static NightStep<Map<Integer, Name>> rename(@NonNull List<Merge> merges, int topicCount, @NonNull int[] live,
            @NonNull IntFunction<String> nameOf, @NonNull BiFunction<Integer, List<Integer>, Merged> merged,
            @NonNull TopicTasks tasks) {
        var roots = roots(topicCount, merges);
        var renamed = merges.stream().map(merge -> roots[merge.kept()]).distinct().sorted().toList();
        var taken = Arrays.stream(live).filter(t -> roots[t] == t && !renamed.contains(t))
                .mapToObj(nameOf).collect(Collectors.toSet());
        var entries = renamed.stream().map(root -> merged.apply(root, members(roots, root))).toList();
        return rename(entries, taken, tasks).map(names -> {
            var out = new LinkedHashMap<Integer, Name>();
            for (int m = 0; m < renamed.size(); m++) {
                out.put(renamed.get(m), names.get(m));
            }
            return out;
        });
    }

    /**
     * The rename call over {@code merged}, then per merged topic its name, in order. A name left out, blank, a
     * catch-all, or equal (by {@link AuthorApplier#nameKey}) to one in {@code taken}, to an earlier one, or to another
     * merged topic's first former name keeps the first former name and definition. Those first names are reserved up
     * front, so a merged topic that keeps its first name never meets another with the same name.
     *
     * @param taken the names of the other live topics
     */
    static NightStep<List<Name>> rename(@NonNull List<Merged> merged, @NonNull Set<String> taken,
            @NonNull TopicTasks tasks) {
        if (merged.isEmpty()) {
            return NightStep.done(List.of());
        }
        var task = tasks.merge();
        var request = LlmRequest.builder()
                .call(LlmCall.NAMING)
                .tag(TopicTasks.MERGE)
                .prompt(TopicPrompts.merge(task, merged))
                .schema(task.schema())
                .maxTokens(task.effectiveMaxTokens())
                .build();
        return NightStep.ask(request, answer -> NightStep.done(names(answer, merged, taken)));
    }

    private static List<Name> names(JsonNode answer, List<Merged> merged, Set<String> taken) {
        var named = new Name[merged.size()];
        var used = new HashSet<String>();
        taken.forEach(name -> used.add(AuthorApplier.nameKey(name)));
        merged.forEach(entry -> used.add(AuthorApplier.nameKey(entry.names().getFirst())));
        for (var entry : NightJson.objects(answer == null ? null : answer.get("topics"))) {
            var id = NightJson.integer(entry.get("id"));
            var name = TopicText.name(text(entry.get("name")));
            var definition = text(entry.get("definition"));
            if (id == null || id < 0 || id >= merged.size() || named[id] != null || name.isEmpty()
                    || definition.isEmpty() || AuthorApplier.isCatchAllName(name)) {
                continue;
            }
            var key = AuthorApplier.nameKey(name);
            if (!key.equals(AuthorApplier.nameKey(merged.get(id).names().getFirst())) && !used.add(key)) {
                continue;
            }
            named[id] = new Name(name, definition);
        }
        var out = new ArrayList<Name>();
        for (int m = 0; m < merged.size(); m++) {
            out.add(named[m] != null
                    ? named[m]
                    : new Name(merged.get(m).names().getFirst(), merged.get(m).definitions().getFirst()));
        }
        return out;
    }

    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? TopicText.pythonStrip(node.asText()) : "";
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
