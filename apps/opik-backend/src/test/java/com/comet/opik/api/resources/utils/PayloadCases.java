package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.slim.SlimJson;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import lombok.Builder;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import uk.co.jemos.podam.api.PodamFactory;

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
     * Deep enough to nest raw JSON well inside the row, shallow enough for the recursive comparison in the span and
     * trace assertions, which recurses per level and overflows the test thread's stack near the 1000-level limit.
     * {@code JsonRowValuesTest} covers that limit itself.
     */
    private static final int NESTED_ARRAYS_DEPTH = 100;

    public static Stream<JsonNode> payloads() {
        int max = SlimJson.MAX_STRING_LENGTH;
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
                nestedArrays(NESTED_ARRAYS_DEPTH));
    }

    /** A payload with strings past the slim limit, and its slim copy: each such string cut to the limit, then marked. */
    @Builder(toBuilder = true)
    public record SlimCase(JsonNode payload, JsonNode slim) {
    }

    public static SlimCase slimCase() {
        int max = SlimJson.MAX_STRING_LENGTH;
        String key = randomText(10);
        String nestedKey = randomText(10);
        String arrayKey = randomText(10);
        String longText = randomText(max + RandomUtils.secure().randomInt(1, max));
        String longItem = randomText(max * 2);
        String shortItem = randomText(10);

        var payload = JsonNodeFactory.instance.objectNode().put(key, longText);
        payload.putObject(nestedKey).putArray(arrayKey).add(longItem).add(shortItem);
        var slim = JsonNodeFactory.instance.objectNode().put(key, cut(longText));
        slim.putObject(nestedKey).putArray(arrayKey).add(cut(longItem)).add(shortItem);
        return SlimCase.builder().payload(payload).slim(slim).build();
    }

    /** A trace in {@code projectName} carrying {@code payload} as its input and output. */
    public static Trace trace(PodamFactory factory, String projectName, JsonNode payload) {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .usage(null)
                .feedbackScores(null)
                .input(payload)
                .output(payload)
                .build();
    }

    /** A root span in {@code projectName} carrying {@code payload} as its input and output. */
    public static Span span(PodamFactory factory, String projectName, JsonNode payload) {
        return factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .parentSpanId(null)
                .feedbackScores(null)
                .input(payload)
                .output(payload)
                .build();
    }

    private static String cut(String text) {
        return text.substring(0, SlimJson.MAX_STRING_LENGTH) + SlimJson.TRUNCATION_SUFFIX;
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
