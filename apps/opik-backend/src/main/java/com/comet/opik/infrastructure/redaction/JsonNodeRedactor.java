package com.comet.opik.infrastructure.redaction;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.NonNull;
import lombok.experimental.UtilityClass;

import java.util.Map;

/**
 * Applies a rule set directly to a parsed tree, for the paths that cannot go through the serializer.
 * <p>
 * Streamed responses are built and written on a scheduler thread, so the thread-local the writer interceptor
 * sets is not in force there. The stream therefore redacts its items explicitly instead, using the decision
 * captured on the request thread.
 * <p>
 * Nodes are rewritten in place: each item is freshly parsed for the stream, so nothing else holds a reference.
 */
@UtilityClass
public class JsonNodeRedactor {

    public JsonNode redact(JsonNode node, @NonNull RedactionRules rules) {
        if (node == null || rules.isEmpty()) {
            return node;
        }

        return rewrite(node, rules);
    }

    private JsonNode rewrite(JsonNode node, RedactionRules rules) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // Field names are collected first: replacing a value while iterating the live view would fail.
            for (String name : object.propertyStream().map(Map.Entry::getKey).toList()) {
                object.set(name, rewrite(object.get(name), rules));
            }
            return object;
        }

        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, rewrite(array.get(i), rules));
            }
            return array;
        }

        if (node.isTextual()) {
            return TextNode.valueOf(rules.apply(node.textValue()));
        }

        return node;
    }
}
