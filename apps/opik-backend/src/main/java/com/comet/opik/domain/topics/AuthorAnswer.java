package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Qwen's author answer: {@code {topics: [{name, definition}], assignment: [{cluster, topic}]}}, both keys required,
 * {@code topic} the topic's index or -1 for Other. Read as topics with cluster lists: each assignment's cluster goes to
 * its topic's list, the first mention of a cluster wins, -1 or an index off the list is Other. The {@code other} list
 * holds the clusters assigned -1; clusters the answer leaves out are not listed (the applier sends unclaimed groups to
 * Other anyway). Unknown keys are ignored; anything else off the schema is {@code qwen_bad_answer}.
 */
@Builder
public record AuthorAnswer(@NonNull List<Topic> topics, @NonNull List<Integer> other) {

    /** The stand's parse rule: the first {@code {...}} block (first brace to last), then JSON. */
    private static final Pattern OBJECT = Pattern.compile("\\{.*}", Pattern.DOTALL);

    /** The {@code output} of a generate call: the object itself, or text holding it. */
    public static AuthorAnswer parse(JsonNode output) {
        var node = output;
        if (output != null && output.isTextual()) {
            var text = output.asText().strip();
            var matcher = OBJECT.matcher(text);
            try {
                node = InferenceJson.MAPPER.readTree(matcher.find() ? matcher.group() : text);
            } catch (IOException exception) {
                throw bad("the answer is not JSON", exception);
            }
        }
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
        var other = new ArrayList<Integer>();
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
            } else {
                other.add(cluster.intValue());
            }
        }
        var topics = new ArrayList<Topic>(names.size());
        for (int t = 0; t < names.size(); t++) {
            topics.add(new Topic(names.get(t), definitions.get(t), List.copyOf(clusters.get(t))));
        }
        return new AuthorAnswer(List.copyOf(topics), List.copyOf(other));
    }

    private static TopicsRunException bad(String message, Throwable cause) {
        return new TopicsRunException(Code.QWEN_BAD_ANSWER, message, cause);
    }

    public record Topic(@NonNull String name, @NonNull String definition, @NonNull List<Integer> clusters) {
    }
}
