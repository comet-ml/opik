package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import com.comet.opik.infrastructure.TopicsConfig.AlgorithmConfig;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;

/**
 * Step c of the nightly update; every share is a share of the whole window. Every
 * candidate, largest first, goes to one grouped naming call for the night; a loose one (tightness below
 * {@code nightlyMinTightness}) is known Other without a call.
 * <p>
 * The naming call shows the live topics and may assign a group to one, put same-kind groups into one new topic, or
 * leave a group in Other. An assign passes one batched verify; a rejected group goes to a second call that offers new
 * and other only. A new topic's groups are de-duplicated; a blank name or a name that is a catch-all as a whole
 * ({@link AuthorApplier#isCatchAllName}, the discovery run's rule) is skipped; a name equal to a live topic's (by
 * {@link AuthorApplier#nameKey}) joins that topic in the first call and is known Other in the second (the verify may
 * just have rejected that topic for it), and equal new names are one topic. New topics are created largest first; one
 * whose groups' share of the window is below {@code nightlyBigShare} is not born, its groups are known Other
 * ({@link Result#OTHER_SMALL}). On a full list, the topic not born tonight with the lowest share of the window is
 * retired when that share is below the new topic's, else its groups are known Other; a topic's share here counts the
 * groups given to it tonight. A group the answers leave out is known Other.
 * <p>
 * A call that fails (timeout or error) leaves the groups it was deciding in Other tonight, not known Other
 * ({@link Result#OTHER_FAILED}), so they are candidates again on the next night: the naming call's groups, the groups
 * a failed verify was checking (they do not go to the second call, whose prompt says they belong to no live topic),
 * and the second call's groups. A verify answer that is not a JSON object gives no verdicts either, so it counts as a
 * failed verify.
 */
@Slf4j
@UtilityClass
class ForcedBirth {

    static final String NAMING_TAG = "naming";
    static final String NAMING_NEW_TAG = "naming_new";
    static final String VERIFY_TAG = "verify_naming";

    /**
     * The naming of the night's candidates, as steps: the naming call, the verify of its assigns, the second naming of
     * the rejected groups. Each step returns what it decided; only {@code state} is changed in place (births and
     * replacements).
     *
     * @param ordered     the candidates largest first (ties by group id)
     * @param liveTopics the live topics as they stand now, read again for each naming call
     */
    static NightStep<Named> apply(@NonNull NightState state, @NonNull List<NightGroup> ordered,
            @NonNull Supplier<List<LiveTopic>> liveTopics, int maxTopics, @NonNull AlgorithmConfig config,
            @NonNull TopicTasks tasks) {
        var named = new Decisions(Named.EMPTY);
        var groupShares = new HashMap<Integer, Double>();
        ordered.forEach(group -> groupShares.put(group.group(), group.share()));
        var forced = new ArrayList<NightGroup>();
        for (var candidate : ordered) {
            if (candidate.tightness() < config.getNightlyMinTightness()) {
                named.put(candidate.group(), TopicAlgorithm.OTHER, Result.OTHER_LOOSE, null);
                continue;
            }
            forced.add(candidate);
        }
        if (forced.isEmpty()) {
            return NightStep.done(named.named());
        }
        return new Naming(state, liveTopics, maxTopics, config, tasks, groupShares).name(named.named(), forced, true);
    }

    /**
     * What the naming decided, in the order it decided: the outcomes, and the group map (candidate group to topic index
     * or {@link TopicAlgorithm#OTHER}) in the same order.
     */
    @Builder(toBuilder = true)
    record Named(@NonNull List<Outcome> outcomes, @NonNull Map<Integer, Integer> groupMap) {

        static final Named EMPTY = new Named(List.of(), Map.of());
    }

    /** A step's own copy of what was decided before it, to add its decisions to. */
    private static final class Decisions {

        private final List<Outcome> outcomes;
        private final Map<Integer, Integer> groupMap;

        Decisions(Named named) {
            this.outcomes = new ArrayList<>(named.outcomes());
            this.groupMap = new LinkedHashMap<>(named.groupMap());
        }

        void put(int group, int topic, Result result, Integer outcomeTopic) {
            groupMap.put(group, topic);
            outcomes.add(Outcome.builder().group(group).result(result).topic(outcomeTopic).build());
        }

        boolean contains(int group) {
            return groupMap.containsKey(group);
        }

        List<Outcome> outcomes() {
            return outcomes;
        }

        Named named() {
            return new Named(List.copyOf(outcomes), Collections.unmodifiableMap(new LinkedHashMap<>(groupMap)));
        }
    }

    /** The groups the verify rejected, and what was decided until then. */
    private record Assigned(Named named, List<NightGroup> rejected) {
    }

    /** One night's naming: what its calls read. Each step returns its decisions. */
    @RequiredArgsConstructor
    private static final class Naming {

