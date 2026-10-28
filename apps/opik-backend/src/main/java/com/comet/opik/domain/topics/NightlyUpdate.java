package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * One nightly update of a project's topic list (stage 2b, steps a to l), on a {@link NightState} read before and
 * written after by the caller. Pure apart from the k-means and Qwen calls behind {@link NightClusterer} and
 * {@link NightLlm}; the state is changed in place.
 *
 * <ol type="a">
 * <li>review night ({@code rebuilds > 0} and a multiple of {@value #REVIEW_EVERY}): the known-Other prototypes are
 * dropped;</li>
 * <li>a2. {@link Revival}: a dormant topic whose traffic came back is live again;</li>
 * <li>every trace to its nearest prototype, topic or known Other, at cosine 0.75 (no Other gate here);</li>
 * <li>{@link Recentre} of every topic with members, then of the known-Other pool;</li>
 * <li>{@link CandidateGrouper} on the traces no prototype took;</li>
 * <li>big candidates: max(recent share, share) at least {@value #BIG_SHARE}; a big candidate on a full list forces a
 * review; Qwen is called when there is a big candidate or a review;</li>
 * <li>the revise call ({@code revise} or review prompt);</li>
 * <li>{@link ReviseApplier};</li>
 * <li>{@link Verifier};</li>
 * <li>{@link ForcedBirth};</li>
 * <li>the centre of every group mapped to a live topic is appended to its prototypes, Other ones to known Other, in the
 * group map's order;</li>
 * <li>the final assignment, as 2a's ({@link PrototypeAssigner}, with the 0.95 Other gate);</li>
 * <li>{@link Lifecycle}: retirements, then a reassignment when one happened, then the inactive switch and sizes.</li>
 * </ol>
 */
@UtilityClass
public class NightlyUpdate {

    static final int REVIEW_EVERY = 7;
    static final double BIG_SHARE = 0.02;
    static final int REVISE_TOKENS = 2500;
    static final String REVISE_TAG = "revise";
    /** Step b's owner of a known-Other prototype. */
    static final int KNOWN_OTHER = -2;
    static final int PROMPT_EXAMPLES = 4;

    public static Result run(@NonNull NightState state, @NonNull NightWindow window, int maxTopics,
            @NonNull NightClusterer clusterer, @NonNull NightLlm llm) {
        int d = window.dimensions();
        boolean reviewNight = state.getRebuilds() > 0 && state.getRebuilds() % REVIEW_EVERY == 0;
        // a. the known-Other memory expires on review nights
        if (reviewNight) {
            state.setOtherPrototypes(List.of());
        }
        // a2. dormant topics whose traffic came back
        var revived = Revival.apply(state, window, maxTopics);
        // b. yesterday's prototypes on tonight's window
        var before = assignBefore(state, window);
        var recent = window.recent();
        // c. recentre
        for (int t : state.live()) {
            var members = members(before, t);
            if (members.length > 0) {
                var topic = state.topic(t);
                topic.setPrototypes(Recentre.run(window, members, topic.getPrototypes(), topic.getId(), clusterer));
            }
        }
        var otherMembers = members(before, KNOWN_OTHER);
        if (otherMembers.length > 0) {
            state.setOtherPrototypes(Recentre.run(window, otherMembers, state.getOtherPrototypes(), null, clusterer));
        }
        // d. the unassigned
        var grouping = CandidateGrouper.group(window, members(before, PrototypeAssigner.OTHER), state.getRebuilds(),
                clusterer);
        var candidates = grouping.candidates();
        // e. trigger
        var big = candidates.stream()
                .filter(group -> Math.max(group.recentShare(), group.share()) >= BIG_SHARE)
                .map(GroupSummary::group)
                .collect(Collectors.toCollection(HashSet::new));
        boolean review = reviewNight || (!big.isEmpty() && state.liveCount() >= maxTopics);
        var result = Result.builder()
                .reviewNight(reviewNight)
                .review(review)
                .revived(revived)
                .unassigned(grouping.unassigned().length)
                .candidates(candidates.stream().map(GroupSummary::group).toList())
                .big(big.stream().sorted().toList());
        Map<Integer, Integer> map = new LinkedHashMap<>();
        if (!big.isEmpty() || review) {
            // f. revise
            var shown = promptTopics(state, window, before, recent);
            var answer = llm.generate(REVISE_TAG, TopicPrompts.revise(shown, candidates, review, maxTopics),
                    TopicPrompts.REVISE_SCHEMA, REVISE_TOKENS);
            // g. apply
            var candidateIds = candidates.stream().map(GroupSummary::group).collect(Collectors.toSet());
            map = ReviseApplier.apply(state, answer, shown, candidateIds, review, maxTopics);
            result.afterRevise(Map.copyOf(map));
            // h. verify, on the topics live after the apply
            var ordered = candidates.stream().sorted(Comparator.comparingInt(group -> -group.size())).toList();
            var verifyTopics = new LinkedHashMap<Integer, TopicPrompts.PromptTopic>();
            promptTopics(state, window, before, recent).forEach(topic -> verifyTopics.put(topic.topic(), topic));
            var verified = Verifier.verify(ordered, verifyTopics, map, llm);
            result.verified(verified);
            // i. forced new topics
            result.forced(ForcedBirth.apply(state, ordered, big, verifyTopics, verified, map,
                    () -> promptTopics(state, window, before, recent), maxTopics, llm));
            // j. decisions become prototypes
            for (int t : state.live()) {
                var added = new ArrayList<float[]>();
                for (var entry : map.entrySet()) {
                    if (entry.getValue() == t) {
                        added.add(grouping.centre(entry.getKey(), d));
                    }
                }
                if (!added.isEmpty()) {
                    var prototypes = new ArrayList<>(state.topic(t).getPrototypes());
                    prototypes.addAll(added);
                    state.topic(t).setPrototypes(prototypes);
                }
            }
            var other = new ArrayList<>(state.getOtherPrototypes());
            map.forEach((group, topic) -> {
                if (topic == ReviseApplier.OTHER) {
                    other.add(grouping.centre(group, d));
                }
            });
            state.setOtherPrototypes(other);
        }
        result.groupMap(Map.copyOf(map));
        // k. final assignment
        var labels = assignFinal(state, window);
        // l. lifecycle
        int liveBefore = state.liveCount();
        result.lifecycleShares(Lifecycle.retire(state, window, labels, maxTopics));
        if (state.liveCount() < liveBefore) {
            labels = assignFinal(state, window);
        }
        Lifecycle.display(state, window, labels);
        return result.labels(labels).build();
    }

    /** Step b: topic index, {@link #KNOWN_OTHER}, or {@link PrototypeAssigner#OTHER} per trace. */
    static int[] assignBefore(NightState state, NightWindow window) {
        var vectors = new ArrayList<float[]>();
        var owners = new ArrayList<Integer>();
        for (int t : state.live()) {
            for (var prototype : state.topic(t).getPrototypes()) {
                vectors.add(prototype);
                owners.add(t);
            }
        }
        for (var prototype : state.getOtherPrototypes()) {
            vectors.add(prototype);
            owners.add(KNOWN_OTHER);
        }
        if (vectors.isEmpty()) {
            return allOther(window.size());
        }
        return new PrototypeAssigner(vectors, owners, List.of(), window.dimensions()).assign(window.vectors());
    }

    /** Step k: topic index or {@link PrototypeAssigner#OTHER} per trace, with the Other gate. */
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

    private static int[] allOther(int n) {
        var out = new int[n];
        java.util.Arrays.fill(out, PrototypeAssigner.OTHER);
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
     * The live topics as the revise and verify prompts show them, from the step b labels: prompt id = position in the
     * live list, share of the window, share of the last 7 days, the {@value #PROMPT_EXAMPLES} most frequent member texts
     * (ties by code point order).
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

    /**
     * What the night decided, for the save, the run's stats and the tests: the final labels (topic index or
     * {@link PrototypeAssigner#OTHER} per trace), the review flags, the unassigned count, the candidates and big ones,
     * the group map after the revise apply and at the end, the verify result, the forced outcomes and the shares the
     * lifecycle judged.
     */
    @Builder
    public record Result(@NonNull int[] labels, boolean reviewNight, boolean review, @NonNull List<Integer> revived,
            int unassigned,
            @NonNull List<Integer> candidates, @NonNull List<Integer> big, Map<Integer, Integer> afterRevise,
            Verifier.Verified verified, List<ForcedBirth.Outcome> forced, @NonNull Map<Integer, Integer> groupMap,
            @NonNull Map<Integer, Double> lifecycleShares) {
    }
}
