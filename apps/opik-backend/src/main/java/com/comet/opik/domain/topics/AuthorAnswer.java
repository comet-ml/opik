package com.comet.opik.domain.topics;

import com.comet.opik.domain.topics.TopicsRunException.Code;
import com.comet.opik.infrastructure.inference.InferenceJson;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Builder;
import lombok.NonNull;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Qwen's author answer: {@code {topics: [{name, definition, clusters: [int]}], other: [int]}}, both keys required.
 * Unknown keys are ignored; anything else off the schema is {@code qwen_bad_answer}.
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
        var otherNode = node.get("other");
        if (topicsNode == null || !topicsNode.isArray() || otherNode == null || !otherNode.isArray()) {
            throw bad("the answer needs the arrays 'topics' and 'other'", null);
        }
        var topics = new ArrayList<Topic>(topicsNode.size());
        for (var topic : topicsNode) {
            var name = topic.get("name");
            var definition = topic.get("definition");
            var clusters = topic.get("clusters");
            if (!topic.isObject() || name == null || !name.isTextual() || definition == null
                    || !definition.isTextual() || clusters == null || !clusters.isArray()) {
                throw bad("every topic needs a string 'name', a string 'definition' and an array 'clusters'", null);
            }
            topics.add(new Topic(name.asText(), definition.asText(), integers(clusters)));
        }
        return new AuthorAnswer(List.copyOf(topics), integers(otherNode));
    }

    private static List<Integer> integers(JsonNode array) {
        var out = new ArrayList<Integer>(array.size());
        for (var item : array) {
            if (!item.isIntegralNumber() || !item.canConvertToInt()) {
                throw bad("cluster ids must be integers", null);
            }
            out.add(item.intValue());
        }
        return List.copyOf(out);
    }

    private static TopicsRunException bad(String message, Throwable cause) {
        return new TopicsRunException(Code.QWEN_BAD_ANSWER, message, cause);
    }

    public record Topic(@NonNull String name, @NonNull String definition, @NonNull List<Integer> clusters) {
    }
}
