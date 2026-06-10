package com.comet.opik.domain.mcpoauth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class OAuthClientService {

    private final @NonNull OAuthClientStrategy strategy;

    public Optional<McpOAuthClient> resolve(@NonNull String clientId) {
        return strategy.supports(clientId) ? strategy.resolve(clientId) : Optional.empty();
    }

    public McpOAuthClient register(@NonNull ClientRegistrationRequest request) {
        return strategy.register(request);
    }

    public boolean matchesRedirectUri(@NonNull McpOAuthClient client, @NonNull String requestedRedirectUri) {
        Set<String> allowed = client.redirectUris();
        if (allowed == null) {
            return false;
        }
        return allowed.stream()
                .anyMatch(candidate -> candidate.equals(requestedRedirectUri)
                        || isLoopbackMatch(candidate, requestedRedirectUri));
    }

    // RFC 8252 7.3: native-app loopback redirects may use any port, so match scheme+host+path and ignore the port.
    private static boolean isLoopbackMatch(String registered, String requested) {
        try {
            URI a = new URI(registered);
            URI b = new URI(requested);
            return isLoopback(a) && isLoopback(b)
                    && Objects.equals(a.getScheme(), b.getScheme())
                    && Objects.equals(a.getHost(), b.getHost())
                    && Objects.equals(a.getPath(), b.getPath());
        } catch (URISyntaxException e) {
            return false;
        }
    }

    private static boolean isLoopback(URI uri) {
        String host = uri.getHost();
        return "http".equals(uri.getScheme())
                && ("127.0.0.1".equals(host) || "localhost".equals(host) || "[::1]".equals(host));
    }
}
