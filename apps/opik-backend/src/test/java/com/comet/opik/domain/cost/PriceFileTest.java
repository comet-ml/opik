package com.comet.opik.domain.cost;

import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.MathContext;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every price of the shipped price file, as the backend bills it. The file is replaced by an automated LiteLLM sync,
 * so these checks also guard every sync: a price field the backend ignores, or a rate it bills wrong, fails here.
 * <p>
 * These tests guard what every span is billed: add a check for each cost path and each bug found, and never remove one.
 * When a fix shows a check expected the wrong cost, correct that check in the same change and say why.
 */
class PriceFileTest {

    // Prices of other modes, which the backend reads only for models of that mode.
    private static final Map<String, String> PRICED_IN_MODE = Map.of(
            "output_cost_per_video_per_second", "video_generation",
            "input_cost_per_character", "audio_speech");

    // Prices the backend doesn't bill, with the reason. A price a sync adds fails the coverage check until it's billed or
    // listed here.
    private static final Map<Pattern, String> NOT_PRICED = Map.of(
            Pattern.compile(".+_(batches|flex|priority|ultrafast|balanced|decisions)(_above_\\d+k?_tokens)?"),
            "service-tier and processing-mode prices: span usage doesn't say which one served the request",
            Pattern.compile("input_cost_per_token_cache_hit"),
            "DeepSeek's cache-hit price, which its entries also publish as cache_read_input_token_cost",
            Pattern.compile("explicit_cache_read_input_token_cost"),
            "Qwen's explicit-cache read price: no usage format tells explicit-cache reads from implicit ones, so they "
                    + "bill at cache_read_input_token_cost",
            Pattern.compile("(input|output)_dbu_cost_per_token"),
            "Databricks DBU units; the entries also publish dollar prices",
            Pattern.compile("(input|output)_cost_per_(image|video)_token|cache_read_input_(image|audio)_token_cost"
                    + "|cache_creation_input_audio_token_cost|citation_cost_per_token"),
            "image and video tokens, cached audio and Perplexity's citations: no usage format reads those counts, so "
                    + "they bill at the text rates",
            Pattern.compile("(input_|output_)?cost_per_(image|pixel|second|character)(_[\\w.]+)?"
                    + "|input_cost_per_(video|audio)_per_second(_\\w+)?"),
            "per-image, per-pixel, per-second and per-character prices: spans report tokens, and video and speech "
                    + "models are billed by their own calculators",
            Pattern.compile("search_context_cost_per_query|google_maps_grounding_cost_per_query|input_cost_per_query"
                    + "|input_cost_per_request|code_interpreter_cost_per_session|guardrail_cost_per_unit"
                    + "|annotation_cost_per_page|ocr_cost_per_page|ocr_cost_per_credit|input_cost_per_text_message"),
            "per-request, per-message and per-tool prices: spans carry no count of searches, tool sessions, pages, "
                    + "messages or requests");

    // Token rates a provider's usage format has no count for, with the reason.
    private static final Map<Format, Map<Pattern, String>> NOT_PRICED_IN_FORMAT = Map.of(
            Format.GEMINI, Map.of(
                    Pattern.compile("cache_creation_input_token_cost(_above_\\d+k?_tokens)?"),
                    "Gemini's usage has no cache-write count: explicit caches are created by a separate call",
                    Pattern.compile("(input|output)_cost_per_audio_token"),
                    "Gemini reports audio tokens only in its per-modality list, which no usage format reads, so they "
                            + "bill at the text rates"),
            Format.BEDROCK_CONVERSE, Map.of(
                    Pattern.compile("(input|output)_cost_per_audio_token"),
                    "Bedrock's usage has no audio count, so Nova's audio tokens bill at the text rates"));

    private static final List<PricedEntry> ENTRIES = pricedEntries();

    /** An entry of the price file as CostService prices it: the last one under its lookup key, or Opik's override. */
    record PricedEntry(String name, String provider, Map<String, JsonNode> fields) {

        BigDecimal price(String field) {
            var value = fields.get(field);
            return value != null && (value.isNumber() || value.isTextual())
                    ? new BigDecimal(value.asText())
                    : BigDecimal.ZERO;
        }

