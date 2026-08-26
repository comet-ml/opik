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

    public JsonNode redact(JsonNode node, @NonNull RedactionRules rules, @NonNull Class<?> itemType) {
        if (node == null || rules.isEmpty()) {
            return node;
        }

        return rewrite(node, rules, itemType);
    }

    /**
     * @param itemType the streamed DTO, or {@code null} once below its own properties. Exemptions apply only at
     *                 the top level, mirroring the {@code BeanSerializerModifier}, which reaches declared
     *                 properties and nothing else - a caller-chosen map key nested in a payload is not structure.
     */
    private JsonNode rewrite(JsonNode node, RedactionRules rules, Class<?> itemType) {
        if (node.isObject()) {
            ObjectNode object = (ObjectNode) node;
            // Field names are collected first: replacing a value while iterating the live view would fail.
            for (String name : object.propertyStream().map(Map.Entry::getKey).toList()) {
                if (itemType != null && RedactionModule.isExemptProperty(itemType, name)) {
                    continue;
                }
                object.set(name, rewrite(object.get(name), rules, null));
            }
            return object;
        }

        if (node.isArray()) {
            ArrayNode array = (ArrayNode) node;
            for (int i = 0; i < array.size(); i++) {
                array.set(i, rewrite(array.get(i), rules, itemType));
            }
            return array;
        }

        if (node.isTextual()) {
            return TextNode.valueOf(rules.apply(node.textValue()));
        }

        return node;
    }
}
