package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.net.URISyntaxException;
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
 * computed (the two price tables are currently equal for Gemini models, but may diverge). That
 * default is a guess, and it is a guess for <em>both</em> values: a Vertex call from an
 * instrumentation that omits {@code server.address} is persisted as Gemini Developer API traffic
 * and filtered and grouped as such. For {@code gcp.gen_ai} this replaces the previous behaviour of
 * persisting the value verbatim — obviously unmapped, and costing 0 — with an attribution that is
 * silently wrong on that path, which is the price of pricing the spans that do carry the host.
 * <p>
 * Google values that <em>do</em> name a backend ({@code vertex_ai}, {@code gcp.vertex_ai},
 * {@code gcp.gemini}) need no host and are aliased directly by {@link GenAiProviderAliasResolver}.
 */
@Slf4j
public class GoogleProviderResolver implements ProviderResolver {

    public static final String GOOGLE_PROVIDER = "google";
    public static final String GCP_GEN_AI_PROVIDER = "gcp.gen_ai";
    public static final String GOOGLE_VERTEX_AI = "google_vertexai";
    public static final String GOOGLE_AI = "google_ai";

    private static final Set<String> AMBIGUOUS_PROVIDERS = Set.of(GOOGLE_PROVIDER, GCP_GEN_AI_PROVIDER);

    private static final String VERTEX_AI_HOST_MARKER = "aiplatform.googleapis.com";
    private static final String GEMINI_API_HOST_MARKER = "generativelanguage.googleapis.com";

    /** Claims only the Google values that name no specific backend. */
    @Override
    public boolean appliesTo(Resolution resolution) {
        return AMBIGUOUS_PROVIDERS.contains(StringUtils.trimToEmpty(resolution.provider()).toLowerCase(Locale.ROOT));
    }

    /** Returns the canonical Google provider resolved from the {@code server.address} in metadata. */
    @Override
    public Resolution apply(Resolution resolution, ObjectNode metadata) {
        String serverAddress = metadata != null && metadata.hasNonNull(GeneralMappingRules.SERVER_ADDRESS_ATTR)
                ? metadata.get(GeneralMappingRules.SERVER_ADDRESS_ATTR).asText().toLowerCase(Locale.ROOT)
                : "";
        String host = extractHost(serverAddress);

        String resolved;
        boolean recognized = true;
        if (host.endsWith(VERTEX_AI_HOST_MARKER)) {
            resolved = GOOGLE_VERTEX_AI;
        } else if (host.endsWith(GEMINI_API_HOST_MARKER)) {
            resolved = GOOGLE_AI;
        } else {
            resolved = GOOGLE_AI;
            recognized = false;
        }

        log.debug("Resolved provider '{}' to '{}' from {} server.address host '{}'",
                resolution.provider(), resolved, recognized ? "recognized" : "unrecognized (defaulted)", host);
        return new Resolution(resolution.model(), resolved);
    }

    /**
     * Reduces a {@code server.address} to its bare host so the markers can be matched on a domain
     * boundary rather than anywhere in the string — {@code contains} would classify
     * {@code evil-aiplatform.googleapis.com.attacker.test} as Vertex AI.
     * <p>
     * The semconv defines {@code server.address} as a host name, so the value usually carries no
     * scheme and {@link URI} would parse it as a path. Prefixing {@code //} makes it an
     * authority-only reference, which is what lets the JDK strip the port, userinfo and any path
     * the value happens to carry, plus the trailing FQDN dot.
     * <p>
     * {@code getHost()} returns null for authorities the RFC forbids but that resolve in practice —
     * underscores in a hostname being the common case — and the address is attacker-controlled, so
     * an unparseable value falls back to the raw string. That is safe here because the fallback only
     * feeds a {@code endsWith} marker test: a value that reaches it either legitimately ends in a
     * Google marker or matches nothing and defaults.
     */
    private static String extractHost(String serverAddress) {
        String candidate = serverAddress.contains("://") ? serverAddress : "//" + serverAddress;

        try {
            String host = new URI(candidate).getHost();
            return host == null ? serverAddress : StringUtils.removeEnd(host, ".");
        } catch (URISyntaxException e) {
            log.debug("Could not parse server.address '{}' as a URI; matching markers on the raw value",
                    serverAddress, e);
            return serverAddress;
        }
    }
}