        boolean pricedByTokens() {
            String mode = Optional.ofNullable(fields.get("mode")).map(JsonNode::asText).orElse("");
            if (PRICED_IN_MODE.entrySet().stream()
                    .anyMatch(price -> mode.equals(price.getValue()) && price(price.getKey()).signum() > 0)) {
                return false;
            }
            return Stream.of("input_cost_per_token", "output_cost_per_token", "cache_creation_input_token_cost",
                    "cache_read_input_token_cost").anyMatch(field -> price(field).signum() > 0);
        }

        /** The prompt-size tiers LiteLLM detects, from the input rates: threshold to the label in the field names. */
        TreeMap<Integer, String> tiers() {
            var tiers = new TreeMap<Integer, String>();
            fields.keySet().forEach(field -> {
                Matcher matcher = ModelPriceTable.PROMPT_TIER_RATE.matcher(field);
                if (matcher.matches() && matcher.group(1).equals("input_cost_per_token")
                        && price(field).signum() > 0) {
                    tiers.put(Integer.parseInt(matcher.group(2)) * (matcher.group(3).isEmpty() ? 1 : 1000),
                            matcher.group(2) + matcher.group(3));
                }
            });
            return tiers;
        }
    }

    private static List<PricedEntry> pricedEntries() {
        var entriesByKey = upstreamEntries();
        directOverrides().forEach((name, fields) -> key(name, fields)
                .ifPresent(key -> entriesByKey.put(key, new PricedEntry(name, provider(fields), fields))));
        return List.copyOf(entriesByKey.values());
    }

    /** The upstream rows by the key ModelPriceTable prices them under, before Opik's overrides replace some. */
    private static Map<String, PricedEntry> upstreamEntries() {
        var entriesByKey = new LinkedHashMap<String, PricedEntry>();
        // ModelPriceTable merges OpenRouter's file into LiteLLM's by model name before keying the prices.
        var upstream = readEntries(ModelPriceTable.MODEL_PRICES_FILE);
        upstream.putAll(readEntries(ModelPriceTable.MODEL_PRICES_OPENROUTER_FILE));
        // A per-image row such as 1024-x-1024/gpt-image-1.5 shares its model's key, and never replaces its token rates.
        upstream.forEach((name, fields) -> key(name, fields).ifPresent(key -> entriesByKey.merge(key,
                new PricedEntry(name, provider(fields), fields),
                (kept, entry) -> kept.pricedByTokens() && !entry.pricedByTokens() ? kept : entry)));
        return entriesByKey;
    }

    // An alias_of override prices like its target, so it adds nothing to check.
    private static Map<String, Map<String, JsonNode>> directOverrides() {
        var overrides = readEntries(ModelPriceTable.MODEL_PRICES_OVERRIDES_FILE);
        overrides.values().removeIf(fields -> fields.containsKey("alias_of"));
        return overrides;
    }

