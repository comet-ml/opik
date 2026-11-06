package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import lombok.Builder;
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
import java.util.TreeMap;
import java.util.stream.IntStream;

/**
 * One nightly update of a project's topic list, on a {@link NightState} read before and written after by the caller.
 * Pure apart from the k-means and Qwen calls behind {@link NightClusterer} and {@link NightLlm}; the state is changed
 * in place. Topic prototypes are frozen: a topic keeps the group centres it was born with and the ones a night's
 * decision adds, so its meaning does not drift from its name.
 *
 * <ol type="a">
 * <li>every trace to its nearest prototype at cosine 0.75, with the 0.95 Other gate, as the final assignment: a
 * topic's, a known-Other one (the memory: the centres of groups earlier nights left in Other), or none;</li>
 * <li>{@link CandidateGrouper} on the Other traces, held by the memory or unassigned; a group of at least
 * {@value CandidateGrouper#MIN_CANDIDATE} traces is a candidate;</li>
 * <li>{@link ForcedBirth}: every candidate goes to one naming call (a loose one stays Other without a call). A group
 * may join a live topic (through one batched verify), form a new topic with same-kind groups, or stay Other. A new
 * topic is born only when its groups' share of the window reaches {@value #BIG_SHARE}; on a full list it replaces the
 * topic with the lowest share of the window, when that is below the new topic's;</li>
 * <li>decisions become prototypes: the centre of every group given to a live topic is appended to its prototypes. The
 * memory keeps its prototypes whose held traces mostly stayed in Other, then the centre of every group the naming left
 * in Other (not a loose one), unless one kept is within {@value #SAME_MEMORY} of it;</li>
 * <li>{@link Rebirth}: a topic born tonight on a dormant topic's traces is that topic again;</li>
 * <li>the final assignment, as the first build's ({@link PrototypeAssigner}, with the 0.95 Other gate);</li>
 * <li>{@link Lifecycle}: retirements on the share of the whole window, then a reassignment when one happened, then the
 * inactive switch and sizes.</li>
 * </ol>
 */
@UtilityClass
public class NightlyUpdate {

    /** A candidate's share of the window that makes it big: a new topic needs it. */
    static final double BIG_SHARE = 0.02;
    /** A known-Other prototype this close to one already kept is not kept again. */
    static final double SAME_MEMORY = 0.95;
    /** A group mapped to Other in the group map. */
    public static final int OTHER = -1;
    /** Step a's owner of the traces held by known-Other prototype {@code m}: {@code HELD - m}. */
    private static final int HELD = -10;
    static final int PROMPT_EXAMPLES = 4;

