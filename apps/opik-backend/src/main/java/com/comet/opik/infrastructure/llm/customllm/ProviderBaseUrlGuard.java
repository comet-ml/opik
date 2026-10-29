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
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;

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

    /**
     * Swapped in for {@code {model}}, whose braces {@link URI} rejects, so the rest of the URL can be parsed. Two of
     * them: a part that differs between the two parses holds a real placeholder, whatever literal text it contains.
     */
    private static final String STAND_IN = "opik-model-a";
    private static final String OTHER_STAND_IN = "opik-model-b";

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
     * substituted URL, can resolve it. The rest of the URL, the scheme included, is still checked here. Every other
     * provider sends the URL as saved, so it must pass in full.
     */
    public void validate(String baseUrl, @NonNull LlmProvider provider) {
        if (provider.routesThroughCustomLlmClient() && !isBlank(baseUrl) && hasModelPlaceholderInHost(baseUrl)) {
            var withStandIn = baseUrl.replace(InterceptingHttpClient.MODEL_PLACEHOLDER, STAND_IN);
            if (!isAllowed(() -> destinationGuard.validateWithoutResolving(withStandIn))) {
                throw new BadRequestException(REFUSED);
            }
            return;
        }
        validate(baseUrl);
    }

    /** Only a host placeholder defers the save-time check; elsewhere the "model" stand-in keeps the saved host. */
    static boolean hasModelPlaceholderInHost(@NonNull String baseUrl) {
        return differs(baseUrl, URI::getHost).orElse(false);
    }

    /**
     * Userinfo, host or port: a raw substitution anywhere here can move the request to another host. A URL that
     * can't be parsed counts as one, so the caller takes the restrictive path.
     */
    static boolean hasModelPlaceholderInAuthority(@NonNull String baseUrl) {
        return differs(baseUrl, URI::getRawAuthority).orElse(true);
    }

    /** Whether the URL part changes with the stand-in, or empty when the URL can't be parsed. */
    private static Optional<Boolean> differs(String url, Function<URI, String> part) {
        return parseWithStandIn(url, STAND_IN)
                .flatMap(uri -> parseWithStandIn(url, OTHER_STAND_IN)
                        .map(other -> !Objects.equals(part.apply(uri), part.apply(other))));
    }

    private static Optional<URI> parseWithStandIn(String url, String standIn) {
        try {
            return Optional.of(new URI(url.replace(InterceptingHttpClient.MODEL_PLACEHOLDER, standIn)));
        } catch (URISyntaxException exception) {
            return Optional.empty();
        }
    }

    /** For a client that also applies the policy at connect time, to each address it connects to. */
    public DestinationGuard destinationGuard() {
        return destinationGuard;
    }

    void validateResolved(@NonNull String url) {
        if (!isAllowed(() -> destinationGuard.validate(url))) {
            throw new BadRequestException(REFUSED);
        }
    }

    /**
     * As {@link #validateResolved(String)}, for a request about to be sent.
     *
     * @throws ProviderRequestRefusedException when the destination is refused
     */
    void validateRequest(@NonNull String url) {
        if (!isAllowed(() -> destinationGuard.validate(url))) {
            throw new ProviderRequestRefusedException(ProviderRequestRefusedException.Reason.DESTINATION);
        }
    }

    private boolean isAllowed(Runnable check) {
        try {
            check.run();
            return true;
        } catch (DestinationGuardException exception) {
            // no exception detail anywhere: it describes a user-supplied URL and how our network resolves it
            log.warn("Refused LLM provider destination");
            return false;
        }
    }
}
