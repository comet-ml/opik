package com.comet.opik.domain.cost;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelCostData;
import com.comet.opik.domain.mapping.otel.GoogleProviderResolver;
import com.comet.opik.domain.mapping.otel.VertexAnthropicResolver;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The price file is replaced by an automated LiteLLM sync, so these checks also guard every sync: a provider LiteLLM
 * renames or drops fails here instead of silently losing its prices.
 */
class ProviderRegistryTest {

    private static final ProviderRegistry REGISTRY = CostService.providerRegistry();

    @Test
    void providersAreEveryProviderOfThePriceFileAndTheOverrides() throws IOException {
        var expected = new TreeSet<String>();
        for (var file : List.of(CostService.MODEL_PRICES_FILE, CostService.MODEL_PRICES_OVERRIDES_FILE)) {
            Map<String, ModelCostData> costsByModel = JsonUtils.readJsonFile(file, new TypeReference<>() {
            });
            costsByModel.forEach((model, cost) -> {
                if (!model.equals("sample_spec") && StringUtils.isNotBlank(cost.litellmProvider())) {
                    expected.add(cost.litellmProvider());
                }
            });
        }

        assertThat(REGISTRY.providers()).hasSizeGreaterThan(100).containsExactlyInAnyOrderElementsOf(expected);
    }

    @Test
    void resolveKeepsEveryProviderUnderItsOwnName() {
        assertThat(REGISTRY.providers())
                .allSatisfy(provider -> assertThat(REGISTRY.resolve(provider, null)).contains(provider));
    }

    @Test
    void aliasesMapNamesOutsideThePriceFileToProvidersInIt() {
        assertThat(ProviderRegistry.aliases()).allSatisfy((alias, provider) -> {
            assertThat(REGISTRY.providers()).doesNotContain(alias).contains(provider);
            assertThat(REGISTRY.resolve(alias, null)).contains(provider);
        });
    }

    @Test
    void siblingGroupsHoldOnlyProvidersOfThePriceFile() {
        assertThat(ProviderRegistry.siblingGroups())
                .allSatisfy(group -> assertThat(REGISTRY.providers()).containsAll(group));
    }

    @ParameterizedTest
    @EnumSource(value = LlmProvider.class, names = {"CUSTOM_LLM", "OPIK_FREE"}, mode = EnumSource.Mode.EXCLUDE)
    void resolveMapsEveryPlaygroundProviderToAProviderOfThePriceFile(LlmProvider provider) {
        assertThat(REGISTRY.resolve(provider.getValue(), null)).get().isIn(REGISTRY.providers());
    }

    @ParameterizedTest
    @ValueSource(strings = {GoogleProviderResolver.GOOGLE_AI, GoogleProviderResolver.GOOGLE_VERTEX_AI,
            VertexAnthropicResolver.ANTHROPIC_VERTEX_AI, "azure", "bedrock", "mistral", "xai", "openai", "jina_ai",
            "elastic"})
    void resolveMapsEveryNameOtelIngestionStoresToAProviderOfThePriceFile(String provider) {
        assertThat(REGISTRY.resolve(provider, null)).get().isIn(REGISTRY.providers());
    }

    @ParameterizedTest
    @CsvSource({
            "openai, openai",
            "OpenAI, openai",
            "'  anthropic  ', anthropic",
            "Google_AI, gemini",
            "together_ai, together_ai",
            "my-gateway, my-gateway"})
    void resolveTrimsAndLowercasesTheProvider(String provider, String expected) {
        assertThat(REGISTRY.resolve(provider, null)).contains(expected);
    }

    @Test
    void resolvePrefersTheProviderFieldOverTheModelPrefix() {
        assertThat(REGISTRY.resolve("openrouter", "anthropic/claude-sonnet-4-5")).contains("openrouter");
    }

    @ParameterizedTest
    @CsvSource({
            "gemini/gemini-3.1-flash-lite-preview, gemini",
            "vertex_ai/gemini-2.5-pro, vertex_ai",
            "vertex_ai-anthropic_models/claude-3-5-sonnet, vertex_ai-anthropic_models",
            "anthropic/claude-3-5-sonnet, anthropic",
            "bedrock/anthropic.claude-3-haiku, bedrock",
            "bedrock_converse/anthropic.claude-3-haiku, bedrock_converse",
            "groq/llama-3.1-70b-versatile, groq",
            "unknown_provider/some-model, unknown_provider",
            "moonshotai/kimi-k2, moonshot",
            "z-ai/glm-4.6, zai",
            "Anthropic/claude-3-5-sonnet, anthropic",
            "openrouter/anthropic/claude-sonnet-4-5, openrouter"})
    void resolveTakesAnExplicitVendorPrefixWhenTheProviderIsBlank(String model, String expected) {
        assertThat(REGISTRY.resolve(null, model)).contains(expected);
        assertThat(REGISTRY.resolve("  ", model)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"gpt-4o", "claude-3-5-sonnet", "gemini-2.5-pro", "anthropic.claude-3-haiku", "/gpt-4o",
            " /gpt-4o"})
    void resolveNeverInfersTheProviderFromTheModelName(String model) {
        assertThat(REGISTRY.resolve(null, model)).isEqualTo(Optional.empty());
    }

    @ParameterizedTest
    @CsvSource({
            "bedrock, bedrock|bedrock_converse",
            "bedrock_converse, bedrock_converse|bedrock",
            "vertex_ai-language-models, vertex_ai-language-models|vertex_ai",
            "vertex_ai, vertex_ai|vertex_ai-language-models",
            "cohere_chat, cohere_chat|cohere",
            "openai, openai",
            "my-gateway, my-gateway"})
    void priceLookupOrderPutsTheProviderFirstThenItsSiblings(String provider, String expected) {
        assertThat(REGISTRY.priceLookupOrder(provider)).containsExactly(expected.split("\\|"));
    }
}