        private final NightState state;
        private final Supplier<List<LiveTopic>> liveTopics;
        private final int maxTopics;
        private final AlgorithmConfig config;
        private final TopicTasks tasks;
        private final Map<Integer, Double> groupShares;

        /** One naming call over {@code forced}; {@code allowAssign} is the first call's, the second offers new and other only. */
        NightStep<Named> name(Named before, List<NightGroup> forced, boolean allowAssign) {
            var live = liveTopics.get();
            var byTopic = new HashMap<Integer, LiveTopic>();
            live.forEach(topic -> byTopic.put(topic.topic(), topic));
            var byGroup = new LinkedHashMap<Integer, NightGroup>();
            forced.forEach(group -> byGroup.put(group.group(), group));
            var task = allowAssign ? tasks.naming() : tasks.namingNew();
            var prompt = TopicPrompts.naming(task, live, forced, config.getRecentDays(),
                    config.getNightlyTopicExamples());
            var request = LlmRequest.builder()
                    .call(LlmCall.NAMING)
                    .tag(allowAssign ? NAMING_TAG : NAMING_NEW_TAG)
                    .prompt(prompt.text())
                    .schema(task.schema())
                    .maxTokens(task.effectiveMaxTokens())
                    .build();
            return NightStep.ask(request, answer -> {
                var done = new HashSet<Integer>();
                var assigned = allowAssign
                        ? assign(before, answer, prompt.topicOfId(), byTopic, byGroup, done)
                        : NightStep.done(new Assigned(before, List.of()));
                return assigned.then(after -> named(after, answer, allowAssign, byTopic, byGroup, done));
            }, () -> NightStep.done(failed(before, byGroup.keySet())));
        }

        /** {@code before}, with {@code groups} left in Other by a failed call. */
        private static Named failed(Named before, Iterable<Integer> groups) {
            var decided = new Decisions(before);
            groups.forEach(group -> decided.put(group, TopicAlgorithm.OTHER, Result.OTHER_FAILED, null));
            return decided.named();
        }

        /** The naming answer's new topics, then the second call over the rejected groups, then the groups left out. */
        private NightStep<Named> named(Assigned assigned, JsonNode answer, boolean allowAssign,
                Map<Integer, LiveTopic> byTopic, Map<Integer, NightGroup> byGroup, Set<Integer> assignedGroups) {
            var decided = new Decisions(assigned.named());
            var done = new HashSet<>(assignedGroups);
            var live = new HashMap<String, Integer>();
            Arrays.stream(state.live()).forEach(t -> live.put(AuthorApplier.nameKey(state.topic(t).getName()), t));
            var groups = new LinkedHashMap<String, NewGroup>();
            for (var entry : NightJson.objects(answer == null ? null : answer.get("new"))) {
                var name = TopicText.name(text(entry.get("name")));
                var ids = new LinkedHashSet<Integer>();
                for (var id : NightJson.integers(entry.get("clusters"))) {
                    if (id != null && byGroup.containsKey(id) && !done.contains(id)) {
                        ids.add(id);
                    }
                }
                if (ids.isEmpty() || name.isEmpty() || AuthorApplier.isCatchAllName(name)) {
                    continue;
                }
                done.addAll(ids);
                var key = AuthorApplier.nameKey(name);
                if (live.containsKey(key)) {
                    for (int id : ids) {
                        if (allowAssign) {
                            decided.put(id, live.get(key), Result.SAME_NAME, live.get(key));
                        } else {
                            decided.put(id, TopicAlgorithm.OTHER, Result.OTHER_NAME, null);
                        }
                    }
                    continue;
                }
                var group = groups.get(key);
                if (group == null) {
                    groups.put(key, new NewGroup(name, text(entry.get("definition")), new ArrayList<>(ids)));
                } else {
                    group.groups().addAll(ids);
                }
            }
            var largestFirst = new ArrayList<>(groups.values());
            largestFirst.sort(Comparator.comparingLong(
                    group -> -group.groups().stream().mapToLong(id -> byGroup.get(id).size()).sum()));
            for (var group : largestFirst) {
                born(decided, group, byTopic, byGroup);
            }
            var rejected = assigned.rejected();
            var again = rejected.isEmpty() ? NightStep.done(decided.named()) : name(decided.named(), rejected, false);
            return again.map(after -> {
                var left = new Decisions(after);
                for (int id : byGroup.keySet()) {
                    if (!left.contains(id)) {
                        left.put(id, TopicAlgorithm.OTHER, Result.OTHER_NAME, null);
                    }
                }
                return left.named();
            });
        }

