package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;

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
        // {model} is substituted per request; the final URL is checked again by the custom LLM client
        validateResolved(baseUrl.replace(InterceptingHttpClient.MODEL_PLACEHOLDER, "model"));
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
