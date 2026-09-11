package com.comet.opik.domain.mcpoauth;

import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class McpOAuthScrubService {

    private final @NonNull TransactionTemplate template;
    private final @NonNull OpikConfiguration opikConfig;

    /**
     * Revoked tokens within the rotation grace window must remain queryable, so a duplicate refresh
     * request (RFC 6749 §6 token rotation grace) returns invalid_grant instead of triggering reuse detection.
     */
    public Mono<Void> scrub() {
        return Mono.fromRunnable(() -> {
            Instant now = Instant.now();
            Instant tokenThreshold = now.minus(opikConfig.getMcpOAuth().getRefreshRotationGrace());

            int codes = template.inTransaction(WRITE,
                    handle -> handle.attach(McpOAuthCodeDAO.class).deleteExpired(now));
            int tokens = template.inTransaction(WRITE,
                    handle -> handle.attach(McpOAuthTokenDAO.class).deleteExpiredAndRevoked(tokenThreshold));
            log.info("MCP OAuth scrub: removed '{}' expired codes, '{}' expired/revoked tokens", codes, tokens);
        });
    }
}