    public static Result run(@NonNull NightState state, @NonNull NightWindow window, int maxTopics,
            @NonNull NightClusterer clusterer, @NonNull NightLlm llm) {
        int d = window.dimensions();
        int n = window.size();
        var memory = new ArrayList<>(state.getOtherPrototypes());
        // a. one assignment: topic index, HELD - m for known-Other prototype m, or OTHER
        var owners = assign(state, window, memory);
        var before = new int[n];
        for (int i = 0; i < n; i++) {
            before[i] = owners[i] >= 0 ? owners[i] : PrototypeAssigner.OTHER;
        }
        var recent = window.recent();
        // b. the Other groups
        var grouped = other(owners);
        var grouping = CandidateGrouper.group(window, grouped, state.getRebuilds(), clusterer);
        var candidates = grouping.candidates().stream()
                .sorted(Comparator.comparingInt((GroupSummary group) -> -group.size())
                        .thenComparingInt(GroupSummary::group))
                .toList();
        var big = candidates.stream()
                .filter(group -> group.share() >= BIG_SHARE)
                .map(GroupSummary::group)
                .sorted()
                .toList();
        // c. the naming call
        Map<Integer, Integer> map = new LinkedHashMap<>();
        List<ForcedBirth.Outcome> forced = List.of();
        if (!candidates.isEmpty()) {
            forced = ForcedBirth.apply(state, candidates, map, () -> promptTopics(state, window, before, recent),
                    maxTopics, llm);
        }
        // d. decisions become prototypes
        var placed = new HashSet<Integer>();
        map.forEach((group, topic) -> {
            if (topic >= 0 && state.topic(topic).isLive()) {
                var prototypes = new ArrayList<>(state.topic(topic).getPrototypes());
                prototypes.add(grouping.centre(group, d));
                state.topic(topic).setPrototypes(prototypes);
                placed.add(group);
            }
        });
        state.setOtherPrototypes(memory(memory, owners, grouped, grouping, placed, forced, d));
        // e. a newborn on a dormant topic's traces is that topic again
        List<Integer> revived = List.of();
        var moves = Rebirth.apply(state, window);
        if (!moves.isEmpty()) {
            var remapped = new LinkedHashMap<Integer, Integer>();
            map.forEach((group, topic) -> remapped.put(group, topic < 0 ? topic : Rebirth.remap(topic, moves)));
            map = remapped;
            forced = forced.stream()
                    .map(outcome -> outcome.topic() == null
                            ? outcome
                            : outcome.toBuilder().topic(Rebirth.remap(outcome.topic(), moves)).build())
                    .toList();
            revived = moves.stream().map(move -> Rebirth.remap(move[1], moves)).sorted().toList();
        }
        // f. final assignment; g. lifecycle
        var labels = assignFinal(state, window);
        int liveBefore = state.liveCount();
        var shares = Lifecycle.retire(state, window, labels, maxTopics);
        if (state.liveCount() < liveBefore) {
            labels = assignFinal(state, window);
        }
        Lifecycle.display(state, window, labels);
        return Result.builder()
                .labels(labels)
                .revived(revived)
                .unassigned(grouped.length)
                .candidates(candidates.stream().map(GroupSummary::group).sorted().toList())
                .big(big)
                .forced(forced)
                .groupMap(Map.copyOf(map))
                .lifecycleShares(shares)
                .build();
    }

