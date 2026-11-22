package com.comet.opik.utils.slim;

import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.TruncationUtils;
import com.comet.opik.utils.slim.SlimJson.JsonWithSlim;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

public class SlimJsonTest {

    private static final int MAX = SlimJson.MAX_STRING_LENGTH;

    private static String randomText(int length) {
        return RandomStringUtils.secure().next(length);
    }

    private static String randomAscii(int length) {
        return RandomStringUtils.secure().nextAlphanumeric(length);
    }

    private static JsonNode parse(String json) {
        return JsonUtils.getJsonNodeFromString(json);
    }

    public static Stream<JsonNode> parsedPayloads() {
        var emojiAtBoundary = "%s\uD83D\uDE00%s".formatted("a".repeat(MAX - 1), randomAscii(10));
        return Stream.of(
                parse("{}"),
                parse("[]"),
                parse("null"),
                parse("true"),
                parse(String.valueOf(RandomUtils.secure().randomInt())),
                parse("\"%s\"".formatted(randomAscii(10))),
                parse("\"%s\"".formatted(randomAscii(MAX))),
                parse("\"%s\"".formatted(randomAscii(MAX + 1))),
                parse("{\"short\":\"%s\",\"n\":%d}".formatted(randomAscii(20), RandomUtils.secure().randomInt())),
                parse(JsonUtils.writeValueAsString(Map.of("text", randomText(MAX + 500)))),
                parse(JsonUtils.writeValueAsString(Map.of("text", randomText(MAX - 1)))),
                parse(JsonUtils.writeValueAsString(Map.of("emoji", emojiAtBoundary))),
                parse(JsonUtils.writeValueAsString(Map.of(
                        "escapes", "quote \" backslash \\ newline \n tab \t control \u0001 %s"
                                .formatted(randomAscii(MAX))))),
                parse("""
                        {"messages":[{"role":"user","content":"%s"},{"role":"assistant","content":"%s"}],
                         "model":"%s","usage":{"prompt_tokens":%d,"completion_tokens":%d},
                         "tools":[],"meta":{"nested":{"deeper":["%s",null,true,false,1.5,-0.0,1e300]}}}
                        """.formatted(randomAscii(5000), randomAscii(30), randomAscii(8),
                        RandomUtils.secure().randomInt(), RandomUtils.secure().randomInt(),
                        randomAscii(MAX + 1))),
                parse("""
                        {"numbers":[0,-1,2147483648,9223372036854775808,123456789012345678901234567890,
                         0.1,1.0E10,1.7976931348623157E308,4.9E-324,NaN,Infinity,-Infinity],
                         "long":"%s"}
                        """.formatted(randomAscii(MAX * 3))),
                parse("[%s]".formatted(String.join(",",
                        Stream.generate(() -> "\"%s\"".formatted(randomAscii(MAX + 7))).limit(50).toList()))),
                parse(JsonUtils.writeValueAsString(Map.of("output", randomText(2_000_000)))),
                parse(JsonUtils.writeValueAsString(largeTree(3000, 0))),
                parse(JsonUtils.writeValueAsString(largeTree(2200, 10))));
    }

