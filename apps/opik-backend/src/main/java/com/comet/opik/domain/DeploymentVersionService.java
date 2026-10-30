package com.comet.opik.domain;

import com.comet.opik.api.DeploymentVersion;
import com.comet.opik.api.DeploymentVersion.DeploymentVersionHistory;
import com.comet.opik.infrastructure.AppMetadataService;
import io.dropwizard.lifecycle.Managed;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Records which Opik versions this install has run, and serves that history back.
 * <p>
 * The history is written on startup rather than at release time because it answers a per-install question —
 * "when was <em>this</em> deployment upgraded?" — which no upstream release feed can answer for a self-hosted
 * or STSaaS environment.
 */
@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class DeploymentVersionService implements Managed {

    private final @NonNull TransactionTemplate template;
    private final @NonNull AppMetadataService appMetadataService;

    /**
     * Records the running version on boot.
     * <p>
     * Failures here are logged and swallowed on purpose: release-note metadata is not worth refusing to serve
     * traffic for, so a database that is unreachable at this moment must not take the application down with it.
     */
    @Override
    public void start() {
        var version = appMetadataService.getVersion();

        try {
            int inserted = template.inTransaction(WRITE,
                    handle -> handle.attach(DeploymentVersionDAO.class).recordVersion(version));

            if (inserted > 0) {
                log.info("Recorded first start of Opik version '{}' on this deployment", version);
            } else {
                log.debug("Opik version '{}' was already recorded for this deployment", version);
            }
        } catch (Exception exception) {
            log.error("Failed to record deployment version '{}'; continuing startup", version, exception);
        }
    }

    public DeploymentVersionHistory getHistory() {
        List<DeploymentVersion> versions = template.inTransaction(READ_ONLY,
                handle -> handle.attach(DeploymentVersionDAO.class).findAll());

        return DeploymentVersionHistory.builder()
                .currentVersion(appMetadataService.getVersion())
                .versions(versions)
                .build();
    }
}
