package com.comet.opik.api.resources.utils;

import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
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

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
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
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        return Stream.of(
                JsonUtils.getJsonNodeFromString("{}"),
                JsonUtils.getJsonNodeFromString("[]"),
                JsonUtils.valueToTree(Map.of(
                        randomAlphanumeric(10), randomAlphanumeric(max + 1),
                        randomAlphanumeric(10), Map.of(randomAlphanumeric(10), randomAlphanumeric(20)))),
                JsonNodeFactory.instance.arrayNode()
                        .add(randomAlphanumeric(max * 2))
                        .add(RandomUtils.secure().randomInt())
                        .add(JsonNodeFactory.instance.objectNode().putNull(randomAlphanumeric(10))),
                JsonUtils.valueToTree(Map.of(randomAlphanumeric(10),
                        "quote \" backslash \\ newline \n tab \t control \u0001 unicode é😀 %s"
                                .formatted(randomAlphanumeric(max)))),
                TextNode.valueOf(randomAlphanumeric(max + 1)),
                IntNode.valueOf(RandomUtils.secure().randomInt()),
                NullNode.getInstance(),
                nestedArrays(NESTED_ARRAYS_DEPTH),
                parse(JsonUtils.writeValueAsString(Map.of("text", randomUnicode(max + 500)))),
                parse(JsonUtils.writeValueAsString(Map.of("emoji", emojiAtBoundary()))),
                parse("[%s]".formatted(String.join(",",
                        Stream.generate(() -> "\"%s\"".formatted(randomAlphanumeric(max + 7))).limit(50).toList()))));
    }

    /** A string whose emoji is split by the slim limit: the cut keeps only its high surrogate. */
    private static String emojiAtBoundary() {
        return "%s\uD83D\uDE00%s".formatted("a".repeat(TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH - 1),
                randomAlphanumeric(10));
    }

    /** A payload with strings past the slim limit, and its slim copy: each such string cut to the limit, then marked. */
    @Builder(toBuilder = true)
    public record SlimCase(JsonNode payload, JsonNode slim) {
    }

    public static SlimCase slimCase() {
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        String key = randomAlphanumeric(10);
        String nestedKey = randomAlphanumeric(10);
        String arrayKey = randomAlphanumeric(10);
        String longText = randomAlphanumeric(max + RandomUtils.secure().randomInt(1, max));
        String longItem = randomAlphanumeric(max * 2);
        String shortItem = randomAlphanumeric(10);

        var payload = JsonNodeFactory.instance.objectNode().put(key, longText);
        payload.putObject(nestedKey).putArray(arrayKey).add(longItem).add(shortItem);
        var slim = JsonNodeFactory.instance.objectNode().put(key, cut(longText));
        slim.putObject(nestedKey).putArray(arrayKey).add(cut(longItem)).add(shortItem);
        return SlimCase.builder().payload(payload).slim(slim).build();
    }

    /**
     * Payloads parsed from JSON text: scalars, strings around the slim limit, an emoji split by the limit, escapes,
     * edge-case numbers, and large documents with and without long strings, in ASCII and in arbitrary Unicode.
     */
    public static Stream<JsonNode> parsedPayloads() {
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        return Stream.of(
                parse("{}"),
                parse("[]"),
                parse("null"),
                parse("true"),
                parse(String.valueOf(RandomUtils.secure().randomInt())),
                parse("\"%s\"".formatted(randomAlphanumeric(10))),
                parse("\"%s\"".formatted(randomAlphanumeric(max))),
                parse("\"%s\"".formatted(randomAlphanumeric(max + 1))),
                parse("{\"short\":\"%s\",\"n\":%d}".formatted(randomAlphanumeric(20),
                        RandomUtils.secure().randomInt())),
                parse(JsonUtils.writeValueAsString(Map.of("text", randomUnicode(max + 500)))),
                parse(JsonUtils.writeValueAsString(Map.of("text", randomUnicode(max - 1)))),
                parse(JsonUtils.writeValueAsString(Map.of("emoji", emojiAtBoundary()))),
                parse(JsonUtils.writeValueAsString(Map.of(
                        "escapes", "quote \" backslash \\ newline \n tab \t control \u0001 %s"
                                .formatted(randomAlphanumeric(max))))),
                parse("""
                        {"messages":[{"role":"user","content":"%s"},{"role":"assistant","content":"%s"}],
                         "model":"%s","usage":{"prompt_tokens":%d,"completion_tokens":%d},
                         "tools":[],"meta":{"nested":{"deeper":["%s",null,true,false,1.5,-0.0,1e300]}}}
                        """.formatted(randomAlphanumeric(5000), randomAlphanumeric(30), randomAlphanumeric(8),
                        RandomUtils.secure().randomInt(), RandomUtils.secure().randomInt(),
                        randomAlphanumeric(max + 1))),
                parse("""
                        {"numbers":[0,-1,2147483648,9223372036854775808,123456789012345678901234567890,
                         0.1,1.0E10,1.7976931348623157E308,4.9E-324,NaN,Infinity,-Infinity],
                         "long":"%s"}
                        """.formatted(randomAlphanumeric(max * 3))),
                parse("[%s]".formatted(String.join(",",
                        Stream.generate(() -> "\"%s\"".formatted(randomAlphanumeric(max + 7))).limit(50).toList()))),
                parse(JsonUtils.writeValueAsString(Map.of("output", randomUnicode(2_000_000)))),
                parse(JsonUtils.writeValueAsString(largeTree(3000, 0))),
                parse(JsonUtils.writeValueAsString(largeTree(2200, 10))));
    }

    private static List<Map<String, Object>> largeTree(int records, int longEvery) {
        return IntStream.range(0, records)
                .mapToObj(index -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", randomUnicode(40));
                    row.put("attributes", Map.of("a", randomAlphanumeric(20), "b", index, "c", randomUnicode(20)));
                    row.put("tags", List.of(randomAlphanumeric(8), randomAlphanumeric(8), randomAlphanumeric(8)));
                    row.put("children", List.of(Map.of("k", randomAlphanumeric(12), "v", index * 0.5)));
                    if (longEvery > 0 && index % longEvery == 0) {
                        row.put("body", randomUnicode(3000));
                    }
                    return row;
                })
                .toList();
    }

    /** Payloads built node by node, with the numeric and binary node types parsing never produces. */
    public static Stream<JsonNode> builtPayloads() {
        int max = TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH;
        var factory = JsonNodeFactory.instance;
        return Stream.of(
                factory.objectNode()
                        .put("decimal", new BigDecimal("1E+10"))
                        .put("text", randomAlphanumeric(max + 1)),
                factory.objectNode()
                        .put("float", 0.1f)
                        .put("text", randomAlphanumeric(10)),
                factory.objectNode()
                        .put("binary", randomAlphanumeric(max * 2).getBytes())
                        .put("text", randomAlphanumeric(10)),
                factory.objectNode()
                        .put("long_decimal", new BigDecimal("1".repeat(1001)))
                        .put("text", randomAlphanumeric(max + 1)),
                factory.objectNode()
                        .put("big_integer", new BigInteger("123456789012345678901234567890"))
                        .put("short", (short) 7)
                        .put("double", 2.5d)
                        .put("text", randomAlphanumeric(max + 1)),
                factory.arrayNode()
                        .add(factory.objectNode().putNull("missing_value"))
                        .add(randomAlphanumeric(max + 1)));
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

    /** Letters and digits only: text whose JSON form is the text itself. */
    public static String randomAlphanumeric(int length) {
        return RandomStringUtils.secure().nextAlphanumeric(length);
    }

    /** Arbitrary Unicode, including surrogate pairs and characters JSON escapes. */
    public static String randomUnicode(int length) {
        return RandomStringUtils.secure().next(length);
    }

    private static JsonNode parse(String json) {
        return JsonUtils.getJsonNodeFromString(json);
    }

    private static String cut(String text) {
        return text.substring(0, TruncationUtils.DEFAULT_SLIM_STRING_MAX_LENGTH) + TruncationUtils.TRUNCATION_SUFFIX;
    }

    /** {@code depth} arrays, each holding the next, around one short string. */
    public static JsonNode nestedArrays(int depth) {
        JsonNode node = JsonNodeFactory.instance.arrayNode().add(randomAlphanumeric(10));
        for (int level = 1; level < depth; level++) {
            node = JsonNodeFactory.instance.arrayNode().add(node);
        }
        return node;
    }
}
