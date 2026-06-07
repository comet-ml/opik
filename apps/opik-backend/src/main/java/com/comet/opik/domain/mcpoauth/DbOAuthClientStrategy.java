package com.comet.opik.domain.mcpoauth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DbOAuthClientStrategy implements OAuthClientStrategy {

    private final @NonNull TransactionTemplate template;

    @Override
    public boolean supports(@NonNull String clientId) {
        return isOpaque(clientId);
    }

    @Override
    public Optional<McpOAuthClient> resolve(@NonNull String clientId) {
        return template.inTransaction(READ_ONLY,
                handle -> handle.attach(McpOAuthClientDAO.class).fetchActive(clientId));
    }

    @Override
    public McpOAuthClient register(@NonNull ClientRegistrationRequest request) {
        validate(request);

        String clientId = UUID.randomUUID().toString();
        var client = McpOAuthClient.builder()
                .clientId(clientId)
                .name(StringUtils.defaultIfBlank(request.clientName(), clientId))
                .redirectUris(request.redirectUris())
                .logoUri(request.logoUri())
                .build();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(McpOAuthClientDAO.class);
            dao.save(client);
            return dao.findActiveById(clientId);
        });
    }

    // Field caps mirror the mcp_oauth_clients column sizes so oversized DCR metadata fails as a
    // proper invalid_client_metadata 400 instead of a raw SQL error.
    private static final int MAX_REDIRECT_URIS = 10;
    private static final int MAX_NAME_LENGTH = 255;
    private static final int MAX_URI_LENGTH = 2048;

    private void validate(ClientRegistrationRequest request) {
        if (CollectionUtils.isEmpty(request.redirectUris())) {
            throw new BadRequestException("redirect_uris is required");
        }
        if (request.redirectUris().size() > MAX_REDIRECT_URIS) {
            throw new BadRequestException("too many redirect_uris (max %d)".formatted(MAX_REDIRECT_URIS));
        }
        if (request.clientName() != null && request.clientName().length() > MAX_NAME_LENGTH) {
            throw new BadRequestException("client_name too long (max %d)".formatted(MAX_NAME_LENGTH));
        }
        if (request.logoUri() != null && request.logoUri().length() > MAX_URI_LENGTH) {
            throw new BadRequestException("logo_uri too long (max %d)".formatted(MAX_URI_LENGTH));
        }
        for (String redirectUri : request.redirectUris()) {
            if (redirectUri.length() > MAX_URI_LENGTH) {
                throw new BadRequestException("redirect_uri too long (max %d)".formatted(MAX_URI_LENGTH));
            }
            try {
                if (!new URI(redirectUri).isAbsolute()) {
                    throw new BadRequestException("redirect_uri must be absolute: " + redirectUri);
                }
            } catch (URISyntaxException e) {
                throw new BadRequestException("invalid redirect_uri: " + redirectUri);
            }
        }
    }

    // Opaque = no URI scheme (UUIDs, DCR-minted ids, ...). Anything with a scheme is a URL-form id that a
    // CIMD strategy would own; the https-only / SSRF gate lives there, not here. Scheme-agnostic and
    // case-insensitive, so we never need to enumerate or prefix-match protocols.
    private static boolean isOpaque(String clientId) {
        try {
            return !new URI(clientId).isAbsolute();
        } catch (URISyntaxException e) {
            return true;
        }
    }
}
