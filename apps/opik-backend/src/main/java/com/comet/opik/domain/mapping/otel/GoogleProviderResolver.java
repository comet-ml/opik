package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;

/**
 * Disambiguates the generic {@code "google"} provider that PydanticAI / the google-genai OTel
 * instrumentation emits (via {@code gen_ai.system}) into the Opik canonical provider used for
 * cost lookup.
 * <p>
 * The provider attribute alone is identical for both Google backends, so cost resolution can't
 * match the price rows keyed on {@code google_vertexai} / {@code google_ai}. The only signal that
 * tells them apart is the endpoint host carried in {@code server.address}:
 * <ul>
 *     <li>{@code *-aiplatform.googleapis.com} -&gt; Vertex AI -&gt; {@code google_vertexai}</li>
 *     <li>{@code generativelanguage.googleapis.com} -&gt; Gemini Developer API -&gt; {@code google_ai}</li>
 * </ul>
 * When the host is absent or unrecognized we default to {@code google_ai} so a cost is still
 * computed (the two price tables are currently equal for Gemini models, but may diverge).
 */
@UtilityClass
@Slf4j
public class GoogleProviderResolver {

    public static final String GOOGLE_PROVIDER = "google";
    public static final String GOOGLE_VERTEX_AI = "google_vertexai";
    public static final String GOOGLE_AI = "google_ai";

    private static final String VERTEX_AI_HOST_MARKER = "aiplatform.googleapis.com";
    private static final String GEMINI_API_HOST_MARKER = "generativelanguage.googleapis.com";

    /**
     * If the provider is the generic {@code "google"}, returns the canonical Google provider
     * resolved from the {@code server.address} stored in metadata. Otherwise returns the provider
     * unchanged.
     */
    public static String resolve(String provider, ObjectNode metadata) {
        if (!GOOGLE_PROVIDER.equalsIgnoreCase(StringUtils.trimToEmpty(provider))) {
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
            log.debug("Provider 'google' with unrecognized server.address '{}', defaulting to '{}'",
                    serverAddress, resolved);
        }

        log.debug("Resolved provider 'google' to '{}' from server.address '{}'", resolved, serverAddress);
        return resolved;
    }
}
