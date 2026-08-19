package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Set;

/**
 * Disambiguates the Google provider values that name no specific backend into the Opik canonical
 * provider used for cost lookup. Two values qualify:
 * <ul>
 *     <li>{@code google} — emitted by PydanticAI / the google-genai OTel instrumentation</li>
 *     <li>{@code gcp.gen_ai} — the semantic convention's own "specific backend is unknown" value</li>
 * </ul>
 * Neither identifies a backend on its own, so cost resolution can't match the price rows keyed on
 * {@code google_vertexai} / {@code google_ai}. The only signal that tells them apart is the
 * endpoint host carried in {@code server.address}:
 * <ul>
 *     <li>{@code *-aiplatform.googleapis.com} -&gt; Vertex AI -&gt; {@code google_vertexai}</li>
 *     <li>{@code generativelanguage.googleapis.com} -&gt; Gemini Developer API -&gt; {@code google_ai}</li>
 * </ul>
 * When the host is absent or unrecognized we default to {@code google_ai} so a cost is still
 * computed (the two price tables are currently equal for Gemini models, but may diverge).
 * <p>
 * Google values that <em>do</em> name a backend ({@code vertex_ai}, {@code gcp.vertex_ai},
 * {@code gcp.gemini}) need no host and are aliased directly by {@link GenAiProviderAliasResolver}.
 */
@UtilityClass
@Slf4j
public class GoogleProviderResolver {

    public static final String GOOGLE_PROVIDER = "google";
    public static final String GCP_GEN_AI_PROVIDER = "gcp.gen_ai";
    public static final String GOOGLE_VERTEX_AI = "google_vertexai";
    public static final String GOOGLE_AI = "google_ai";

    private static final Set<String> AMBIGUOUS_PROVIDERS = Set.of(GOOGLE_PROVIDER, GCP_GEN_AI_PROVIDER);

    private static final String VERTEX_AI_HOST_MARKER = "aiplatform.googleapis.com";
    private static final String GEMINI_API_HOST_MARKER = "generativelanguage.googleapis.com";

    /**
     * If the provider names no specific Google backend, returns the canonical Google provider
     * resolved from the {@code server.address} stored in metadata. Otherwise returns the provider
     * unchanged.
     */
    public static String resolve(String provider, ObjectNode metadata) {
        if (!AMBIGUOUS_PROVIDERS.contains(StringUtils.trimToEmpty(provider).toLowerCase(Locale.ROOT))) {
            return provider;
        }

        String serverAddress = metadata != null && metadata.hasNonNull(GeneralMappingRules.SERVER_ADDRESS_ATTR)
                ? metadata.get(GeneralMappingRules.SERVER_ADDRESS_ATTR).asText().toLowerCase(Locale.ROOT)
                : "";

        String resolved;
        if (serverAddress.contains(VERTEX_AI_HOST_MARKER)) {
            resolved = GOOGLE_VERTEX_AI;
        } else if (serverAddress.contains(GEMINI_API_HOST_MARKER)) {
            resolved = GOOGLE_AI;
        } else {
            resolved = GOOGLE_AI;
            log.debug("Provider '{}' with unrecognized server.address '{}', defaulting to '{}'",
                    provider, serverAddress, resolved);
        }

        log.debug("Resolved provider '{}' to '{}' from server.address '{}'", provider, resolved, serverAddress);
        return resolved;
    }
}