    private static List<Map<String, Object>> largeTree(int records, int longEvery) {
        return IntStream.range(0, records)
                .mapToObj(index -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", randomText(40));
                    row.put("attributes", Map.of("a", randomAscii(20), "b", index, "c", randomText(20)));
                    row.put("tags", List.of(randomAscii(8), randomAscii(8), randomAscii(8)));
                    row.put("children", List.of(Map.of("k", randomAscii(12), "v", index * 0.5)));
                    if (longEvery > 0 && index % longEvery == 0) {
                        row.put("body", randomText(3000));
                    }
                    return row;
                })
                .toList();
    }

    public static Stream<JsonNode> builtPayloads() {
        var factory = JsonNodeFactory.instance;
        return Stream.of(
                factory.objectNode()
                        .put("decimal", new BigDecimal("1E+10"))
                        .put("text", randomAscii(MAX + 1)),
                factory.objectNode()
                        .put("float", 0.1f)
                        .put("text", randomAscii(10)),
                factory.objectNode()
                        .put("binary", randomAscii(MAX * 2).getBytes())
                        .put("text", randomAscii(10)),
                factory.objectNode()
                        .put("long_decimal", new BigDecimal("1".repeat(1001)))
                        .put("text", randomAscii(MAX + 1)),
                factory.objectNode()
                        .put("big_integer", new BigInteger("123456789012345678901234567890"))
                        .put("short", (short) 7)
                        .put("double", 2.5d)
                        .put("text", randomAscii(MAX + 1)),
                factory.arrayNode()
                        .add(factory.objectNode().putNull("missing_value"))
                        .add(randomAscii(MAX + 1)));
    }

    /** Lengths that straddle the cut, plus short and long ones. */
    private static int randomLength(Random random) {
        return switch (random.nextInt(4)) {
            case 0 -> random.nextInt(20);
            case 1 -> MAX - 3 + random.nextInt(7);
            case 2 -> MAX * 2 + random.nextInt(MAX * 3);
            default -> random.nextInt(MAX);
        };
    }

    private static String randomString(Random random) {
        String alphabet = "abcXYZ019 \"\\\n\t\u0001\u001f/<>&\u00e9\u6f22\u2028\uD83D\uDE00";
        int length = randomLength(random);
        var text = new StringBuilder(length);
        while (text.length() < length) {
            int start = random.nextInt(alphabet.length() - 1);
            // Keep the surrogate pair whole: the cut may still split it, as it does today.
            text.append(Character.isHighSurrogate(alphabet.charAt(start))
                    ? alphabet.substring(start, start + 2)
                    : alphabet.substring(start, start + 1));
        }
        return text.toString();
    }

    private static JsonNode randomNode(Random random, int depth) {
        var factory = JsonNodeFactory.instance;
        int kind = depth >= 4 ? 2 + random.nextInt(5) : random.nextInt(7);
        return switch (kind) {
            case 0 -> {
                ObjectNode object = factory.objectNode();
                int size = random.nextInt(6);
                for (int i = 0; i < size; i++) {
                    object.set(random.nextBoolean() ? randomString(random) : "k%d".formatted(i),
                            randomNode(random, depth + 1));
                }
                yield object;
            }
            case 1 -> {
                var array = factory.arrayNode();
                int size = random.nextInt(6);
                for (int i = 0; i < size; i++) {
                    array.add(randomNode(random, depth + 1));
                }
                yield array;
            }
            case 2, 3 -> factory.textNode(randomString(random));
            case 4 -> random.nextBoolean()
                    ? factory.numberNode(random.nextLong())
                    : factory.numberNode(random.nextDouble() * Math.pow(10, random.nextInt(40) - 20));
            case 5 -> factory.booleanNode(random.nextBoolean());
            default -> factory.nullNode();
        };
    }

    /** Random trees, both built and parsed back from their text, as request bodies are. */
    public static Stream<JsonNode> randomPayloads() {
        long seed = RandomUtils.secure().randomLong();
        var random = new Random(seed);
        return Stream.generate(() -> randomNode(random, 0))
                .limit(200)
                .flatMap(node -> Stream.of(node, JsonUtils.getJsonNodeFromString(node.toString())));
    }

    static Stream<JsonNode> allPayloads() {
        return Stream.of(parsedPayloads(), builtPayloads(), randomPayloads()).flatMap(Function.identity());
    }

    /** What main writes: the full JSON reparsed, then cut node by node. */
    private static JsonWithSlim expectedFromReparse(JsonNode node) {
        String json = TruncationUtils.toJsonString(node);
        return JsonWithSlim.builder()
                .json(json)
                .slim(JsonUtils.writeValueAsString(TruncationUtils.createSlimJson(node)))
                .build();
    }

    @Nested
    @DisplayName("toStrings")
    class ToStrings {

        @ParameterizedTest
        @MethodSource("com.comet.opik.utils.slim.SlimJsonTest#allPayloads")
        @DisplayName("writes the JSON and the slim copy main writes")
        void matchesReparse(JsonNode node) {
            JsonWithSlim actual = SlimJson.toStrings(node);

            assertThat(actual).isEqualTo(expectedFromReparse(node));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.utils.slim.SlimJsonTest#parsedPayloads")
        @DisplayName("matches reparsing the full JSON string for parsed payloads")
        void matchesStringReparseForParsedPayloads(JsonNode node) {
            JsonWithSlim actual = SlimJson.toStrings(node);

            String json = TruncationUtils.toJsonString(node);
            var expected = JsonWithSlim.builder().json(json).slim(TruncationUtils.createSlimJsonString(json)).build();
            assertThat(actual).isEqualTo(expected);
        }

        @Test
        @DisplayName("returns empty json and slim for null")
        void emptyForNull() {
            assertThat(SlimJson.toStrings(null)).isEqualTo(JsonWithSlim.builder().json("").slim("").build());
        }

        @Test
        @DisplayName("reuses the full json as slim when no string exceeds the max length")
        void reusesJsonWhenNothingIsCut() {
            ObjectNode node = JsonNodeFactory.instance.objectNode()
                    .put("text", randomAscii(MAX))
                    .put("number", 1);

            JsonWithSlim actual = SlimJson.toStrings(node);

            assertThat(actual.slim()).isSameAs(actual.json());
        }
    }

    @Nested
    @DisplayName("toRaw")
    class ToRaw {

        @ParameterizedTest
        @MethodSource("com.comet.opik.utils.slim.SlimJsonTest#allPayloads")
        @DisplayName("writes the strings toStrings returns, json then slim as a row does")
        void matchesStrings(JsonNode node) {
            var raw = SlimJson.toRaw(node);
            String json = JsonUtils.writeValueAsString(raw.json());
            String slim = JsonUtils.writeValueAsString(raw.slim());

            assertThat(JsonWithSlim.builder().json(json).slim(slim).build()).isEqualTo(SlimJson.toStrings(node));
        }

        @ParameterizedTest
        @MethodSource("com.comet.opik.utils.slim.SlimJsonTest#allPayloads")
        @DisplayName("writes the same strings when slim is written before json")
        void matchesStringsSlimFirst(JsonNode node) {
            var raw = SlimJson.toRaw(node);
            String slim = JsonUtils.writeValueAsString(raw.slim());
            String json = JsonUtils.writeValueAsString(raw.json());

            assertThat(JsonWithSlim.builder().json(json).slim(slim).build()).isEqualTo(SlimJson.toStrings(node));
        }
    }

    @Nested
    @DisplayName("needsSlimming")
    class NeedsSlimming {

        @Test
        @DisplayName("is false when the longest string value is exactly the max length")
        void falseAtMaxLength() {
            var node = JsonUtils.getJsonNodeFromString("{\"a\":[1,{\"b\":\"%s\"}]}".formatted(randomAscii(MAX)));

            assertThat(SlimJson.needsSlimming(node)).isFalse();
        }

        @Test
        @DisplayName("is true for one string value past the max length, however deep")
        void trueForDeepLongString() {
            var node = JsonUtils.getJsonNodeFromString("{\"a\":[1,{\"b\":[null,\"%s\"]}],\"c\":\"%s\"}"
                    .formatted(randomAscii(MAX + 1), randomAscii(10)));

            assertThat(SlimJson.needsSlimming(node)).isTrue();
        }

        @Test
        @DisplayName("ignores field names, which the slim copy keeps whole")
        void ignoresLongFieldNames() {
            var node = JsonNodeFactory.instance.objectNode().put(randomAscii(MAX * 2), randomAscii(10));

            assertThat(SlimJson.needsSlimming(node)).isFalse();
        }
    }
}
