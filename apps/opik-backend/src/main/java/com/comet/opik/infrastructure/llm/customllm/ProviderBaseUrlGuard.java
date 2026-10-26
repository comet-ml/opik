package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;

import static org.apache.commons.lang3.StringUtils.indexOfAny;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Destination check for user-configured LLM provider base URLs, applied on save and before outbound calls.
 * Plain http stays allowed: self-hosted model servers commonly run without TLS.
 */
public class ProviderBaseUrlGuard {

    private final DestinationGuard destinationGuard;

    public ProviderBaseUrlGuard(@NonNull DestinationGuard.Mode mode) {
        this.destinationGuard = DestinationGuard.builder()
                .mode(mode)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .build();
    }

    /**
     * @throws BadRequestException with a user-facing message when the destination is refused
     */
    public void validate(String baseUrl) {
        if (isBlank(baseUrl)) {
            return;
        }
        // a path-level {model} leaves the host as is, so a stand-in keeps the URL parseable
        validateResolved(baseUrl.replace(InterceptingHttpClient.MODEL_PLACEHOLDER, "model"));
    }

    /**
     * As {@link #validate(String)}, except a {@code {model}} in the host of a provider whose client substitutes it:
     * that host names a different destination per request, so only DestinationGuardHttpClientBuilder, checking the
     * substituted URL, can judge it. Every other provider sends the URL as saved, so it must pass here.
     */
    public void validate(String baseUrl, @NonNull LlmProvider provider) {
        if (provider.routesThroughCustomLlmClient() && !isBlank(baseUrl) && hasModelPlaceholderInHost(baseUrl)) {
            return;
        }
        validate(baseUrl);
    }

    /** Only a host placeholder defers the save-time check; elsewhere the "model" stand-in keeps the saved host. */
    static boolean hasModelPlaceholderInHost(String baseUrl) {
        String authority = authority(baseUrl);
        String hostAndPort = authority.substring(authority.lastIndexOf('@') + 1);
        String host = hostAndPort.startsWith("[")
                ? hostAndPort.substring(0, hostAndPort.indexOf(']') + 1)
                : hostAndPort.split(":", 2)[0];
        return host.contains(InterceptingHttpClient.MODEL_PLACEHOLDER);
    }

    /** Userinfo, host or port: a raw substitution anywhere here can move the request to another host. */
    static boolean hasModelPlaceholderInAuthority(String baseUrl) {
        return authority(baseUrl).contains(InterceptingHttpClient.MODEL_PLACEHOLDER);
    }

    private static String authority(String url) {
        int schemeEnd = url.indexOf("://");
        String rest = schemeEnd < 0 ? url : url.substring(schemeEnd + 3);
        int authorityEnd = indexOfAny(rest, "/?#");
        return authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
    }

    void validateResolved(String url) {
        try {
            destinationGuard.validate(url);
        } catch (DestinationGuardException exception) {
            throw new BadRequestException(exception.getMessage()
                    + " (self-hosted deployments with providers on internal networks can set LLM_PROVIDER_DESTINATION_GUARD=relaxed)",
                    exception);
        }
    }
}
