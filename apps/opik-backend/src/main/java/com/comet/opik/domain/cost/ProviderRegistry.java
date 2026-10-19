package com.comet.opik.domain.cost;

import jakarta.annotation.Nullable;
import lombok.NonNull;
import lombok.experimental.UtilityClass;
import org.apache.commons.lang3.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps the provider a span names to the matching provider of LiteLLM's price file, under whose names Opik prices
 * models. Every provider in that file is priced under its own name, so a provider LiteLLM adds needs no code change.
 * Other spellings clients send are aliases of one of them. The provider is never inferred from usage keys or from
 * model-name patterns.
 */
@UtilityClass
public class ProviderRegistry {

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

    // Built once, as every priced span reads it.
    private static final Map<String, List<String>> PRICE_LOOKUP_ORDER_BY_PROVIDER = SIBLING_GROUPS.stream()
            .flatMap(group -> group.stream().map(provider -> Map.entry(provider, Stream.concat(Stream.of(provider),
                    group.stream().filter(sibling -> !sibling.equals(provider))).toList())))
            .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));

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
        return PRICE_LOOKUP_ORDER_BY_PROVIDER.getOrDefault(provider, List.of(provider));
    }

    String toLiteLlmName(@NonNull String name) {
        String normalized = name.trim().toLowerCase(Locale.ROOT);
        return LITELLM_NAME_BY_ALIAS.getOrDefault(normalized, normalized);
    }

    Map<String, String> aliases() {
        return LITELLM_NAME_BY_ALIAS;
    }

    List<List<String>> siblingGroups() {
        return SIBLING_GROUPS;
    }
}
