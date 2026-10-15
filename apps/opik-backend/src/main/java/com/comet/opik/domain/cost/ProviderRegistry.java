package com.comet.opik.domain.cost;

import jakarta.annotation.Nullable;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;

/**
 * The LLM providers Opik prices, named as in LiteLLM's price file. Every provider in that file is supported under its
 * own name, so a provider LiteLLM adds needs no code change. Other spellings clients send are aliases of one of them.
 * The provider is never inferred from usage keys or from model-name patterns.
 *
 * @param providers every {@code litellm_provider} of the price file and of Opik's overrides file.
 */
public record ProviderRegistry(@NonNull Set<String> providers) {

    // Names clients send that aren't in the price file, each mapped to the provider it means.
    private static final Map<String, String> LITELLM_NAME_BY_ALIAS = Map.of(
            // The Python SDK's provider names, which OTel ingestion's provider resolvers also store on spans.
            "google_ai", "gemini",
            "google_vertexai", "vertex_ai-language-models",
            "anthropic_vertexai", "vertex_ai-anthropic_models",
            // Online evaluation's LlmProvider value for Vertex, which only offers Gemini models there.
            "vertex-ai", "vertex_ai-language-models",
            // OpenRouter's vendor prefixes, seen when the model name supplies the provider.
            "moonshotai", "moonshot",
            "z-ai", "zai");

    // Providers LiteLLM splits one vendor's models between: most Bedrock models are only under its Converse provider,
    // the newer Vertex Gemini models only under vertex_ai, and Cohere's chat models only under cohere_chat.
    private static final List<List<String>> SIBLING_GROUPS = List.of(
            List.of("bedrock", "bedrock_converse"),
            List.of("vertex_ai-language-models", "vertex_ai"),
            List.of("cohere", "cohere_chat"));

    public ProviderRegistry {
        providers = Set.copyOf(providers);
    }

    /**
     * The provider field as sent, or the provider its alias means. Only when the field is blank can an explicit vendor
     * prefix in the model name ({@code anthropic/claude-sonnet-4-5}) supply it.
     */
    public Optional<String> resolve(@Nullable String provider, @Nullable String model) {
        if (StringUtils.isNotBlank(provider)) {
            return Optional.of(toLiteLlmName(provider));
        }
        if (model != null) {
            int prefixEnd = model.indexOf('/');
            if (prefixEnd > 0 && StringUtils.isNotBlank(model.substring(0, prefixEnd))) {
                return Optional.of(toLiteLlmName(model.substring(0, prefixEnd)));
            }
        }
        return Optional.empty();
    }

    /** The provider first, then the providers LiteLLM files the same vendor's other models under. */
    public List<String> priceLookupOrder(@NonNull String provider) {
        return SIBLING_GROUPS.stream()
                .filter(group -> group.contains(provider))
                .findFirst()
                .map(group -> Stream.concat(Stream.of(provider),
                        group.stream().filter(sibling -> !sibling.equals(provider))).toList())
                .orElse(List.of(provider));
    }

    String toLiteLlmName(@NonNull String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return LITELLM_NAME_BY_ALIAS.getOrDefault(normalized, normalized);
    }

    static Map<String, String> aliases() {
        return LITELLM_NAME_BY_ALIAS;
    }

    static List<List<String>> siblingGroups() {
        return SIBLING_GROUPS;
    }
}
