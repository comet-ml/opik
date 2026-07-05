package com.comet.opik.domain.cost;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.math.BigDecimal;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class CostServiceTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Test
    void calculateCostForVideoGenerationUsesDuration() {
        BigDecimal cost = CostService.calculateCost("sora-2", "openai",
                Map.of("video_duration_seconds", 4), null);

        assertThat(cost).isEqualByComparingTo("0.4");
    }

    @Test
    void calculateCostUsesCacheAwareCalculatorWhenCachePricesConfigured() {
        Map<String, Integer> usage = Map.of(
                "original_usage.inputTokens", 100,
                "original_usage.outputTokens", 20,
                "original_usage.cacheReadInputTokens", 10,
                "original_usage.cacheWriteInputTokens", 5);

        BigDecimal cost = CostService.calculateCost("anthropic.claude-3-5-haiku-20241022-v1:0", "bedrock", usage, null);

        assertThat(cost).isEqualByComparingTo("0.0001658");
    }

    /**
     * Covers every branch of the new audio-token handling in
     * {@link SpanCostCalculator#textGenerationCost}:
     * <ul>
     *   <li>{@code gpt-4o-audio-preview} publishes {@code input_cost_per_audio_token} (4e-5),
     *       which is 16x the standard input rate (2.5e-6). The Python SDK
     *       ({@code openai_chat_completions_usage.PromptTokensDetails.audio_tokens}) flattens the
     *       count under {@code original_usage.prompt_tokens_details.audio_tokens}; the bare OTel
     *       key {@code prompt_tokens_details.audio_tokens} is the documented fallback.</li>
     *   <li>{@code gpt-4o-mini} has no {@code input_cost_per_audio_token}, so
     *       {@code inputAudioRate == 0} and the audio token key in usage is ignored — every
     *       prompt token bills at the standard input rate.</li>
     * </ul>
     */
    @ParameterizedTest(name = "{0} with key={1}")
    @MethodSource("provideAudioPromptTokenCases")
    void calculateCostBillsAudioPromptTokensAtTheConfiguredRate(
            String model, String audioUsageKey, String expectedCost) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1_000,
                "completion_tokens", 200,
                audioUsageKey, 300);

        BigDecimal cost = CostService.calculateCost(model, "openai", usage, null);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideAudioPromptTokenCases() {
        // gpt-4o-audio-preview: input 2.5e-6, output 1e-5, input_audio 4e-5
        // non-audio prompt = 1000 - 300 = 700
        // 700 * 2.5e-6 + 300 * 4e-5 + 200 * 1e-5 = 0.00175 + 0.012 + 0.002 = 0.01575
        // gpt-4o-mini: input 1.5e-7, output 6e-7 (no audio rate; audio key is ignored)
        // 1000 * 1.5e-7 + 200 * 6e-7 = 0.00015 + 0.00012 = 0.00027
        return Stream.of(
                Arguments.of("gpt-4o-audio-preview",
                        "original_usage.prompt_tokens_details.audio_tokens", "0.01575"),
                Arguments.of("gpt-4o-audio-preview",
                        "prompt_tokens_details.audio_tokens", "0.01575"),
                Arguments.of("gpt-4o-mini",
                        "original_usage.prompt_tokens_details.audio_tokens", "0.00027"));
    }

    @Test
    void calculateCostUsesAnthropicCacheCalculatorForClaudeOnVertexAI() {
        // Claude models hosted on Google Vertex AI ship under the litellm_provider
        // "vertex_ai-anthropic_models" (canonical provider "anthropic_vertexai"), separate from
        // the bare-Anthropic and Bedrock paths. Without an entry in PROVIDERS_CACHE_COST_CALCULATOR
        // these requests fell through to textGenerationCost, so prompt-cache tokens were billed
        // at the full input rate instead of the configured cache-read rate.
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 100,
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 100,
                "original_usage.cache_read_input_tokens", 200,
                "original_usage.cache_creation_input_tokens", 50);

        BigDecimal cost = CostService.calculateCost("vertex_ai/claude-haiku-4-5", "anthropic_vertexai", usage, null);

        // vertex_ai/claude-haiku-4-5 (vertex_ai-anthropic_models):
        // input 1e-6, output 5e-6, cache_read 1e-7, cache_creation 1.25e-6
        // Anthropic shape: prompt_tokens EXCLUDES cached tokens, so we sum each bucket at its own rate.
        // 1000*1e-6 + 100*5e-6 + 50*1.25e-6 + 200*1e-7
        // = 0.001 + 0.0005 + 0.0000625 + 0.00002 = 0.0015825
        assertThat(cost).isEqualByComparingTo("0.0015825");
    }

    /**
     * Whole-prompt tier semantics for {@code *_above_200k_tokens} rates (issue #6982):
     * once the prompt strictly exceeds 200K every token bills at the tier rate; exactly at
     * the threshold the base rate still applies.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideGeminiAbove200kTierCases")
    void calculateCostAppliesAbove200kTierPricingForGemini_issue6982(String description, int promptTokens,
            String expectedCost) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", promptTokens,
                "completion_tokens", 1_000,
                "original_usage.prompt_token_count", promptTokens,
                "original_usage.candidates_token_count", 1_000);

        BigDecimal cost = CostService.calculateCost("gemini-2.5-pro", "google_vertexai", usage, null);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideGeminiAbove200kTierCases() {
        // gemini-2.5-pro base: input 1.25e-6 / output 1e-5; above_200k: input 2.5e-6 / output 1.5e-5.
        return Stream.of(
                // 200_000 * 1.25e-6 + 1_000 * 1e-5 = 0.25 + 0.01 = 0.26
                Arguments.of("at threshold uses base rate", 200_000, "0.26"),
                // 300_000 * 2.5e-6 + 1_000 * 1.5e-5 = 0.75 + 0.015 = 0.765
                Arguments.of("above threshold uses tier rate", 300_000, "0.765"));
    }

    @Test
    void calculateCostUsesGoogleCacheCalculatorWhenCachePricesConfigured_issue6976() {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 100,
                "original_usage.prompt_token_count", 1000,
                "original_usage.candidates_token_count", 100,
                "original_usage.cached_content_token_count", 400);

        BigDecimal cost = CostService.calculateCost("gemini-2.5-flash", "google_vertexai", usage, null);

        // gemini-2.5-flash: input 3e-7, output 2.5e-6, cache_read 3e-8
        // non-cached input = 1000 - 400 = 600 -> 600*3e-7 + 100*2.5e-6 + 400*3e-8
        // = 0.00018 + 0.00025 + 0.000012 = 0.000442
        assertThat(cost).isEqualByComparingTo("0.000442");
    }

    @ParameterizedTest
    @MethodSource("provideAudioSpeechModels")
    void calculateCostForAudioSpeech(String model, int inputCharacters, String expectedCost) {
        BigDecimal cost = CostService.calculateCost(model, "openai",
                Map.of("input_characters", inputCharacters), null);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideAudioSpeechModels() {
        return Stream.of(
                // tts-1 at $0.000015 per character, 1000 characters → $0.015
                Arguments.of("tts-1", 1000, "0.015"),
                // tts-1-hd at $0.000030 per character, 500 characters → $0.015
                Arguments.of("tts-1-hd", 500, "0.015"));
    }

    @Test
    void calculateCostForAudioSpeechWithOriginalUsagePrefix() {
        // SDK 1.6.0+ sends usage with original_usage. prefix
        BigDecimal cost = CostService.calculateCost("tts-1", "openai",
                Map.of("original_usage.input_characters", 1000), null);

        assertThat(cost).isEqualByComparingTo("0.015");
    }

    @Test
    void calculateCostFallsBackToMetadataWhenNoMatchingModelFound() {
        ObjectNode metadata = OBJECT_MAPPER.createObjectNode();
        metadata.putObject("cost")
                .put("currency", "USD")
                .put("total_cost", 0.42);

        BigDecimal cost = CostService.calculateCost("unknown-model", "unknown", Map.of(), metadata);

        assertThat(cost).isEqualByComparingTo("0.42");
    }

    /**
     * Test for issue #4114: Cost estimate not showing for recent Claude models.
     *
     * This test verifies that model names with dots (e.g., "claude-3.5-sonnet")
     * are correctly normalized to hyphens (e.g., "claude-3-5-sonnet") to match
     * the pricing database format. It also tests case insensitivity and backwards
     * compatibility.
     *
     * Parameterized test covering both positive (cost > 0) and edge (cost = 0) cases.
     */
    @ParameterizedTest
    @MethodSource("provideModelNamesForNormalization")
    void calculateCost_shouldNormalizeModelNames_issue4114(String modelName, String provider, boolean shouldHaveCost) {
        Map<String, Integer> usage = Map.of(
                "original_usage.input_tokens", 1000,
                "original_usage.output_tokens", 500);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        if (shouldHaveCost) {
            assertThat(cost).isGreaterThan(BigDecimal.ZERO);
        } else {
            assertThat(cost).isEqualTo(BigDecimal.ZERO);
        }
    }

    private static Stream<Arguments> provideModelNamesForNormalization() {
        return Stream.of(
                // Dot notation should work (normalized to hyphens)
                Arguments.of("claude-3.7-sonnet-20250219", "anthropic", true),
                Arguments.of("claude-sonnet-4.5", "anthropic", true),
                Arguments.of("claude-haiku-4.5", "anthropic", true),
                Arguments.of("claude-sonnet-4.5-20250929", "anthropic", true),
                Arguments.of("claude-haiku-4.5-20251001", "anthropic", true),

                // Case insensitivity should work
                Arguments.of("Claude-3.7-Sonnet-20250219", "anthropic", true),
                Arguments.of("CLAUDE-SONNET-4.5", "anthropic", true),

                // Backwards compatibility - exact matches still work
                Arguments.of("claude-3-7-sonnet-20250219", "anthropic", true),
                Arguments.of("claude-haiku-4-5", "anthropic", true),
                Arguments.of("claude-sonnet-4-5", "anthropic", true),

                // Provider prefix + dot notation should work (prefix stripped, then dots normalized)
                Arguments.of("anthropic/claude-3.7-sonnet-20250219", "anthropic", true),
                Arguments.of("anthropic/claude-sonnet-4.5", "anthropic", true),

                // Provider prefix + case variation should work
                Arguments.of("anthropic/Claude-3.7-Sonnet-20250219", "anthropic", true),

                // Unknown models should gracefully return zero
                Arguments.of("claude-3.5.1", "anthropic", false),
                Arguments.of("unknown-model-with-dots.1.2.3", "unknown", false));
    }

    @ParameterizedTest
    @MethodSource("provideModelNamesWithDateSuffixes")
    void calculateCost_shouldStripDateSuffixes_issue5018(String modelName, String provider) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    @Test
    void calculateCost_shouldReturnZeroForUnknownModelWithDateSuffix_issue5018() {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal cost = CostService.calculateCost("unknown-model-2025-12-17", "openai", usage, null);

        assertThat(cost).isEqualTo(BigDecimal.ZERO);
    }

    /**
     * Test for issue #5621: LiteLLM OTel model names with provider prefix not found in pricing table.
     *
     * LiteLLM sends model names with provider prefix via gen_ai.request.model
     * (e.g. "openai/gpt-4o", "anthropic/claude-3-5-sonnet-20241022").
     * These should be stripped before lookup to match the stored keys.
     */
    @ParameterizedTest
    @MethodSource("provideModelNamesWithProviderPrefix")
    void calculateCost_shouldStripProviderPrefix_issue5621(String modelName, String provider) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal cost = CostService.calculateCost(modelName, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    private static Stream<Arguments> provideModelNamesWithProviderPrefix() {
        return Stream.of(
                Arguments.of("openai/gpt-4o", "openai"),
                Arguments.of("openai/gpt-4o-mini", "openai"),
                Arguments.of("anthropic/claude-3-7-sonnet-20250219", "anthropic"),
                Arguments.of("anthropic/claude-haiku-4-5-20251001", "anthropic"));
    }

    /**
     * Overrides file: alias entries should resolve to the target's pricing.
     */
    @ParameterizedTest
    @MethodSource("provideOverrideAliases")
    void calculateCost_overrideAliasResolvesToTargetPricing(String aliasName, String targetName, String provider) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal aliasCost = CostService.calculateCost(aliasName, provider, usage, null);
        BigDecimal targetCost = CostService.calculateCost(targetName, provider, usage, null);

        assertThat(aliasCost).isGreaterThan(BigDecimal.ZERO);
        assertThat(aliasCost).isEqualByComparingTo(targetCost);
    }

    private static Stream<Arguments> provideOverrideAliases() {
        return Stream.of(
                Arguments.of("claude-4-5-haiku", "claude-haiku-4-5", "anthropic"),
                Arguments.of("claude-4-5-opus", "claude-opus-4-5", "anthropic"),
                Arguments.of("claude-4-5-sonnet", "claude-sonnet-4-5", "anthropic"),
                Arguments.of("claude-4-6-opus", "claude-opus-4-6", "anthropic"),
                Arguments.of("claude-4-6-sonnet", "claude-sonnet-4-6", "anthropic"),
                Arguments.of("claude-4-7-opus", "claude-opus-4-7", "anthropic"));
    }

    /**
     * Overrides file: aliases must be reachable via the existing dot→hyphen normalization,
     * which is how EIS-style names like "claude-4.5-haiku" resolve to the alias key
     * "claude-4-5-haiku" and from there to the target "claude-haiku-4-5".
     */
    @Test
    void calculateCost_overrideAliasReachableViaDotNormalization() {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal dotFormCost = CostService.calculateCost("claude-4.5-haiku", "anthropic", usage, null);
        BigDecimal canonicalCost = CostService.calculateCost("claude-haiku-4-5", "anthropic", usage, null);

        assertThat(dotFormCost).isGreaterThan(BigDecimal.ZERO);
        assertThat(dotFormCost).isEqualByComparingTo(canonicalCost);
    }

    /**
     * Overrides file: brand-new entries (models absent from upstream LiteLLM) must produce non-zero cost.
     */
    @ParameterizedTest
    @MethodSource("provideOverrideNewEntries")
    void calculateCost_overrideNewEntry(String model, String provider) {
        Map<String, Integer> usage = Map.of(
                "prompt_tokens", 1000,
                "completion_tokens", 500);

        BigDecimal cost = CostService.calculateCost(model, provider, usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    private static Stream<Arguments> provideOverrideNewEntries() {
        return Stream.of(
                Arguments.of("gpt-oss-120b", "openai"),
                Arguments.of("gpt-oss-20b", "openai"),
                Arguments.of("jina-clip-v2", "jina_ai"),
                Arguments.of("jina-embeddings-v3", "jina_ai"),
                Arguments.of("jina-embeddings-v5-omni-nano", "jina_ai"),
                Arguments.of("jina-embeddings-v5-omni-small", "jina_ai"),
                Arguments.of("jina-embeddings-v5-text-nano", "jina_ai"),
                Arguments.of("jina-embeddings-v5-text-small", "jina_ai"),
                Arguments.of("elser_model_2", "elastic"),
                Arguments.of("gemini-3-flash", "google_ai"),
                Arguments.of("gemini-3.1-pro", "google_ai"),
                Arguments.of("gemini-embedding-002", "google_ai"),
                Arguments.of("mistral-medium-3-5", "mistral"),
                Arguments.of("mistral-small-2603", "mistral"));
    }

    /**
     * Mistral cost tracking: until `mistral` was added to PROVIDERS_MAPPING the entire set of
     * upstream LiteLLM `litellm_provider: "mistral"` rows was dropped at startup, so every
     * Mistral span returned cost = 0. This locks in both the upstream-sourced rows and the
     * two override-only rows (Medium 3.5, Small 4) added alongside the registry fix.
     */
    @ParameterizedTest
    @MethodSource("provideMistralModels")
    void calculateCostForMistralModels(String model, String expectedCost) {
        Map<String, Integer> usage = Map.of("prompt_tokens", 1_000_000, "completion_tokens", 1_000_000);

        BigDecimal cost = CostService.calculateCost(model, "mistral", usage, null);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideMistralModels() {
        return Stream.of(
                // Upstream LiteLLM row: $6e-08 in / $1.8e-07 out → 0.06 + 0.18 = 0.24
                Arguments.of("mistral-small-latest", "0.24"),
                // Upstream LiteLLM row: $5e-07 in / $1.5e-06 out → 0.5 + 1.5 = 2.00
                Arguments.of("mistral-large-3", "2.00"),
                // Upstream LiteLLM row: $3e-07 in / $9e-07 out → 0.3 + 0.9 = 1.20
                Arguments.of("codestral-2508", "1.20"),
                // New override: $1.5e-06 in / $7.5e-06 out → 1.5 + 7.5 = 9.00
                Arguments.of("mistral-medium-3-5", "9.00"),
                // New override: $1.5e-07 in / $6e-07 out → 0.15 + 0.6 = 0.75
                Arguments.of("mistral-small-2603", "0.75"));
    }

    /**
     * Overrides file: `litellm_provider: "microsoft"` is remapped to canonical `azure` via
     * PROVIDERS_MAPPING. A span emitted with provider=`azure` and the model name must hit
     * the override row for `azure/multilingual-e5-large`.
     */
    @Test
    void calculateCost_overrideMicrosoftProviderMapsToAzure() {
        Map<String, Integer> usage = Map.of("prompt_tokens", 1000);

        BigDecimal cost = CostService.calculateCost("multilingual-e5-large", "azure", usage, null);

        assertThat(cost).isGreaterThan(BigDecimal.ZERO);
    }

    /**
     * Covers both branches of registering {@code azure} as a canonical provider so that the 199
     * Azure-tagged entries in {@code model_prices_and_context_window.json} (e.g. {@code azure/gpt-4.1},
     * {@code azure/command-r-plus}, all {@code azure/gpt-4o*} and {@code azure/gpt-5*} variants) are
     * no longer silently dropped at load time:
     * <ul>
     *   <li>Azure model with no cache rates falls through to {@link SpanCostCalculator#textGenerationCost}.</li>
     *   <li>Azure model with cache rates routes through
     *       {@link SpanCostCalculator#textGenerationWithCacheCostOpenAI} — Azure-hosted OpenAI models share
     *       the OpenAI usage shape, so cached_tokens are subtracted from prompt_tokens before billing the
     *       remainder at the standard input rate.</li>
     * </ul>
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource("provideAzureProviderCases")
    void calculateCostHandlesAzureHostedModels(String description, String model, Map<String, Integer> usage,
            String expectedCost) {
        BigDecimal cost = CostService.calculateCost(model, "azure", usage, null);

        assertThat(cost).isEqualByComparingTo(expectedCost);
    }

    private static Stream<Arguments> provideAzureProviderCases() {
        // azure/command-r-plus: input 3e-6, output 1.5e-5 (no cache rates) -> textGenerationCost
        // 1000 * 3e-6 + 200 * 1.5e-5 = 0.003 + 0.003 = 0.006
        // azure/gpt-4.1: input 2e-6, output 8e-6, cache_read 5e-7 -> textGenerationWithCacheCostOpenAI
        // non-cached input = 1000 - 300 = 700
        // 700 * 2e-6 + 200 * 8e-6 + 300 * 5e-7 = 0.0014 + 0.0016 + 0.00015 = 0.00315
        return Stream.of(
                Arguments.of("plain text-generation route", "azure/command-r-plus",
                        Map.of("prompt_tokens", 1000, "completion_tokens", 200), "0.006"),
                Arguments.of("cache-aware route via OpenAI calc", "azure/gpt-4.1",
                        Map.of("original_usage.prompt_tokens", 1000,
                                "original_usage.completion_tokens", 200,
                                "original_usage.prompt_tokens_details.cached_tokens", 300),
                        "0.00315"));
    }

    private static Stream<Arguments> provideModelNamesWithDateSuffixes() {
        return Stream.of(
                // 1. Stripped date on original name (base model has dots, date suffix removed before lookup)
                Arguments.of("gpt-5.2-2025-12-17", "openai"),
                // 2. Stripped date on normalized name (dots normalized to hyphens, then date suffix removed)
                Arguments.of("claude-sonnet-4.5-2025-12-17", "anthropic"),
                // 3. Base models without date suffix should still work
                Arguments.of("gpt-5.2", "openai"),
                Arguments.of("claude-sonnet-4.5", "anthropic"),
                // 4. Provider prefix + date suffix: prefix stripped first, then date suffix removed
                Arguments.of("anthropic/claude-sonnet-4.5-2025-12-17", "anthropic"),
                Arguments.of("openai/gpt-5.2-2025-12-17", "openai"));
    }
}
