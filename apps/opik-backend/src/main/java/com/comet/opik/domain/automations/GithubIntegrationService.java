package com.comet.opik.domain.automations;

import com.comet.opik.api.automations.GithubIntegration;
import com.comet.opik.infrastructure.EncryptionUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Optional;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/** The workspace's GitHub token: stored encrypted, never returned, decrypted only to post a check. */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class GithubIntegrationService {

    private final @NonNull TransactionTemplate transactionTemplate;

    public void save(@NonNull String workspaceId, @NonNull String userName, @NonNull String token) {
        String encrypted = EncryptionUtils.encryptGcm(token);
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(GithubIntegrationDAO.class).save(workspaceId, encrypted, userName);
            return null;
        });
        log.info("Saved GitHub token for workspace '{}'", workspaceId);
    }

    public void delete(@NonNull String workspaceId) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(GithubIntegrationDAO.class).delete(workspaceId);
            return null;
        });
    }

    public GithubIntegration status(@NonNull String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(GithubIntegrationDAO.class).find(workspaceId))
                .map(row -> GithubIntegration.builder()
                        .configured(true).lastUpdatedAt(row.lastUpdatedAt()).lastUpdatedBy(row.lastUpdatedBy())
                        .build())
                .orElse(GithubIntegration.builder().configured(false).build());
    }

    public Optional<String> token(@NonNull String workspaceId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(GithubIntegrationDAO.class).find(workspaceId))
                .map(row -> EncryptionUtils.decryptGcm(row.token()));
    }
}
