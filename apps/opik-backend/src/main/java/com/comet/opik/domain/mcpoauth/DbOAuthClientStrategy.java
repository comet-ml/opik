package com.comet.opik.domain.mcpoauth;

import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
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
        String clientId = UUID.randomUUID().toString();
        var client = McpOAuthClientMapper.INSTANCE.toClient(request, clientId);

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(McpOAuthClientDAO.class);
            dao.save(client);
            return dao.findActiveById(clientId);
        });
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
