package com.comet.opik.domain.mcpoauth;

import com.comet.opik.api.resources.utils.TestContainersSetup;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.podam.PodamFactoryUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("McpOAuthScrubJob")
@ExtendWith(DropwizardAppExtensionProvider.class)
class McpOAuthScrubJobTest {

    private static final Duration ROTATION_GRACE = Duration.ofMinutes(5);

    private final TestContainersSetup setup = new TestContainersSetup();

    @RegisterApp
    private final TestDropwizardAppExtension APP = setup.APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TransactionTemplate transactionTemplate;
    private McpOAuthScrubJob job;

    @BeforeAll
    void setUpAll(TransactionTemplate transactionTemplate) {
        this.transactionTemplate = transactionTemplate;

        var config = new OpikConfiguration();
        config.getMcpOAuth().setRefreshRotationGrace(ROTATION_GRACE);
        this.job = new McpOAuthScrubJob(transactionTemplate, config);
    }

    @AfterAll
    void tearDownAll() {
        setup.wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.execute("DELETE FROM mcp_oauth_codes");
            handle.execute("DELETE FROM mcp_oauth_tokens");
            return null;
        });
    }

    @Test
    @DisplayName("scrub deletes codes past their expiry and keeps the ones still valid")
    void deletesExpiredCodes() {
        Instant now = Instant.now();
        McpOAuthCode expired = insertCode(now.minus(Duration.ofHours(1)));
        McpOAuthCode valid = insertCode(now.plus(Duration.ofHours(1)));

        job.doJob(null);

        assertThat(codeExists(expired.codeHash())).isFalse();
        assertThat(codeExists(valid.codeHash())).isTrue();
    }

    @Test
    @DisplayName("scrub deletes tokens past their expiry and keeps the ones still valid")
    void deletesExpiredTokens() {
        Instant now = Instant.now();
        McpOAuthToken expired = insertToken(now.minus(Duration.ofHours(1)), null);
        McpOAuthToken valid = insertToken(now.plus(Duration.ofHours(1)), null);

        job.doJob(null);

        assertThat(tokenExists(expired.tokenHash())).isFalse();
        assertThat(tokenExists(valid.tokenHash())).isTrue();
    }

    @Test
    @DisplayName("revoked tokens are deleted only once they fall outside the rotation grace window")
    void deletesRevokedTokensOnlyAfterGrace() {
        Instant now = Instant.now();
        Instant farFuture = now.plus(Duration.ofHours(1));

        McpOAuthToken withinGrace = insertToken(farFuture, now.minus(ROTATION_GRACE.dividedBy(2)));
        McpOAuthToken pastGrace = insertToken(farFuture, now.minus(ROTATION_GRACE.plusMinutes(1)));

        job.doJob(null);

        assertThat(tokenExists(withinGrace.tokenHash())).isTrue();
        assertThat(tokenExists(pastGrace.tokenHash())).isFalse();
    }

    private McpOAuthCode insertCode(Instant expiresAt) {
        McpOAuthCode code = factory.manufacturePojo(McpOAuthCode.class).toBuilder()
                .id(UUID.randomUUID().toString())
                .codeHash(randomHash())
                .clientId(UUID.randomUUID().toString())
                .codeChallengeMethod("S256")
                .expiresAt(expiresAt)
                .build();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(McpOAuthCodeDAO.class).save(code);
            return null;
        });

        return code;
    }

    private McpOAuthToken insertToken(Instant expiresAt, Instant revokedAt) {
        McpOAuthToken token = factory.manufacturePojo(McpOAuthToken.class).toBuilder()
                .id(UUID.randomUUID().toString())
                .tokenHash(randomHash())
                .type(McpOAuthToken.TYPE_ACCESS)
                .clientId(UUID.randomUUID().toString())
                .familyId(UUID.randomUUID().toString())
                .rotatedFromId(null)
                .expiresAt(expiresAt)
                .build();

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(McpOAuthTokenDAO.class).save(token);
            if (revokedAt != null) {
                handle.createUpdate("UPDATE mcp_oauth_tokens SET revoked_at = :revokedAt WHERE id = :id")
                        .bind("revokedAt", revokedAt)
                        .bind("id", token.id())
                        .execute();
            }
            return null;
        });

        return token;
    }

    private boolean codeExists(String codeHash) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpOAuthCodeDAO.class).fetch(codeHash).isPresent());
    }

    private boolean tokenExists(String tokenHash) {
        return transactionTemplate
                .inTransaction(handle -> handle.attach(McpOAuthTokenDAO.class).fetch(tokenHash).isPresent());
    }

    private static String randomHash() {
        return RandomStringUtils.secure().nextAlphanumeric(64);
    }
}