        /** A new topic from the answer: born, replacing the weakest on a full list, or its groups known Other. */
        private void born(Decisions decided, NewGroup group, Map<Integer, LiveTopic> byTopic,
                Map<Integer, NightGroup> byGroup) {
            // A plain loop in list order, as Python's sum: DoubleStream.sum compensates and can differ in the last bit.
            double share = 0;
            for (int id : group.groups()) {
                share += byGroup.get(id).share();
            }
            if (share < config.getNightlyBigShare()) {
                for (int id : group.groups()) {
                    decided.put(id, TopicAlgorithm.OTHER, Result.OTHER_SMALL, null);
                }
                return;
            }
            if (state.liveCount() >= maxTopics) {
                Integer lowest = null;
                for (int t : state.live()) {
                    if (state.topic(t).getBorn().equals(state.getNight())) {
                        continue;
                    }
                    if (lowest == null || share(byTopic, t, groupShares, decided.outcomes()) < share(byTopic,
                            lowest, groupShares, decided.outcomes())) {
                        lowest = t;
                    }
                }
                if (lowest == null || share(byTopic, lowest, groupShares, decided.outcomes()) >= share) {
                    for (int id : group.groups()) {
                        decided.put(id, TopicAlgorithm.OTHER, Result.OTHER_FULL, null);
                    }
                    return;
                }
                state.retire(lowest, RetiredReason.REPLACED);
            }
            int born = state.born(TopicText.name(group.name()),
                    TopicText.oneLine(group.definition(), TopicPrompts.MAX_DEFINITION_LENGTH));
            for (int id : group.groups()) {
                decided.put(id, born, Result.BORN, born);
            }
        }

        /**
         * The answer's assigns to a live topic, one per group, through one batched verify; a group not verified is
         * rejected. No assign, no verify call. Adds the groups it read to {@code done} before it asks.
         */
        private NightStep<Assigned> assign(Named before, JsonNode answer, Map<Integer, Integer> topicOfId,
                Map<Integer, LiveTopic> byTopic, Map<Integer, NightGroup> byGroup, Set<Integer> done) {
            var pairs = new ArrayList<int[]>();
            var prompts = new ArrayList<TopicPrompts.VerifyPair>();
            for (var entry : NightJson.objects(answer == null ? null : answer.get("assign"))) {
                var group = NightJson.integer(entry.get("cluster"));
                var promptId = NightJson.integer(entry.get("topic"));
                var topic = promptId == null ? null : topicOfId.get(promptId);
                if (group != null && byGroup.containsKey(group) && !done.contains(group) && topic != null
                        && state.topic(topic).isLive()) {
                    pairs.add(new int[]{group, topic});
                    prompts.add(TopicPrompts.VerifyPair.builder().topic(byTopic.get(topic)).group(byGroup.get(group))
                            .build());
                    done.add(group);
                }
            }
            if (pairs.isEmpty()) {
                return NightStep.done(new Assigned(before, List.of()));
            }
            return Verifier.ask(VERIFY_TAG, prompts, tasks.verify(), config, answers -> {
                var decided = new Decisions(before);
                var rejected = new ArrayList<NightGroup>();
                for (int n = 0; n < pairs.size(); n++) {
                    int group = pairs.get(n)[0];
                    int topic = pairs.get(n)[1];
                    if (answers.getOrDefault(n, false)) {
                        decided.put(group, topic, Result.ASSIGNED, topic);
                    } else {
                        rejected.add(byGroup.get(group));
                    }
                }
                return NightStep.done(new Assigned(decided.named(), List.copyOf(rejected)));
            }, () -> NightStep.done(
                    new Assigned(failed(before, pairs.stream().map(pair -> pair[0]).toList()), List.of())));
        }
    }

    /** A string field, Python-stripped; empty when it is missing or not a string. */
    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? TopicText.pythonStrip(node.asText()) : "";
    }

    /**
     * A live topic's share of the window for a replacement: its share as it stood at the naming call, plus the shares of the
     * groups given to it tonight.
     */
    private static double share(Map<Integer, LiveTopic> topics, int topic, Map<Integer, Double> groupShares,
            List<Outcome> outcomes) {
        var live = topics.get(topic);
        double share = live == null ? 0 : live.share();
        for (var outcome : outcomes) {
            if ((outcome.result() == Result.ASSIGNED || outcome.result() == Result.SAME_NAME)
                    && outcome.topic() == topic) {
                share += groupShares.get(outcome.group());
            }
        }
        return share;
    }

    private record NewGroup(String name, String definition, List<Integer> groups) {
    }

    enum Result {
        BORN,
        ASSIGNED,
        SAME_NAME,
        OTHER_LOOSE,
        OTHER_FULL,
        /** Named new, but under {@code nightlyBigShare}. */
        OTHER_SMALL,
        OTHER_NAME,
        /** Its call failed: Other tonight, not known Other, so a candidate again on the next night. */
        OTHER_FAILED
    }

    /** What happened to one candidate; {@code topic} is the index of the topic it got. */
    @Builder(toBuilder = true)
    record Outcome(int group, @NonNull Result result, Integer topic) {
    }
}
