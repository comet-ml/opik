package com.comet.opik.domain.mcpoauth;

import com.google.inject.ImplementedBy;
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

/**
 * Resolves OAuth client identity, keyed on the form of the {@code client_id}.
 * The DB strategy handles opaque ids (DCR-registered + seeded).
 */
@ImplementedBy(DbOAuthClientStrategy.class)
interface OAuthClientStrategy {

    boolean supports(String clientId);

    Optional<McpOAuthClient> resolve(String clientId);

    McpOAuthClient register(ClientRegistrationRequest request);
}

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
                handle -> handle.attach(McpOAuthClientDAO.class).findActiveById(clientId));
    }

    @Override
    public McpOAuthClient register(@NonNull ClientRegistrationRequest request) {
        String clientId = UUID.randomUUID().toString();
        var client = McpOAuthClientMapper.INSTANCE.toClient(request, clientId);

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(McpOAuthClientDAO.class);
            dao.save(client);
            return dao.findActiveById(clientId)
                    .orElseThrow(() -> new IllegalStateException("client not found after registration: " + clientId));
        });
    }

    /**
     * Opaque = no URI scheme (UUIDs, DCR-minted ids, ...). Anything with a scheme is a URL-form id that a
     * CIMD strategy should own.
     */
    private static boolean isOpaque(String clientId) {
        try {
            return !new URI(clientId).isAbsolute();
        } catch (URISyntaxException e) {
            return true;
        }
    }
}