    private static Map<String, Map<String, JsonNode>> readEntries(String file) {
        try {
            Map<String, Map<String, JsonNode>> entries = new LinkedHashMap<>(JsonUtils.readJsonFile(file,
                    new TypeReference<Map<String, Map<String, JsonNode>>>() {
                    }));
            entries.remove("sample_spec");
            return entries;
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    // ModelPriceTable keys a price by the name after its first "/" and the provider, and skips Bedrock's legacy paths.
    private static Optional<String> key(String name, Map<String, JsonNode> fields) {
        String provider = provider(fields);
        if (provider.isBlank() || (provider.equals("bedrock") && name.contains("/"))) {
            return Optional.empty();
        }
        return Optional.of((name.contains("/") ? StringUtils.substringAfter(name, "/") : name) + "/" + provider);
    }

    private static String provider(Map<String, JsonNode> fields) {
        return Optional.ofNullable(fields.get("litellm_provider")).map(JsonNode::asText).orElse("");
    }

    static Stream<String> providers() {
        return ENTRIES.stream().filter(PricedEntry::pricedByTokens).map(PricedEntry::provider).distinct().sorted();
    }

    static Stream<String> providersWithOneHourWrites() {
        return providers().filter(provider -> Format.of(provider).billedRates()
                .contains("cache_creation_input_token_cost_above_1hr"));
    }

    private static Stream<PricedEntry> entriesPricedByTokens(String provider) {
        return ENTRIES.stream().filter(entry -> entry.provider().equals(provider) && entry.pricedByTokens());
    }

    // A small prompt, and each tier threshold: exactly at it, which stays below it, and just above it.
    private static Stream<Integer> promptSizes(PricedEntry entry) {
        return Stream.concat(Stream.of(2_000),
                entry.tiers().keySet().stream().flatMap(threshold -> Stream.of(threshold, threshold + 1_000)));
    }

    private static BigDecimal billed(PricedEntry entry, Tokens tokens) {
        return CostService.calculateCost(entry.name(), entry.provider(),
                tokens.usage(Format.of(entry.provider())), null);
    }

    @Nested
    class Coverage {

        @Test
        void everyPriceIsBilledOrListedAsNotPriced() {
            var unaccounted = new TreeMap<String, TreeSet<String>>();
            Stream.of(ModelPriceTable.MODEL_PRICES_FILE, ModelPriceTable.MODEL_PRICES_OPENROUTER_FILE,
                    ModelPriceTable.MODEL_PRICES_OVERRIDES_FILE)
                    .flatMap(file -> readEntries(file).values().stream())
                    .forEach(fields -> {
                        String provider = Optional.ofNullable(fields.get("litellm_provider")).map(JsonNode::asText)
                                .orElse("");
                        var format = Format.of(provider);
                        fields.forEach((field, value) -> {
                            if (field.contains("cost") && isPrice(value) && !accountedFor(field, fields, format)) {
                                unaccounted.computeIfAbsent("%s in the %s format".formatted(field, format),
                                        unused -> new TreeSet<>()).add(provider);
                            }
                        });
                    });

            assertThat(unaccounted)
                    .as("price fields their provider's usage format doesn't bill and NOT_PRICED doesn't list, "
                            + "with the providers")
                    .isEmpty();
        }

        /**
         * A direct override replaces its model's whole upstream row, so a rate upstream bills that it leaves out is lost.
         */
        @Test
        void anOverrideSetsEveryRateTheUpstreamRowItReplacesBills() {
            var upstream = upstreamEntries();
            var dropped = new TreeSet<String>();
            directOverrides().forEach((name, override) -> key(name, override).map(upstream::get)
                    .ifPresent(replaced -> replaced.fields().forEach((field, value) -> {
                        if (isPrice(value) && billed(field, replaced.fields(), Format.of(replaced.provider()))
                                && !override.containsKey(field)) {
                            dropped.add("%s: %s".formatted(name, field));
                        }
                    })));

            assertThat(dropped)
                    .as("upstream rates the overrides drop: set them in the override, or remove an override upstream "
                            + "now prices right")
                    .isEmpty();
        }

        private static boolean isPrice(JsonNode value) {
            return value.isObject() || (value.isNumber() && value.decimalValue().signum() > 0);
        }

        private static boolean accountedFor(String field, Map<String, JsonNode> fields, Format format) {
            return billed(field, fields, format)
                    || Stream.of(NOT_PRICED, NOT_PRICED_IN_FORMAT.getOrDefault(format, Map.of()))
                            .flatMap(notPriced -> notPriced.keySet().stream())
                            .anyMatch(pattern -> pattern.matcher(field).matches());
        }

        private static boolean billed(String field, Map<String, JsonNode> fields, Format format) {
            String mode = Optional.ofNullable(fields.get("mode")).map(JsonNode::asText).orElse("");
            Matcher tierRate = ModelPriceTable.PROMPT_TIER_RATE.matcher(field);
            String rate = tierRate.matches() ? tierRate.group(1) : field;
            return format.billedRates().contains(rate) || mode.equals(PRICED_IN_MODE.get(field));
        }
    }

    @Nested
    class Rates {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.comet.opik.domain.cost.PriceFileTest#providers")
        void billsEveryTokenRateOfEveryModelAsThePriceFileSays(String provider) {
            var mismatches = entriesPricedByTokens(provider)
                    .flatMap(entry -> promptSizes(entry).map(promptSize -> {
                        var tokens = Tokens.forPrompt(Format.of(provider), promptSize);
                        BigDecimal expected = expectedCost(entry, tokens);
                        BigDecimal actual = billed(entry, tokens);
                        return expected.compareTo(actual) == 0
                                ? null
                                : "%s with a %,d-token prompt: expected %s, billed %s".formatted(entry.name(),
                                        promptSize, expected.toPlainString(), actual.toPlainString());
                    }))
                    .filter(mismatch -> mismatch != null)
                    .toList();

            assertThat(mismatches).as("models of '%s' billed differently from the price file", provider).isEmpty();
        }

        /**
         * The cost from the price file's own fields: the highest tier the whole prompt exceeds, and each rate of that
         * tier, or the base rate when the tier doesn't publish it (LiteLLM's rule). A cache rate that isn't published
         * bills as input. A one-hour write never bills below a five-minute one. Above a tier, a rate it doesn't publish
         * keeps its base ratio to the rate it changes: a one-hour write to the five-minute one, reasoning and audio to
         * their text rate.
         */
        private static BigDecimal expectedCost(PricedEntry entry, Tokens tokens) {
            var tier = Optional.ofNullable(entry.tiers().lowerEntry(Math.toIntExact(tokens.prompt())))
                    .map(Map.Entry::getValue);
            UnaryOperator<String> tiered = field -> tier
                    .map(label -> field + "_above_" + label + "_tokens")
                    .filter(tierField -> entry.price(tierField).signum() > 0)
                    .orElse(field);
            BigDecimal input = entry.price(tiered.apply("input_cost_per_token"));
            BigDecimal output = entry.price(tiered.apply("output_cost_per_token"));
            BigDecimal cacheRead = orElse(entry.price(tiered.apply("cache_read_input_token_cost")), input);
            BigDecimal fiveMinuteWrite = orElse(entry.price(tiered.apply("cache_creation_input_token_cost")), input);
            String oneHourRate = tiered.apply("cache_creation_input_token_cost_above_1hr");
            BigDecimal oneHourWrite = (!oneHourRate.equals("cache_creation_input_token_cost_above_1hr")
                    ? entry.price(oneHourRate)
                    : scaled(entry.price("cache_creation_input_token_cost_above_1hr"),
                            entry.price("cache_creation_input_token_cost"), fiveMinuteWrite))
                    .max(fiveMinuteWrite);
            BigDecimal baseInput = entry.price("input_cost_per_token");
            BigDecimal baseOutput = entry.price("output_cost_per_token");
            BigDecimal reasoning = scaled(entry.price("output_cost_per_reasoning_token"), baseOutput, output);
            BigDecimal audioInput = scaled(entry.price("input_cost_per_audio_token"), baseInput, input);
            BigDecimal audioOutput = scaled(entry.price("output_cost_per_audio_token"), baseOutput, output);

            return switch (Format.of(entry.provider())) {
                case ANTHROPIC, BEDROCK_CONVERSE -> cost(input, tokens.input())
                        .add(cost(cacheRead, tokens.cacheRead()))
                        .add(cost(fiveMinuteWrite, tokens.fiveMinuteWrite()))
                        .add(cost(oneHourWrite, tokens.oneHourWrite()))
                        .add(cost(output, tokens.output()));
                case GEMINI -> cost(input, tokens.input())
                        .add(cost(cacheRead, tokens.cacheRead()))
                        .add(cost(output, tokens.output()))
                        .add(cost(orElse(reasoning, output), tokens.reasoning()));
                // Audio and reasoning bill as text unless the model has a rate for them.
                case OPENAI -> {
                    long audioIn = audioInput.signum() > 0 ? tokens.audioInput() : 0;
                    long audioOut = audioOutput.signum() > 0 ? tokens.audioOutput() : 0;
                    long reasoningTokens = reasoning.signum() > 0 ? tokens.reasoning() : 0;
                    yield cost(input, tokens.input() + tokens.audioInput() - audioIn)
                            .add(cost(audioInput, audioIn))
                            .add(cost(cacheRead, tokens.cacheRead()))
                            .add(cost(fiveMinuteWrite, tokens.fiveMinuteWrite()))
                            .add(cost(oneHourWrite, tokens.oneHourWrite()))
                            .add(cost(output, tokens.output() + tokens.reasoning() + tokens.audioOutput()
                                    - audioOut - reasoningTokens))
                            .add(cost(audioOutput, audioOut))
                            .add(cost(reasoning, reasoningTokens));
                }
            };
        }

        private static BigDecimal orElse(BigDecimal price, BigDecimal fallback) {
            return price.signum() > 0 ? price : fallback;
        }

        private static BigDecimal scaled(BigDecimal baseRate, BigDecimal baseTextRate, BigDecimal tierTextRate) {
            return baseTextRate.signum() > 0 && tierTextRate.compareTo(baseTextRate) != 0
                    ? baseRate.multiply(tierTextRate).divide(baseTextRate, MathContext.DECIMAL64)
                    : baseRate;
        }

        private static BigDecimal cost(BigDecimal price, long tokens) {
            return price.multiply(BigDecimal.valueOf(tokens));
        }
    }

    @Nested
    class Invariants {

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.comet.opik.domain.cost.PriceFileTest#providersWithOneHourWrites")
        void aOneHourCacheWriteNeverBillsBelowAFiveMinuteOne(String provider) {
            var violations = entriesPricedByTokens(provider)
                    .flatMap(entry -> promptSizes(entry).filter(promptSize -> {
                        int writes = promptSize / 2;
                        var fiveMinute = new Tokens(promptSize - writes, 0, writes, 0, 100, 0, 0, 0);
                        var oneHour = new Tokens(promptSize - writes, 0, 0, writes, 100, 0, 0, 0);
                        return billed(entry, oneHour).compareTo(billed(entry, fiveMinute)) < 0;
                    }).map(promptSize -> "%s with a %,d-token prompt".formatted(entry.name(), promptSize)))
                    .toList();

            assertThat(violations).as("one-hour writes billed below five-minute ones").isEmpty();
        }

        @ParameterizedTest(name = "{0}")
        @MethodSource("com.comet.opik.domain.cost.PriceFileTest#providers")
        void moreTokensNeverCostLess(String provider) {
            var format = Format.of(provider);
            var violations = entriesPricedByTokens(provider)
                    .flatMap(entry -> promptSizes(entry).flatMap(promptSize -> {
                        var tokens = Tokens.forPrompt(format, promptSize);
                        BigDecimal cost = billed(entry, tokens);
                        // One more token moves a prompt at a tier threshold above it.
                        return Stream.concat(Stream.of(tokens.withMoreInput(1)), tokens.withEachBucketGrown(format))
                                .filter(more -> billed(entry, more).compareTo(cost) < 0)
                                .map(more -> "%s: %s cost less than %s".formatted(entry.name(), more, tokens));
                    }))
                    .toList();

            assertThat(violations).as("usage billed below smaller usage").isEmpty();
        }
    }

    /** The usage format UsageFormats picks for the provider. */
    enum Format {
        ANTHROPIC,
        BEDROCK_CONVERSE,
        GEMINI,
        OPENAI;

        static Format of(String provider) {
            return switch (provider) {
                case "anthropic", "vertex_ai-anthropic_models" -> ANTHROPIC;
                case "bedrock", "bedrock_converse" -> BEDROCK_CONVERSE;
                case "gemini", "vertex_ai-language-models", "vertex_ai" -> GEMINI;
                default -> OPENAI;
            };
        }

        /** The token rates the format's usage has a count for, besides their prompt-size tiers. */
        Set<String> billedRates() {
            return switch (this) {
                case ANTHROPIC, BEDROCK_CONVERSE -> Set.of("input_cost_per_token", "output_cost_per_token",
                        "cache_read_input_token_cost", "cache_creation_input_token_cost",
                        "cache_creation_input_token_cost_above_1hr");
                case GEMINI -> Set.of("input_cost_per_token", "output_cost_per_token", "cache_read_input_token_cost",
                        "output_cost_per_reasoning_token");
                case OPENAI -> Set.of("input_cost_per_token", "output_cost_per_token", "cache_read_input_token_cost",
                        "cache_creation_input_token_cost", "cache_creation_input_token_cost_above_1hr",
                        "output_cost_per_reasoning_token", "input_cost_per_audio_token", "output_cost_per_audio_token");
            };
        }
    }

    /**
     * A call's tokens by what they bill as. The prompt is the uncached input, the cache and the audio input; the
     * completion is the output, the reasoning and the audio output.
     */
    record Tokens(long input, long cacheRead, long fiveMinuteWrite, long oneHourWrite, long output, long reasoning,
            long audioInput, long audioOutput) {

        /** A prompt of the given size, split over every bucket the format reports. */
        static Tokens forPrompt(Format format, int prompt) {
            long cacheRead = prompt / 5;
            long fiveMinuteWrite = format == Format.GEMINI ? 0 : prompt / 10;
            long oneHourWrite = format == Format.GEMINI ? 0 : prompt / 20;
            long reasoning = format == Format.OPENAI || format == Format.GEMINI ? 300 : 0;
            long audioInput = format == Format.OPENAI ? 40 : 0;
            long audioOutput = format == Format.OPENAI ? 20 : 0;
            return new Tokens(prompt - cacheRead - fiveMinuteWrite - oneHourWrite - audioInput, cacheRead,
                    fiveMinuteWrite, oneHourWrite, 700, reasoning, audioInput, audioOutput);
        }

        long prompt() {
            return input + cacheRead + fiveMinuteWrite + oneHourWrite + audioInput;
        }

        Tokens withMoreInput(long more) {
            return new Tokens(input + more, cacheRead, fiveMinuteWrite, oneHourWrite, output, reasoning, audioInput,
                    audioOutput);
        }

        Stream<Tokens> withEachBucketGrown(Format format) {
            var forPrompt = forPrompt(format, 2_000);
            return Stream.of(
                    withMoreInput(100),
                    forPrompt.cacheRead == 0
                            ? null
                            : new Tokens(input, cacheRead + 100, fiveMinuteWrite,
                                    oneHourWrite, output, reasoning, audioInput, audioOutput),
                    forPrompt.fiveMinuteWrite == 0
                            ? null
                            : new Tokens(input, cacheRead, fiveMinuteWrite + 100,
                                    oneHourWrite, output, reasoning, audioInput, audioOutput),
                    forPrompt.oneHourWrite == 0
                            ? null
                            : new Tokens(input, cacheRead, fiveMinuteWrite,
                                    oneHourWrite + 100, output, reasoning, audioInput, audioOutput),
                    new Tokens(input, cacheRead, fiveMinuteWrite, oneHourWrite, output + 100, reasoning, audioInput,
                            audioOutput),
                    forPrompt.reasoning == 0
                            ? null
                            : new Tokens(input, cacheRead, fiveMinuteWrite, oneHourWrite,
                                    output, reasoning + 100, audioInput, audioOutput),
                    forPrompt.audioInput == 0
                            ? null
                            : new Tokens(input, cacheRead, fiveMinuteWrite, oneHourWrite,
                                    output, reasoning, audioInput + 100, audioOutput),
                    forPrompt.audioOutput == 0
                            ? null
                            : new Tokens(input, cacheRead, fiveMinuteWrite, oneHourWrite,
                                    output, reasoning, audioInput, audioOutput + 100))
                    .filter(tokens -> tokens != null);
        }

        /** The usage as the format's provider reports it. */
        Map<String, Integer> usage(Format format) {
            var usage = switch (format) {
                case ANTHROPIC -> Map.of("input_tokens", input, "output_tokens", output,
                        "cache_read_input_tokens", cacheRead,
                        "cache_creation_input_tokens", fiveMinuteWrite + oneHourWrite,
                        "cache_creation.ephemeral_5m_input_tokens", fiveMinuteWrite,
                        "cache_creation.ephemeral_1h_input_tokens", oneHourWrite);
                // The SDK logs Converse's cacheDetails keyed by TTL.
                case BEDROCK_CONVERSE -> Map.of("inputTokens", input, "outputTokens", output,
                        "cacheReadInputTokens", cacheRead, "cacheWriteInputTokens", fiveMinuteWrite + oneHourWrite,
                        "cacheDetails.5m.inputTokens", fiveMinuteWrite, "cacheDetails.1h.inputTokens", oneHourWrite);
                case GEMINI -> Map.of("prompt_token_count", prompt(), "candidates_token_count", output,
                        "cached_content_token_count", cacheRead, "thoughts_token_count", reasoning);
                case OPENAI -> Map.of("prompt_tokens", prompt(),
                        "completion_tokens", output + reasoning + audioOutput,
                        "prompt_tokens_details.cached_tokens", cacheRead,
                        "prompt_tokens_details.cache_write_tokens", fiveMinuteWrite + oneHourWrite,
                        "prompt_tokens_details.cache_creation_token_details.ephemeral_1h_input_tokens", oneHourWrite,
                        "prompt_tokens_details.audio_tokens", audioInput,
                        "completion_tokens_details.reasoning_tokens", reasoning,
                        "completion_tokens_details.audio_tokens", audioOutput);
            };
            return usage.entrySet().stream()
                    .collect(Collectors.toMap(Map.Entry::getKey, count -> Math.toIntExact(count.getValue())));
        }
    }
}
