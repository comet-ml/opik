package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * Narrows Vertex AI spans serving an Anthropic model onto the canonical provider Opik prices them
 * under, {@code anthropic_vertexai}.
 * <p>
 * Vertex is not a single-vendor backend: alongside Gemini it serves Claude, and the two are priced
 * from different rows. Opik keys the Gemini rows on {@code google_vertexai} (LiteLLM's
 * {@code vertex_ai-language-models}) and the Claude rows on {@code anthropic_vertexai}
 * ({@code vertex_ai-anthropic_models}). Nothing downstream recovers the difference: the
 * {@code CostService} provider-prefix fallback needs a {@code /} in the model name, which a bare
 * {@code claude-haiku-4-5} does not have, so a Claude-on-Vertex span left under
 * {@code google_vertexai} matches no row and costs 0 — the very OPIK-7717 symptom — while also
 * being grouped and filtered as Google traffic.
 * <p>
 * The model family is the only signal that separates them; the endpoint host is identical for both.
 * Anthropic is the sole non-Google family Opik loads Vertex pricing for, so matching the
 * {@code claude} prefix is sufficient — a Vertex-hosted Llama or Mistral has no Vertex price row to
 * route to and is left under {@code google_vertexai}, exactly as before.
 */
@Slf4j
public class VertexAnthropicResolver implements ProviderResolver {

    public static final String ANTHROPIC_VERTEX_AI = "anthropic_vertexai";

    private static final String ANTHROPIC_MODEL_PREFIX = "claude";

    /**
     * Claims Vertex spans whose model is a Claude model. Keys on {@code google_vertexai} rather than
     * the wire vocabulary so it catches every route into Vertex — the {@code vertex_ai} aliases and
     * the host-disambiguated {@code google} / {@code gcp.gen_ai} values alike.
     */
    @Override
    public boolean appliesTo(Resolution resolution) {
        return GoogleProviderResolver.GOOGLE_VERTEX_AI.equals(resolution.provider())
                && isAnthropicModel(resolution.model());
    }

    @Override
    public Resolution apply(Resolution resolution, ObjectNode metadata) {
        log.debug("Resolved Vertex provider to '{}' for Anthropic model '{}'",
                ANTHROPIC_VERTEX_AI, resolution.model());
        return new Resolution(resolution.model(), ANTHROPIC_VERTEX_AI);
    }

    /**
     * Matches on the model name with any routing prefix stripped, the same way {@code CostService}
     * normalizes it before a price lookup, so a LiteLLM-style {@code vertex_ai/claude-haiku-4-5}
     * is recognized as readily as a bare {@code claude-haiku-4-5}.
     */
    private static boolean isAnthropicModel(String model) {
        if (StringUtils.isBlank(model)) {
            return false;
        }

        String withoutPrefix = StringUtils.substringAfter(model, "/");
        String modelName = withoutPrefix.isEmpty() ? model : withoutPrefix;

        return modelName.toLowerCase(Locale.ROOT).startsWith(ANTHROPIC_MODEL_PREFIX);
    }
}
