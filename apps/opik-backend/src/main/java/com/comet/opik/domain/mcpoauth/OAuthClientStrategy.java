package com.comet.opik.domain.mcpoauth;

import com.google.inject.ImplementedBy;

import java.util.Optional;

/**
 * Resolves OAuth client identity, keyed on the form of the {@code client_id}. The DB strategy handles
 * opaque ids (DCR-registered + seeded). A future CIMD strategy would handle {@code https://} URL ids —
 * add it by switching this binding to a multibinder + dispatcher.
 */
@ImplementedBy(DbOAuthClientStrategy.class)
public interface OAuthClientStrategy {

    boolean supports(String clientId);

    Optional<McpOAuthClient> resolve(String clientId);

    McpOAuthClient register(ClientRegistrationRequest request);
}
