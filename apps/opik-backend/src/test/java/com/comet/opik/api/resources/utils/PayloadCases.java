package com.comet.opik.api.resources.utils;

import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;

import java.util.Map;
import java.util.stream.Stream;

/**
 * Trace and span payload shapes for the tests that check the batch and single-create paths store the same
 * {@code input} and {@code output}: the batch path embeds objects and arrays in the row raw, and writes the rest as
 * text.
 */
@UtilityClass
public class PayloadCases {

    /**
     * The levels a batch request puts above a payload: the request object, its items array and the item object.
     */
    private static final int BATCH_ENVELOPE_DEPTH = 3;

    public static Stream<JsonNode> payloads() {
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        return Stream.of(
                JsonUtils.getJsonNodeFromString("{}"),
                JsonUtils.getJsonNodeFromString("[]"),
                JsonUtils.valueToTree(Map.of(
                        randomText(10), randomText(max + 1),
                        randomText(10), Map.of(randomText(10), randomText(20)))),
                JsonNodeFactory.instance.arrayNode()
                        .add(randomText(max * 2))
                        .add(RandomUtils.secure().randomInt())
                        .add(JsonNodeFactory.instance.objectNode().putNull(randomText(10))),
                JsonUtils.valueToTree(Map.of(randomText(10),
                        "quote \" backslash \\ newline \n tab \t control \u0001 unicode é😀 %s"
                                .formatted(randomText(max)))),
                TextNode.valueOf(randomText(max + 1)),
                IntNode.valueOf(RandomUtils.secure().randomInt()),
                NullNode.getInstance(),
                nestedArrays(StreamReadConstraints.DEFAULT_MAX_DEPTH - BATCH_ENVELOPE_DEPTH));
    }

    private static String randomText(int length) {
        return RandomStringUtils.secure().nextAlphanumeric(length);
    }

    private static JsonNode nestedArrays(int depth) {
        JsonNode node = JsonNodeFactory.instance.arrayNode().add(randomText(10));
        for (int level = 1; level < depth; level++) {
            node = JsonNodeFactory.instance.arrayNode().add(node);
        }
        return node;
    }
}
