package com.comet.opik.domain.topics;

import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
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
import java.util.stream.IntStream;

/**
 * One nightly update of a project's topic list, on a {@link NightState} read before and written after by the caller.
 * Pure apart from the Qwen calls behind {@link NightLlm}; the state is changed in place. Topic prototypes are frozen: a
 * topic keeps the group centres it was born with and the ones a night's decision adds, while they take traces (step
 * g), so its meaning does not drift from its name.
 *
 * <ol type="a">
 * <li>every trace to its nearest prototype at cosine 0.75, with the 0.95 Other gate, as the final assignment: a
 * topic's, a known-Other one (the memory: the centres of groups earlier nights left in Other), or none;</li>
 * <li>{@link CandidateGrouper} on the Other traces, held by the memory or unassigned; a group of at least
 * {@value CandidateGrouper#MIN_CANDIDATE} traces is a candidate;</li>
 * <li>{@link ForcedBirth}: every candidate goes to one naming call; a group may join a live topic, form a new topic, or
 * stay Other;</li>
 * <li>decisions become prototypes: the centre of every group given to a live topic is appended to its prototypes. The
 * memory keeps its prototypes whose held traces mostly stayed in Other, then the centre of every group the naming left
 * in Other (not a loose one), unless one kept is within {@value #SAME_MEMORY} of it;</li>
 * <li>the final assignment, as the first build's ({@link PrototypeAssigner}, with the 0.95 Other gate);</li>
 * <li>the lifecycle ({@link NightState#retireLowShare}): retirements on the share of the whole window, then a
 * reassignment when one happened, then the sizes;</li>
 * <li>{@link #dropIdle}: a prototype that won no trace leaves, a live topic keeping at least one. The window is a
 * prototype's lifetime. A retired topic keeps no prototypes: a theme that comes back later is born as a new topic.</li>
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
            @NonNull AlgorithmConfig config, @NonNull NightLlm llm) {
        int d = window.dimensions();
        int n = window.size();
        var memory = new ArrayList<>(state.getOtherPrototypes());
        // a. one assignment: topic index, HELD - m for known-Other prototype m, or OTHER
        var owners = assign(state, window, memory, config);
        var before = new int[n];
        for (int i = 0; i < n; i++) {
            before[i] = owners[i] >= 0 ? owners[i] : TopicAlgorithm.OTHER;
        }
        var recent = window.recent(config.getRecentDays());
        // b. the Other groups
        var grouped = other(owners);
        var grouping = CandidateGrouper.group(window, grouped, state.getRebuilds(), config);
        var candidates = grouping.candidates().stream()
                .sorted(Comparator.comparingInt((NightGroup group) -> -group.size())
                        .thenComparingInt(NightGroup::group))
                .toList();
        var big = candidates.stream()
                .filter(group -> group.share() >= BIG_SHARE)
                .map(NightGroup::group)
                .sorted()
                .toList();
        // c. the naming call
        Map<Integer, Integer> map = new LinkedHashMap<>();
        List<ForcedBirth.Outcome> forced = List.of();
        if (!candidates.isEmpty()) {
            forced = ForcedBirth.apply(state, candidates, map, () -> promptTopics(state, window, before, recent),
                    maxTopics, config.getRecentDays(), llm);
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
        // e. final assignment; f. lifecycle
        var last = assignFinal(state, window, config);
        int liveBefore = state.liveCount();
        state.retireLowShare(last.labels(), config.getRetireShare(), config.getRetireNights());
        if (state.liveCount() < liveBefore) {
            last = assignFinal(state, window, config);
        }
        var labels = last.labels();
        state.recordSizes(labels);
        // g. prototypes that won no trace in the last final assignment
        dropIdle(state, last);
        return Result.builder()
                .labels(labels)
                .before(before)
                .unassigned(grouped.length)
                .candidates(candidates.stream().map(NightGroup::group).sorted().toList())
                .big(big)
                .forced(forced)
                .groupMap(Map.copyOf(map))
                .build();
    }

    /**
     * Step a: per trace its topic index, {@code HELD - m} when known-Other prototype {@code m} takes it, or
     * {@link TopicAlgorithm#OTHER}. The same assignment as {@link #assignFinal}, the Other gate included.
     */
    static int[] assign(NightState state, NightWindow window, List<float[]> memory, AlgorithmConfig config) {
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
                gated = VectorMath.dot(memory.get(m), 0, vectors.get(p), 0, d) >= config.getOtherGate();
            }
            if (!gated) {
                vectors.add(memory.get(m));
                owners.add(HELD - m);
            }
        }
        if (vectors.isEmpty()) {
            return allOther(window.size());
        }
        return new PrototypeAssigner(vectors, owners, List.of(), d, config.getAssignMinCosine(),
                config.getOtherGate()).assign(window.vectors());
    }

    /**
     * Step e: topic index or {@link TopicAlgorithm#OTHER} per trace, with the Other gate, and the prototype that won
     * each trace.
     */
    static FinalAssignment assignFinal(NightState state, NightWindow window, AlgorithmConfig config) {
        var vectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t : state.live()) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        var assigner = new PrototypeAssigner(vectors, owners, state.getOtherPrototypes(), window.dimensions(),
                config.getAssignMinCosine(), config.getOtherGate());
        var winners = assigner.winners(window.vectors());
        return FinalAssignment.builder().assigner(assigner).labels(assigner.owners(winners)).winners(winners).build();
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
        var labels = grouping.labels();
        for (int r = 0; r < grouped.length; r++) {
            int owner = owners[grouped[r]];
            if (owner <= HELD) {
                held[HELD - owner]++;
                if (labels.length > 0 && placed.contains(labels[r])) {
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
            if (out.stream().noneMatch(kept -> VectorMath.dot(kept, 0, centre, 0, d) >= SAME_MEMORY)) {
                out.add(centre);
            }
        }
        return out;
    }

    /**
     * Step g, on the last final assignment (the one after a retirement, when there was one): a prototype stays when it
     * won at least one trace. A live topic none of whose prototypes won keeps its first. The memory keeps the
     * known-Other prototypes the gate kept that won; a gated one never wins, so it leaves. A dropped prototype won no
     * trace, so the labels stay as they are.
     */
    static void dropIdle(NightState state, FinalAssignment last) {
        var won = new boolean[last.assigner().prototypeCount()];
        for (int winner : last.winners()) {
            if (winner != PrototypeAssigner.NO_WINNER) {
                won[winner] = true;
            }
        }
        // The assigner's order: the live topics' prototypes in list order, then the known-Other ones the gate kept.
        int p = 0;
        for (int t : state.live()) {
            var prototypes = state.topic(t).getPrototypes();
            var kept = new ArrayList<float[]>();
            for (var prototype : prototypes) {
                if (won[p++]) {
                    kept.add(prototype);
                }
            }
            if (kept.isEmpty() && !prototypes.isEmpty()) {
                kept.add(prototypes.getFirst());
            }
            if (kept.size() < prototypes.size()) {
                state.topic(t).setPrototypes(kept);
            }
        }
        var memory = new ArrayList<float[]>();
        for (var other : last.assigner().keptOther()) {
            if (won[p++]) {
                memory.add(other);
            }
        }
        state.setOtherPrototypes(memory);
    }

    /** The window positions of the traces step a left without a topic: held by known Other or unassigned. */
    private static int[] other(int[] owners) {
        return IntStream.range(0, owners.length).filter(i -> owners[i] < 0).toArray();
    }

    private static int[] allOther(int n) {
        var out = new int[n];
        Arrays.fill(out, TopicAlgorithm.OTHER);
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
     * live list, share of the window, share of the recent days and {@value #PROMPT_EXAMPLES} member texts, picked as a
     * group's ({@link GroupSummaries#pick}): texts that repeat first, most frequent first (ties by code point order),
     * then the most typical ones, nearest to any of the topic's prototypes (ties by trace order).
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
            var topic = state.topic(t);
            int recentCount = 0;
            var members = new ArrayList<Integer>();
            for (int i = 0; i < n; i++) {
                if (before[i] == t) {
                    members.add(i);
                    recentCount += recent[i] ? 1 : 0;
                }
            }
            int count = members.size();
            out.add(TopicPrompts.PromptTopic.builder()
                    .id(++id)
                    .topic(t)
                    .name(topic.getName())
                    .definition(topic.getDefinition())
                    .share((double) count / n)
                    .recentShare((double) recentCount / Math.max(1, recentTotal))
                    .examples(examples(window, members.stream().mapToInt(Integer::intValue).toArray(),
                            topic.getPrototypes()))
                    .build());
        }
        return out;
    }

    /** A topic's {@value #PROMPT_EXAMPLES} prompt texts among its member traces, picked as a group's. */
    private static List<String> examples(NightWindow window, int[] members, List<float[]> prototypes) {
        if (members.length == 0) {
            return List.of();
        }
        int d = window.dimensions();
        var merged = IntentMerger.merge(window.texts(members), window.rows(members), d);
        var sims = new double[merged.size()];
        for (int u = 0; u < sims.length; u++) {
            sims[u] = Double.NEGATIVE_INFINITY;
            for (var prototype : prototypes) {
                sims[u] = Math.max(sims[u], VectorMath.dot(merged.vectors(), u * d, prototype, 0, d));
            }
        }
        return GroupSummaries.pick(merged, IntStream.range(0, merged.size()).toArray(), 0, merged.size(),
                merged.weights(), sims, PROMPT_EXAMPLES, PROMPT_EXAMPLES);
    }

    /**
     * Step e's result: the labels and, per trace, the prototype that won it, in {@code assigner}'s order, or
     * {@link PrototypeAssigner#NO_WINNER}.
     */
    @Builder(toBuilder = true)
    record FinalAssignment(@NonNull PrototypeAssigner assigner, @NonNull int[] labels, @NonNull int[] winners) {
    }

    /**
     * What the night decided, for the save, the run's stats and the tests: the final labels (topic index or
     * {@link TopicAlgorithm#OTHER} per trace), step a's labels by the list saved before tonight (a trace a
     * known-Other prototype holds is Other), the Other count before the night's decisions, the candidates and the big
     * ones, the naming outcomes and the group map at the end.
     */
    @Builder(toBuilder = true)
    public record Result(@NonNull int[] labels, @NonNull int[] before, int unassigned,
            @NonNull List<Integer> candidates, @NonNull List<Integer> big, @NonNull List<ForcedBirth.Outcome> forced,
            @NonNull Map<Integer, Integer> groupMap) {
    }
}
