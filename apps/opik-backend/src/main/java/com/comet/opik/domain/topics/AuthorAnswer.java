package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicProject.RunError;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

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
 * {@code topic} the topic's index or -1 for Other. Read as topics with cluster lists: each assignment's cluster goes to
 * its topic's list, the first mention of a cluster wins. A cluster assigned -1 or an index off the list claims nothing:
 * the applier sends every unclaimed group to Other. Unknown keys are ignored; anything else off the schema, text
 * included, is {@code llm_bad_answer}.
 */
@Builder(toBuilder = true)
public record AuthorAnswer(@NonNull List<AuthorTopic> topics) {

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
        Map<Integer, Integer> firstMention = assignmentNode.valueStream().map(AuthorAnswer::assignment)
                .collect(toMap(Assignment::cluster, Assignment::topic, (first, later) -> first, LinkedHashMap::new));
        Map<Integer, List<Integer>> claimed = firstMention.entrySet().stream()
                .filter(entry -> entry.getValue() >= 0 && entry.getValue() < named.size())
                .collect(groupingBy(Map.Entry::getValue, mapping(Map.Entry::getKey, toUnmodifiableList())));
        return AuthorAnswer.builder()
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

    private record Assignment(int cluster, int topic) {
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
                || topic == null || !topic.isIntegralNumber() || !topic.canConvertToInt()) {
            throw bad("every assignment needs an integer 'cluster' and an integer 'topic'", null);
        }
        return new Assignment(cluster.intValue(), topic.intValue());
    }

    private static TopicsRunException bad(String message, Throwable cause) {
        return new TopicsRunException(RunError.LLM_BAD_ANSWER, message, cause);
    }
}
