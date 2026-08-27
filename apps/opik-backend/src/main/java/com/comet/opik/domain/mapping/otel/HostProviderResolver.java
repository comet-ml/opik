package com.comet.opik.domain.mapping.otel;

import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Normalizes OTel spans that report a provider host (e.g. {@code api.cerebras.ai},
 * {@code api.x.ai}, {@code api.deepseek.com}) instead of the canonical provider name.
 * <p>
 * When an OpenAI-compatible SDK is pointed at a provider via its base URL, some
 * instrumentations emit the host in {@code gen_ai.system} rather than the canonical
 * LiteLLM provider name. {@code CostService.findModelPrice} keys on the canonical name,
 * so the lookup fails and the span is billed at zero. This resolver strips the
 * {@code api.} prefix and public-suffix tail from hostnames whose second-level domain
 * label matches a known canonical provider, replacing the host with the canonical name.
 * <p>
 * Only providers already registered in {@code CostService.PROVIDERS_MAPPING} are
 * recognized. Unknown hosts are returned unchanged.
 */
@UtilityClass
@Slf4j
public class HostProviderResolver {

    // Matches optional "api." prefix + label + optional ".tld" (e.g. "api.cerebras.ai" or "api.x.ai")
    private static final Pattern HOST_PATTERN = Pattern.compile(
            "^(?:api\\.)?([a-z0-9][a-z0-9_-]*)(?:\\.[a-z]{2,})$",
            Pattern.CASE_INSENSITIVE);

    // Canonical provider names that are known to use host-style identifiers.
    // Only list providers that appear in CostService.PROVIDERS_MAPPING and are
    // observed to emit host-style gen_ai.system values.
    private static final Set<String> KNOWN_PROVIDERS = Set.of(
            "cerebras",
            "groq",
            "nebius",
            "sambanova",
            "perplexity",
            "mistral",
            "fireworks_ai",
            "deepseek",
            "xai",
            "moonshot",
            "ai21",
            "morph",
            "inception"
    );

    // Full-host aliases for providers whose canonical name cannot be inferred from the
    // second-level domain label alone. Keyed on the normalized hostname (lower-case,
    // with or without the "api." prefix stripped). Only exact-match entries are applied.
    private static final java.util.Map<String, String> HOST_ALIASES = java.util.Map.of(
            "api.x.ai", "xai",
            "x.ai", "xai"
    );

    /**
     * If {@code provider} looks like a hostname whose extracted label matches a known
     * canonical provider, return the canonical name. Otherwise return {@code provider}
     * unchanged.
     *
     * @param provider the raw provider string from the OTel span
     * @param metadata span metadata (unused here but kept for API symmetry with other resolvers)
     * @return canonical provider name, or the original {@code provider}
     */
    public static @Nullable String resolve(@Nullable String provider, ObjectNode metadata) {
        if (StringUtils.isBlank(provider)) {
            return provider;
        }

        String normalized = provider.toLowerCase(Locale.ROOT).trim();

        // Fast-path: if it does not contain a dot it cannot be a host
        if (!normalized.contains(".")) {
            return provider;
        }

        // Check full-host aliases first (exact match on the normalized hostname).
        // These cover cases where the SLD label alone is ambiguous (e.g. "x" in "api.x.ai").
        String hostAlias = HOST_ALIASES.get(normalized);
        if (hostAlias != null) {
            log.debug("Resolved host-style provider: provider='{}' canonical='{}'", provider, hostAlias);
            return hostAlias;
        }

        Matcher m = HOST_PATTERN.matcher(normalized);
        if (!m.matches()) {
            return provider;
        }

        String label = m.group(1);

        if (KNOWN_PROVIDERS.contains(label)) {
            log.debug("Resolved host-style provider: provider='{}' canonical='{}'", provider, label);
            return label;
        }

        log.debug("Host-style provider not recognized: provider='{}' label='{}'", provider, label);
        return provider;
    }
}
