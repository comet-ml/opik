package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.NightState.RetiredReason;
import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Step g of the nightly update: applies the revise answer to the night's state, as the stand's {@code apply_revise}
 * (v4), in this order: {@code assign} (the group is a candidate, the topic is live, the group not mapped yet); on review
 * nights {@code merge} (both live and distinct: the dropped topic's mapped groups and prototypes go to the kept one, the
 * dropped one is retired), {@code retire} (its mapped groups go to Other; a topic with a 2% share or more, overall or
 * in the last 7 days, is kept: not in the stand) and {@code redefine} (the definition is
 * replaced, the name kept); then {@code new} (catch-all names skipped; the groups must be candidates and unmapped;
 * skipped when the list holds {@code maxTopics}); then {@code other}. Topic ids in the answer are the prompt's ids.
 *
 * <p>Entries off the schema are skipped, as the stand's lenient reads do; an answer that is not a JSON object is
 * {@code qwen_bad_answer}. The group map keeps insertion order (a Python dict's): its order decides the order of the
 * prototypes added in step j.
 */
@UtilityClass
public class ReviseApplier {

    /** A group mapped to Other in the group map. */
    public static final int OTHER = -1;

    /**
     * @param promptTopics the revise prompt's topics: prompt id to the topic's index in the night's list
     * @param candidates   the candidate groups' ids
     * @return the group map: candidate group to topic index or {@link #OTHER}, in insertion order
     */
    public static LinkedHashMap<Integer, Integer> apply(@NonNull NightState state, @NonNull JsonNode answer,
            @NonNull List<TopicPrompts.PromptTopic> promptTopics, @NonNull Set<Integer> candidates, boolean review,
            int maxTopics) {
        if (!answer.isObject()) {
            throw new TopicsRunException(Code.QWEN_BAD_ANSWER, "the revise answer is not a JSON object", null);
        }
        Map<Integer, Integer> byPromptId = promptTopics.stream()
                .collect(Collectors.toMap(TopicPrompts.PromptTopic::id, TopicPrompts.PromptTopic::topic));
        var map = new LinkedHashMap<Integer, Integer>();
        for (var entry : objects(answer.get("assign"))) {
            var group = integer(entry.get("cluster"));
            var topic = topic(entry.get("topic"), byPromptId, state);
            if (group != null && candidates.contains(group) && topic != null && !map.containsKey(group)) {
                map.put(group, topic);
            }
        }
        if (review) {
            for (var pair : arrays(answer.get("merge"))) {
                if (pair.size() != 2) {
                    continue;
                }
                var keepId = integer(pair.get(0));
                var dropId = integer(pair.get(1));
                var keep = topic(pair.get(0), byPromptId, state);
                var drop = topic(pair.get(1), byPromptId, state);
                if (keep == null || drop == null || keepId.equals(dropId)) {
                    continue;
                }
                map.replaceAll((group, topic) -> topic.equals(drop) ? keep : topic);
                var merged = new ArrayList<>(state.topic(keep).getPrototypes());
                merged.addAll(state.topic(drop).getPrototypes());
                state.topic(keep).setPrototypes(merged);
                state.retire(drop, RetiredReason.MERGED);
            }
            for (var id : integers(answer.get("retire"))) {
                var topic = id == null ? null : live(byPromptId.get(id), state);
                // Not in the stand: a topic with a real share is never retired by a review (merge or redefine it).
                // Qwen retired both topics of a project holding 96% of its traffic; the stand never review-retired one.
                if (topic != null && holdsTraffic(promptTopics, id)) {
                    continue;
                }
                if (topic != null) {
                    map.replaceAll((group, mapped) -> mapped.equals(topic) ? OTHER : mapped);
                    state.retire(topic, RetiredReason.REVIEW);
                }
            }
            for (var entry : objects(answer.get("redefine"))) {
                var topic = topic(entry.get("id"), byPromptId, state);
                var definition = entry.get("definition");
                if (topic != null && definition != null && definition.isTextual() && !definition.asText().isEmpty()) {
                    state.topic(topic).setDefinition(TopicText.pythonStrip(definition.asText()));
                }
            }
        }
        for (var entry : objects(answer.get("new"))) {
            var name = entry.get("name");
            // A catch-all or blank name is no topic.
            if (name != null && name.isTextual() && (AuthorApplier.isCatchAll(name.asText())
                    || TopicText.pythonStrip(name.asText()).isEmpty())) {
                continue;
            }
            var groups = new ArrayList<Integer>();
            for (var group : integers(entry.get("clusters"))) {
                if (group != null && candidates.contains(group) && !map.containsKey(group)) {
                    groups.add(group);
                }
            }
            var definition = entry.get("definition");
            if (groups.isEmpty() || state.liveCount() >= maxTopics || name == null || !name.isTextual()
                    || definition == null || !definition.isTextual()) {
                continue;
            }
            int born = state.born(TopicText.name(name.asText()), TopicText.pythonStrip(definition.asText()));
            groups.forEach(group -> map.put(group, born));
        }
        for (var group : integers(answer.get("other"))) {
            if (group != null && candidates.contains(group)) {
                map.putIfAbsent(group, OTHER);
            }
        }
        return map;
    }

    /** The live topic a prompt id names, or null. */
    private static Integer topic(JsonNode id, Map<Integer, Integer> byPromptId, NightState state) {
        var value = integer(id);
        return value == null ? null : live(byPromptId.get(value), state);
    }

    private static Integer live(Integer index, NightState state) {
        return index != null && state.topic(index).isLive() ? index : null;
    }

    static Integer integer(JsonNode node) {
        return node != null && node.isIntegralNumber() && node.canConvertToInt() ? node.intValue() : null;
    }

    /** The array's items as integers (null for an item that is not one); empty when it is not an array. */
    static List<Integer> integers(JsonNode node) {
        var out = new ArrayList<Integer>();
        if (node != null && node.isArray()) {
            node.forEach(item -> out.add(integer(item)));
        }
        return out;
    }

    static List<JsonNode> objects(JsonNode node) {
        var out = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isObject()) {
                    out.add(item);
                }
            });
        }
        return out;
    }

    private static List<JsonNode> arrays(JsonNode node) {
        var out = new ArrayList<JsonNode>();
        if (node != null && node.isArray()) {
            node.forEach(item -> {
                if (item.isArray()) {
                    out.add(item);
                }
            });
        }
        return out;
    }

    /** True when the prompt topic's share, overall or in the last 7 days, is at least the candidates' 2% bar. */
    private static boolean holdsTraffic(List<TopicPrompts.PromptTopic> promptTopics, int id) {
        return promptTopics.stream().filter(topic -> topic.id() == id).anyMatch(
                topic -> Math.max(topic.share(), topic.recentShare()) >= NightlyUpdate.BIG_SHARE);
    }
}
