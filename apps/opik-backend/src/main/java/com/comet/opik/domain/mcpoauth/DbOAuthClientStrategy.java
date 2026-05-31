package com.comet.opik.domain.mcpoauth;

import com.comet.opik.domain.IdGenerator;
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

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DbOAuthClientStrategy implements OAuthClientStrategy {

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;

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

        String clientId = idGenerator.generateId().toString();
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

    private void validate(ClientRegistrationRequest request) {
        if (CollectionUtils.isEmpty(request.redirectUris())) {
            throw new BadRequestException("redirect_uris is required");
        }
        for (String redirectUri : request.redirectUris()) {
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
