package com.comet.opik.domain.mapping.otel;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;

/**
 * Maps the OTel GenAI semantic-convention provider vocabulary onto the canonical Opik provider
 * names used as price-table keys.
 * <p>
 * Instrumentations report the provider via {@code gen_ai.system} (deprecated) or its replacement
 * {@code gen_ai.provider.name}, using the values from the OTel registry. Several of those spell
 * the same provider differently from Opik: {@code vertex_ai} vs {@code google_vertexai},
 * {@code aws.bedrock} vs {@code bedrock}, {@code x_ai} vs {@code xai}. A value that reaches
 * {@code CostService} unmapped matches no pricing row, so the span silently costs 0 (OPIK-7717).
 * <p>
 * Only unambiguous 1:1 renames belong here — values naming more than one backend are excluded:
 * <ul>
 *     <li>{@code google} and {@code gcp.gen_ai} ("specific backend is unknown" per the semconv)
 *     need the endpoint host and are handled by {@link GoogleProviderResolver} instead.</li>
 *     <li>{@code azure.ai.inference} / {@code az.ai.inference} front either Azure OpenAI
 *     (priced under {@code azure}) or Azure AI Foundry models such as Claude and Llama, which
 *     LiteLLM prices under a separate {@code azure_ai} provider that Opik does not load at all.
 *     Aliasing them to {@code azure} would price a Foundry model against the OpenAI table.</li>
 * </ul>
 * Values already matching the Opik vocabulary ({@code openai}, {@code anthropic}, {@code groq},
 * {@code deepseek}, {@code perplexity}) need no entry and pass through unchanged, as do values
 * Opik has no pricing for at all ({@code cohere}, {@code ibm.watsonx.ai}).
 *
 * @see <a href="https://opentelemetry.io/docs/specs/semconv/registry/attributes/gen-ai/">OTel GenAI attribute registry</a>
 */
@UtilityClass
@Slf4j
public class GenAiProviderAliasResolver {

    private static final Map<String, String> ALIASES = Map.ofEntries(
            // Legacy spellings the semconv renamed but instrumentation still emits (OPIK-7717):
            // `vertex_ai` -> `gcp.vertex_ai`, `az.ai.openai` -> `azure.ai.openai`.
            Map.entry("vertex_ai", GoogleProviderResolver.GOOGLE_VERTEX_AI),
            Map.entry("az.ai.openai", "azure"),
            // Current spellings, each scoped to a single backend
            Map.entry("gcp.vertex_ai", GoogleProviderResolver.GOOGLE_VERTEX_AI),
            Map.entry("gcp.gemini", GoogleProviderResolver.GOOGLE_AI),
            Map.entry("aws.bedrock", "bedrock"),
            Map.entry("azure.ai.openai", "azure"),
            Map.entry("mistral_ai", "mistral"),
            Map.entry("x_ai", "xai"));

    /**
     * Returns the canonical Opik provider for a semantic-convention provider value, or the
     * provider unchanged when it needs no aliasing.
     */
    public static String resolve(String provider) {
        if (StringUtils.isBlank(provider)) {
            return provider;
        }

        String resolved = ALIASES.get(StringUtils.trimToEmpty(provider).toLowerCase(Locale.ROOT));
        if (resolved == null) {
            return provider;
        }

        log.debug("Aliased OTel provider '{}' to canonical '{}'", provider, resolved);
        return resolved;
    }
}