    /**
     * Step a: per trace its topic index, {@code HELD - m} when known-Other prototype {@code m} takes it, or
     * {@link PrototypeAssigner#OTHER}. The same assignment as {@link #assignFinal}, the Other gate included.
     */
    static int[] assign(NightState state, NightWindow window, List<float[]> memory) {
        int d = window.dimensions();
        var vectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t : state.live()) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        int topicCount = vectors.size();
        for (int m = 0; m < memory.size(); m++) {
            boolean gated = false;
            for (int p = 0; p < topicCount && !gated; p++) {
                gated = dot(memory.get(m), vectors.get(p), d) >= PrototypeAssigner.OTHER_GATE;
            }
            if (!gated) {
                vectors.add(memory.get(m));
                owners.add(HELD - m);
            }
        }
        if (vectors.isEmpty()) {
            return allOther(window.size());
        }
        return new PrototypeAssigner(vectors, owners, List.of(), d).assign(window.vectors());
    }

    /** Step f: topic index or {@link PrototypeAssigner#OTHER} per trace, with the Other gate. */
    static int[] assignFinal(NightState state, NightWindow window) {
        var vectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t : state.live()) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        if (vectors.isEmpty() && state.getOtherPrototypes().isEmpty()) {
            return allOther(window.size());
        }
        return new PrototypeAssigner(vectors, owners, state.getOtherPrototypes(), window.dimensions())
                .assign(window.vectors());
    }

    /**
     * Step d's memory: the prototypes most of whose held traces are not in a placed group, then the centre of every
     * group the naming left in Other (not a loose one; a new topic under {@value #BIG_SHARE} included) that is not
     * within {@value #SAME_MEMORY} of one kept.
     */
    private static List<float[]> memory(List<float[]> memory, int[] owners, int[] grouped,
            CandidateGrouper.Grouping grouping, Set<Integer> placed, List<ForcedBirth.Outcome> forced, int d) {
        var held = new int[memory.size()];
        var placedHeld = new int[memory.size()];
        for (int r = 0; r < grouped.length; r++) {
            int owner = owners[grouped[r]];
            if (owner <= HELD) {
                held[HELD - owner]++;
                if (grouping.labels().length > 0 && placed.contains(grouping.labels()[r])) {
                    placedHeld[HELD - owner]++;
                }
            }
        }
        var out = new ArrayList<float[]>();
        for (int m = 0; m < memory.size(); m++) {
            if (placedHeld[m] * 2 <= held[m]) {
                out.add(memory.get(m));
            }
        }
        for (var outcome : forced) {
            if (outcome.result() != ForcedBirth.Result.OTHER_NAME
                    && outcome.result() != ForcedBirth.Result.OTHER_FULL
                    && outcome.result() != ForcedBirth.Result.OTHER_SMALL) {
                continue;
            }
            var centre = grouping.centre(outcome.group(), d);
            if (out.stream().noneMatch(kept -> dot(kept, centre, d) >= SAME_MEMORY)) {
                out.add(centre);
            }
        }
        return out;
    }

    /** The window positions of the traces step a left without a topic: held by known Other or unassigned. */
    private static int[] other(int[] owners) {
        return IntStream.range(0, owners.length).filter(i -> owners[i] < 0).toArray();
    }

    private static int[] allOther(int n) {
        var out = new int[n];
        Arrays.fill(out, PrototypeAssigner.OTHER);
        return out;
    }

    static int[] members(int[] labels, int owner) {
        int count = 0;
        for (int label : labels) {
            count += label == owner ? 1 : 0;
        }
        var out = new int[count];
        int r = 0;
        for (int i = 0; i < labels.length; i++) {
            if (labels[i] == owner) {
                out[r++] = i;
            }
        }
        return out;
    }

    /**
     * The live topics as the naming and verify prompts show them, from the step a labels: prompt id = position in the
     * live list, share of the window, share of the last 7 days, the {@value #PROMPT_EXAMPLES} most frequent member
     * texts (ties by code point order).
     */
    static List<TopicPrompts.PromptTopic> promptTopics(NightState state, NightWindow window, int[] before,
            boolean[] recent) {
        int n = window.size();
        int recentTotal = 0;
        for (boolean r : recent) {
            recentTotal += r ? 1 : 0;
        }
        var out = new ArrayList<TopicPrompts.PromptTopic>();
        int id = 0;
        for (int t : state.live()) {
            int count = 0;
            int recentCount = 0;
            var texts = new TreeMap<String, Integer>(TopicText.CODE_POINT_ORDER);
            for (int i = 0; i < n; i++) {
                if (before[i] == t) {
                    count++;
                    recentCount += recent[i] ? 1 : 0;
                    texts.merge(window.texts()[i], 1, Integer::sum);
                }
            }
            var byCount = new ArrayList<>(texts.entrySet());
            byCount.sort(Comparator.comparingInt(entry -> -entry.getValue()));
            var topic = state.topic(t);
            out.add(TopicPrompts.PromptTopic.builder()
                    .id(++id)
                    .topic(t)
                    .name(topic.getName())
                    .definition(topic.getDefinition())
                    .share((double) count / n)
                    .recentShare((double) recentCount / Math.max(1, recentTotal))
                    .examples(byCount.stream().limit(PROMPT_EXAMPLES).map(Map.Entry::getKey).toList())
                    .build());
        }
        return out;
    }

    private static double dot(float[] a, float[] b, int dimensions) {
        double s = 0;
        for (int t = 0; t < dimensions; t++) {
            s += (double) a[t] * b[t];
        }
        return s;
    }

    /**
     * What the night decided, for the save, the run's stats and the tests: the final labels (topic index or
     * {@link PrototypeAssigner#OTHER} per trace), the topics revived, the Other count before the night's decisions,
     * the candidates and the big ones, the naming outcomes, the group map at the end and the shares the lifecycle
     * judged.
     */
    @Builder
    public record Result(@NonNull int[] labels, @NonNull List<Integer> revived, int unassigned,
            @NonNull List<Integer> candidates, @NonNull List<Integer> big, @NonNull List<ForcedBirth.Outcome> forced,
            @NonNull Map<Integer, Integer> groupMap, @NonNull Map<Integer, Double> lifecycleShares) {
    }
}
