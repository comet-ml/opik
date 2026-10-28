package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.GroupSummaries.GroupSummary;
import com.comet.opik.domain.topics.NightState.RetiredReason;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
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
 * Steps h and i of the nightly update after the verify call, as the stand's {@code verify_and_force} and
 * {@code force_grouped} (v7n). For each candidate, largest first, a pair that was not verified is removed from the group
 * map. Every big candidate that ended without a topic (rejected, Other, or not mapped) is forced: below tightness
 * {@value #MIN_TIGHTNESS} it is known Other, else it joins one grouped naming call for the night.
 * <p>
 * The naming call shows the live topics (those born tonight included) and may assign a group to one, put same-kind
 * groups into one new topic, or leave a group in Other. An assign passes one batched verify; a rejected group goes to
 * a second call that offers new and other only. A new topic's groups are de-duplicated; a blank or catch-all name is
 * skipped; a name equal to a live topic's (by {@link AuthorApplier#nameKey}) joins that topic, and equal new names are
 * one topic. New topics are created largest first. On a full list, the topic not born tonight with the lowest 7-day
 * share is retired when that share is below the sum of the new topic's groups' 7-day shares, else its groups are known
 * Other. A forced group the answers leave out is known Other.
 */
@Slf4j
@UtilityClass
public class ForcedBirth {

    static final double MIN_TIGHTNESS = 0.80;
    static final String NAMING_TAG = "naming";
    static final String NAMING_NEW_TAG = "naming_new";
    static final String VERIFY_TAG = "verify_naming";
    static final int NAMING_TOKENS = 2500;

    /**
     * @param ordered     the candidates largest first (ties by group id)
     * @param big         the big candidates' group ids
     * @param topics      the live topics as the verify prompt showed them, by topic index
     * @param shownTopics the live topics as a prompt shows them now, read again for each naming call
     * @param map         the group map, changed in place
     */
    public static List<Outcome> apply(@NonNull NightState state, @NonNull List<GroupSummary> ordered,
            @NonNull Set<Integer> big, @NonNull Map<Integer, TopicPrompts.PromptTopic> topics,
            @NonNull Verifier.Verified verified, @NonNull Map<Integer, Integer> map,
            @NonNull Supplier<List<TopicPrompts.PromptTopic>> shownTopics, int maxTopics, @NonNull NightLlm llm) {
        var outcomes = new ArrayList<Outcome>();
        var forced = new ArrayList<GroupSummary>();
        for (var candidate : ordered) {
            int group = candidate.group();
            var topic = map.get(group);
            if (topic != null && topic != ReviseApplier.OTHER && topics.containsKey(topic)) {
                if (verified.verified(group)) {
                    continue;
                }
                map.remove(group);
            } else if (topic != null && topic != ReviseApplier.OTHER) {
                continue;
            }
            if (!big.contains(group)) {
                continue;
            }
            if (candidate.tightness() < MIN_TIGHTNESS) {
                map.put(group, ReviseApplier.OTHER);
                outcomes.add(new Outcome(group, Result.OTHER_LOOSE, null));
                continue;
            }
            forced.add(candidate);
        }
        if (!forced.isEmpty()) {
            name(state, forced, map, shownTopics, maxTopics, llm, true, outcomes);
        }
        return outcomes;
    }

    private static void name(NightState state, List<GroupSummary> forced, Map<Integer, Integer> map,
            Supplier<List<TopicPrompts.PromptTopic>> shownTopics, int maxTopics, NightLlm llm, boolean allowAssign,
            List<Outcome> outcomes) {
        var shown = shownTopics.get();
        var byPromptId = new HashMap<Integer, Integer>();
        var byTopic = new HashMap<Integer, TopicPrompts.PromptTopic>();
        shown.forEach(topic -> {
            byPromptId.put(topic.id(), topic.topic());
            byTopic.put(topic.topic(), topic);
        });
        var byGroup = new LinkedHashMap<Integer, GroupSummary>();
        forced.forEach(group -> byGroup.put(group.group(), group));
        var answer = allowAssign
                ? llm.generate(NAMING_TAG, TopicPrompts.namingGroup(shown, forced, true),
                        TopicPrompts.NAMING_GROUP_SCHEMA, NAMING_TOKENS)
                : llm.generate(NAMING_NEW_TAG, TopicPrompts.namingGroup(shown, forced, false),
                        TopicPrompts.NAMING_GROUP_NEW_SCHEMA, NAMING_TOKENS);
        var done = new HashSet<Integer>();
        var rejected = new ArrayList<GroupSummary>();
        if (allowAssign) {
            assign(state, answer, byPromptId, byTopic, byGroup, map, llm, done, rejected, outcomes);
        }
        var live = new HashMap<String, Integer>();
        state.live().forEach(t -> live.put(AuthorApplier.nameKey(state.topic(t).getName()), t));
        var groups = new LinkedHashMap<String, NewGroup>();
        for (var entry : ReviseApplier.objects(answer == null ? null : answer.get("new"))) {
            var name = text(entry.get("name"));
            var ids = new LinkedHashSet<Integer>();
            for (var id : ReviseApplier.integers(entry.get("clusters"))) {
                if (id != null && byGroup.containsKey(id) && !done.contains(id)) {
                    ids.add(id);
                }
            }
            if (ids.isEmpty() || name.isEmpty() || AuthorApplier.isCatchAll(name)) {
                continue;
            }
            done.addAll(ids);
            var key = AuthorApplier.nameKey(name);
            if (live.containsKey(key)) {
                for (int id : ids) {
                    map.put(id, live.get(key));
                    outcomes.add(new Outcome(id, Result.SAME_NAME, live.get(key)));
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
            // A plain loop in list order, as Python's sum: DoubleStream.sum compensates and can differ in the last bit.
            double recentShare = 0;
            for (int id : group.groups()) {
                recentShare += byGroup.get(id).recentShare();
            }
            if (state.liveCount() >= maxTopics) {
                Integer lowest = null;
                for (int t : state.live()) {
                    if (state.topic(t).getBorn().equals(state.getNight())) {
                        continue;
                    }
                    if (lowest == null || recentShare(byTopic, t) < recentShare(byTopic, lowest)) {
                        lowest = t;
                    }
                }
                if (lowest == null || recentShare(byTopic, lowest) >= recentShare) {
                    for (int id : group.groups()) {
                        map.put(id, ReviseApplier.OTHER);
                        outcomes.add(new Outcome(id, Result.OTHER_FULL, null));
                    }
                    continue;
                }
                state.retire(lowest, RetiredReason.REPLACED);
            }
            int born = state.born(TopicText.name(group.name()), group.definition());
            for (int id : group.groups()) {
                map.put(id, born);
                outcomes.add(new Outcome(id, Result.BORN, born));
            }
        }
        if (!rejected.isEmpty()) {
            name(state, rejected, map, shownTopics, maxTopics, llm, false, outcomes);
        }
        for (int id : byGroup.keySet()) {
            if (!map.containsKey(id)) {
                map.put(id, ReviseApplier.OTHER);
                outcomes.add(new Outcome(id, Result.OTHER_NAME, null));
            }
        }
    }

    /** The answer's assigns to a live topic, one per group, through one batched verify; a group not verified is rejected. */
    private static void assign(NightState state, JsonNode answer, Map<Integer, Integer> byPromptId,
            Map<Integer, TopicPrompts.PromptTopic> byTopic, Map<Integer, GroupSummary> byGroup,
            Map<Integer, Integer> map, NightLlm llm, Set<Integer> done, List<GroupSummary> rejected,
            List<Outcome> outcomes) {
        var pairs = new ArrayList<int[]>();
        var prompts = new ArrayList<TopicPrompts.VerifyPair>();
        for (var entry : ReviseApplier.objects(answer == null ? null : answer.get("assign"))) {
            var group = ReviseApplier.integer(entry.get("cluster"));
            var promptId = ReviseApplier.integer(entry.get("topic"));
            var topic = promptId == null ? null : byPromptId.get(promptId);
            if (group != null && byGroup.containsKey(group) && !done.contains(group) && topic != null
                    && state.topic(topic).isLive()) {
                pairs.add(new int[]{group, topic});
                prompts.add(new TopicPrompts.VerifyPair(byTopic.get(topic), byGroup.get(group)));
                done.add(group);
            }
        }
        if (pairs.isEmpty()) {
            return;
        }
        var answers = Verifier.ask(VERIFY_TAG, prompts, llm);
        for (int n = 0; n < pairs.size(); n++) {
            int group = pairs.get(n)[0];
            int topic = pairs.get(n)[1];
            if (answers != null && answers.getOrDefault(n, false)) {
                map.put(group, topic);
                outcomes.add(new Outcome(group, Result.ASSIGNED, topic));
            } else {
                rejected.add(byGroup.get(group));
            }
        }
    }

    /** A string field, Python-stripped; empty when it is missing or not a string. */
    private static String text(JsonNode node) {
        return node != null && node.isTextual() ? TopicText.pythonStrip(node.asText()) : "";
    }

    private static double recentShare(Map<Integer, TopicPrompts.PromptTopic> topics, int topic) {
        var shown = topics.get(topic);
        return shown == null ? 0 : shown.recentShare();
    }

    private record NewGroup(String name, String definition, List<Integer> groups) {
    }

    public enum Result {
        BORN,
        ASSIGNED,
        SAME_NAME,
        OTHER_LOOSE,
        OTHER_FULL,
        OTHER_NAME
    }

    /** What happened to one big candidate left without a topic; {@code topic} is the index of the topic it got. */
    @Builder(toBuilder = true)
    public record Outcome(int group, @NonNull Result result, Integer topic) {
    }
}
