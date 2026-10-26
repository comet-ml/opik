package com.comet.opik.infrastructure.llm.customllm;

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
        // a {model} in the host names a different destination per request, so only the final URL can be judged;
        // DestinationGuardHttpClientBuilder checks it after substitution
        if (isBlank(baseUrl) || hasHostPlaceholder(baseUrl)) {
            return;
        }
        // a path-level {model} leaves the host as is, so a stand-in keeps the URL parseable
        validateResolved(baseUrl.replace(InterceptingHttpClient.MODEL_PLACEHOLDER, "model"));
    }

    private static boolean hasHostPlaceholder(String baseUrl) {
        int schemeEnd = baseUrl.indexOf("://");
        String rest = schemeEnd < 0 ? baseUrl : baseUrl.substring(schemeEnd + 3);
        int authorityEnd = indexOfAny(rest, "/?#");
        String authority = authorityEnd < 0 ? rest : rest.substring(0, authorityEnd);
        return authority.contains(InterceptingHttpClient.MODEL_PLACEHOLDER);
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
