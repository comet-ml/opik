package com.comet.opik.domain.cost;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.domain.mapping.otel.GoogleProviderResolver;
import com.comet.opik.domain.mapping.otel.VertexAnthropicResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class ProviderRegistryTest {

    @Test
    void resolveKeepsEveryProviderOfThePriceFileUnderItsOwnName() {
        assertThat(PriceFileProviders.ALL).hasSizeGreaterThan(100)
                .allSatisfy(provider -> assertThat(ProviderRegistry.resolve(provider, null)).contains(provider));
    }

    @Test
    void aliasesMapNamesOutsideThePriceFileToProvidersInIt() {
        assertThat(ProviderRegistry.aliases()).allSatisfy((alias, provider) -> {
            assertThat(PriceFileProviders.ALL).doesNotContain(alias).contains(provider);
            assertThat(ProviderRegistry.resolve(alias, null)).contains(provider);
        });
    }

    @Test
    void siblingGroupsHoldOnlyProvidersOfThePriceFile() {
        assertThat(ProviderRegistry.siblingGroups())
                .allSatisfy(group -> assertThat(PriceFileProviders.ALL).containsAll(group));
    }

    @ParameterizedTest
    @EnumSource(value = LlmProvider.class, names = {"CUSTOM_LLM", "OPIK_FREE"}, mode = EnumSource.Mode.EXCLUDE)
    void resolveMapsEveryPlaygroundProviderToAProviderOfThePriceFile(LlmProvider provider) {
        assertThat(ProviderRegistry.resolve(provider.getValue(), null)).get().isIn(PriceFileProviders.ALL);
    }

    @ParameterizedTest
    @ValueSource(strings = {GoogleProviderResolver.GOOGLE_AI, GoogleProviderResolver.GOOGLE_VERTEX_AI,
            VertexAnthropicResolver.ANTHROPIC_VERTEX_AI, "azure", "bedrock", "mistral", "xai", "openai", "jina_ai",
            "elastic"})
    void resolveMapsEveryNameOtelIngestionStoresToAProviderOfThePriceFile(String provider) {
        assertThat(ProviderRegistry.resolve(provider, null)).get().isIn(PriceFileProviders.ALL);
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
        assertThat(ProviderRegistry.resolve(provider, null)).contains(expected);
    }

    @ParameterizedTest
    @CsvSource({
            "api.cerebras.ai, cerebras",
            "API.Cerebras.AI, cerebras",
            "cerebras.ai, cerebras",
            "api.cerebras.ai., cerebras",
            "api.deepseek.com, deepseek",
            "api.x.ai, xai",
            "api.z.ai, zai",
            "api.fireworks.ai, fireworks_ai",
            "api.groq.com, groq",
            "api.openai.com, openai",
            "openrouter.ai, openrouter",
            "api.together.xyz, together_ai",
            "api.together.xyz:443, together_ai"})
    void resolveMapsAnApiHostToTheProviderItNames(String host, String expected) {
        assertThat(ProviderRegistry.resolve(host, null)).contains(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"api.example.com", "inference.cerebras.ai", "api.eu.cerebras.ai", "openai.internal",
            "api.deepseek.local", "deepseek.vercel.app", "localhost:8080", "127.0.0.1:8000"})
    void resolveKeepsAHostThatNamesNoProviderAsSent(String host) {
        assertThat(ProviderRegistry.resolve(host, null)).contains(host);
    }

    @Test
    void resolvePrefersTheProviderFieldOverTheModelPrefix() {
        assertThat(ProviderRegistry.resolve("openrouter", "anthropic/claude-sonnet-4-5")).contains("openrouter");
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
        assertThat(ProviderRegistry.resolve(null, model)).contains(expected);
        assertThat(ProviderRegistry.resolve("  ", model)).contains(expected);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"gpt-4o", "claude-3-5-sonnet", "gemini-2.5-pro", "anthropic.claude-3-haiku", "/gpt-4o",
            " /gpt-4o"})
    void resolveNeverInfersTheProviderFromTheModelName(String model) {
        assertThat(ProviderRegistry.resolve(null, model)).isEqualTo(Optional.empty());
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
        assertThat(ProviderRegistry.priceLookupOrder(provider)).containsExactly(expected.split("\\|"));
    }
}
