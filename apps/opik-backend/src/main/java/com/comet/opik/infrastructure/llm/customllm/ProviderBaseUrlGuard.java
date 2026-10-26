package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardException;
import jakarta.ws.rs.BadRequestException;
import lombok.Builder;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;

import static org.apache.commons.lang3.StringUtils.contains;
import static org.apache.commons.lang3.StringUtils.isBlank;

/**
 * Destination check for user-configured LLM provider base URLs, applied on save and before outbound calls.
 * Stateless, so a single instance is shared.
 */
@Slf4j
public class ProviderBaseUrlGuard {

    static final String REFUSED = "the provider base_url was refused: it must be a valid URL with an allowed scheme that"
            + " resolves to a public address (self-hosted deployments with providers on internal networks can set"
            + " LLM_PROVIDER_DESTINATION_GUARD=relaxed)";

    /** Swapped in for {@code {model}}, whose braces {@link URI} rejects, so the rest of the URL can be parsed. */
    private static final String PLACEHOLDER_STAND_IN = "opik-model-placeholder";

    private final DestinationGuard.Mode mode;
    private final DestinationGuard.Scheme scheme;
    private final DestinationGuard destinationGuard;

    @Builder(toBuilder = true)
    private ProviderBaseUrlGuard(@NonNull DestinationGuard.Mode mode, @NonNull DestinationGuard.Scheme scheme) {
        this.mode = mode;
        this.scheme = scheme;
        this.destinationGuard = DestinationGuard.builder().mode(mode).scheme(scheme).build();
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
     * that host names a different destination per request, so only {@link DestinationGuardHttpClient}, checking the
     * substituted URL, can judge it. Every other provider sends the URL as saved, so it must pass here.
     */
    public void validate(String baseUrl, @NonNull LlmProvider provider) {
        if (provider.routesThroughCustomLlmClient() && !isBlank(baseUrl) && hasModelPlaceholderInHost(baseUrl)) {
            return;
        }
        validate(baseUrl);
    }

    /** Only a host placeholder defers the save-time check; elsewhere the "model" stand-in keeps the saved host. */
    static boolean hasModelPlaceholderInHost(@NonNull String baseUrl) {
        return parseWithStandIn(baseUrl)
                .map(uri -> contains(uri.getHost(), PLACEHOLDER_STAND_IN))
                .orElse(false);
    }

    /**
     * Userinfo, host or port: a raw substitution anywhere here can move the request to another host. A URL that
     * can't be parsed counts as one, so the caller takes the restrictive path.
     */
    static boolean hasModelPlaceholderInAuthority(@NonNull String baseUrl) {
        return parseWithStandIn(baseUrl)
                .map(uri -> contains(uri.getRawAuthority(), PLACEHOLDER_STAND_IN))
                .orElse(true);
    }

    private static Optional<URI> parseWithStandIn(String url) {
        try {
            return Optional.of(new URI(url.replace(InterceptingHttpClient.MODEL_PLACEHOLDER, PLACEHOLDER_STAND_IN)));
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    void validateResolved(@NonNull String url) {
        if (!isAllowed(url)) {
            throw new BadRequestException(REFUSED);
        }
    }

    /**
     * As {@link #validateResolved(String)}, for a request about to be sent.
     *
     * @throws ProviderRequestRefusedException when the destination is refused
     */
    void validateRequest(@NonNull String url) {
        if (!isAllowed(url)) {
            throw new ProviderRequestRefusedException(REFUSED);
        }
    }

    private boolean isAllowed(String url) {
        try {
            destinationGuard.validate(url);
            return true;
        } catch (DestinationGuardException exception) {
            // the guard's detail stays in the logs: it tells a caller how our network resolves their host
            log.warn("Refused LLM provider destination", exception);
            return false;
        }
    }
}
