package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.mapping;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toUnmodifiableList;

/**
 * The LLM's author answer: {@code {topics: [{name, definition}], assignment: [{cluster, topic}]}}, both keys required,
 * {@code topic} the topic's name or "-1" for Other. A name, not an index: the model miscounted indexes and moved the
 * last topics' names one cluster over. Read as topics with cluster lists: each assignment's cluster goes to the first
 * topic of that name, compared as written, then by {@link AuthorApplier#nameKey}; the first mention of a cluster wins.
 * A cluster assigned "-1" or a name off the list claims nothing: the applier sends every unclaimed group to Other.
 * {@code unmatched} counts the clusters given a name off the list. Unknown keys are ignored; anything else off the
 * schema, text included, is {@code llm_bad_answer}.
 */
@Builder(toBuilder = true)
public record AuthorAnswer(@NonNull List<AuthorTopic> topics, int unmatched) {

    /** The {@code topic} of a cluster that has no topic. */
    static final String OTHER = "-1";

    public AuthorAnswer(@NonNull List<AuthorTopic> topics) {
        this(topics, 0);
    }

    /**
     * The {@code output} of a generate call with the author task's schema ({@code topics/tasks/author.yaml}):
     * opik-inference parses and checks it against the schema, so it is the object itself. Text or null is not parsed:
     * it is {@code llm_bad_answer}.
     */
    public static AuthorAnswer parse(JsonNode node) {
        if (node == null || !node.isObject()) {
            throw bad("the answer is not a JSON object", null);
        }
        var topicsNode = node.get("topics");
        var assignmentNode = node.get("assignment");
        if (topicsNode == null || !topicsNode.isArray() || assignmentNode == null || !assignmentNode.isArray()) {
            throw bad("the answer needs the arrays 'topics' and 'assignment'", null);
        }
        var named = topicsNode.valueStream().map(AuthorAnswer::named).toList();
        var byName = new HashMap<String, Integer>();
        var byKey = new HashMap<String, Integer>();
        for (int t = 0; t < named.size(); t++) {
            byName.putIfAbsent(named.get(t).name(), t);
            byKey.putIfAbsent(AuthorApplier.nameKey(named.get(t).name()), t);
        }
        Map<Integer, String> firstMention = assignmentNode.valueStream().map(AuthorAnswer::assignment)
                .collect(toMap(Assignment::cluster, Assignment::topic, (first, later) -> first, LinkedHashMap::new));
        var topicOf = new LinkedHashMap<Integer, Integer>();
        int unmatched = 0;
        for (var entry : firstMention.entrySet()) {
            var topic = byName.getOrDefault(entry.getValue(), byKey.get(AuthorApplier.nameKey(entry.getValue())));
            if (topic != null) {
                topicOf.put(entry.getKey(), topic);
            } else if (!OTHER.equals(entry.getValue().strip())) {
                unmatched++;
            }
        }
        Map<Integer, List<Integer>> claimed = topicOf.entrySet().stream()
                .collect(groupingBy(Map.Entry::getValue, mapping(Map.Entry::getKey, toUnmodifiableList())));
        return AuthorAnswer.builder()
                .unmatched(unmatched)
                .topics(IntStream.range(0, named.size())
                        .mapToObj(t -> AuthorTopic.builder()
                                .name(named.get(t).name())
                                .definition(named.get(t).definition())
                                .clusters(claimed.getOrDefault(t, List.of()))
                                .build())
                        .toList())
                .build();
    }

    private record Named(String name, String definition) {
    }

    private record Assignment(int cluster, String topic) {
    }

    private static Named named(JsonNode topic) {
        var name = topic.get("name");
        var definition = topic.get("definition");
        if (!topic.isObject() || name == null || !name.isTextual() || definition == null || !definition.isTextual()) {
            throw bad("every topic needs a string 'name' and a string 'definition'", null);
        }
        return new Named(name.asText(), definition.asText());
    }

    private static Assignment assignment(JsonNode entry) {
        var cluster = entry.get("cluster");
        var topic = entry.get("topic");
        if (!entry.isObject() || cluster == null || !cluster.isIntegralNumber() || !cluster.canConvertToInt()
                || topic == null || !topic.isTextual()) {
            throw bad("every assignment needs an integer 'cluster' and a string 'topic'", null);
        }
        return new Assignment(cluster.intValue(), topic.asText());
    }

    private static TopicsRunException bad(String message, Throwable cause) {
        return new TopicsRunException(RunError.LLM_BAD_ANSWER, message, cause);
    }
}
