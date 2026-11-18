package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;

/**
 * Qwen's author answer: {@code {topics: [{name, definition}], assignment: [{cluster, topic}]}}, both keys required,
 * {@code topic} the topic's index or -1 for Other. Read as topics with cluster lists: each assignment's cluster goes to
 * its topic's list, the first mention of a cluster wins. A cluster assigned -1 or an index off the list claims nothing:
 * the applier sends every unclaimed group to Other. Unknown keys are ignored; anything else off the schema, text
 * included, is {@code qwen_bad_answer}.
 */
@Builder
public record AuthorAnswer(@NonNull List<Topic> topics) {

    /**
     * The {@code output} of a generate call with {@link TopicPrompts#AUTHOR_SCHEMA}: opik-inference parses and checks
     * it against the schema, so it is the object itself. Text or null is not parsed: it is {@code qwen_bad_answer}.
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
        var names = new ArrayList<String>();
        var definitions = new ArrayList<String>();
        for (var topic : topicsNode) {
            var name = topic.get("name");
            var definition = topic.get("definition");
            if (!topic.isObject() || name == null || !name.isTextual() || definition == null
                    || !definition.isTextual()) {
                throw bad("every topic needs a string 'name' and a string 'definition'", null);
            }
            names.add(name.asText());
            definitions.add(definition.asText());
        }
        var clusters = new ArrayList<List<Integer>>();
        names.forEach(name -> clusters.add(new ArrayList<>()));
        var seen = new HashSet<Integer>();
        for (var entry : assignmentNode) {
            var cluster = entry.get("cluster");
            var topic = entry.get("topic");
            if (!entry.isObject() || cluster == null || !cluster.isIntegralNumber() || !cluster.canConvertToInt()
                    || topic == null || !topic.isIntegralNumber() || !topic.canConvertToInt()) {
                throw bad("every assignment needs an integer 'cluster' and an integer 'topic'", null);
            }
            if (!seen.add(cluster.intValue())) {
                continue;
            }
            int t = topic.intValue();
            if (t >= 0 && t < names.size()) {
                clusters.get(t).add(cluster.intValue());
            }
        }
        var topics = new ArrayList<Topic>(names.size());
        for (int t = 0; t < names.size(); t++) {
            topics.add(new Topic(names.get(t), definitions.get(t), List.copyOf(clusters.get(t))));
        }
        return new AuthorAnswer(List.copyOf(topics));
    }

    private static TopicsRunException bad(String message, Throwable cause) {
        return new TopicsRunException(Code.QWEN_BAD_ANSWER, message, cause);
    }

    public record Topic(@NonNull String name, @NonNull String definition, @NonNull List<Integer> clusters) {
    }
}
