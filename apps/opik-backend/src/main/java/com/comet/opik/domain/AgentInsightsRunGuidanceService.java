package com.comet.opik.domain;

import com.comet.opik.infrastructure.ServiceTogglesConfig;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * The project guidance a queued Agent Insights run carries, and the record of which guidance version the run was
 * enqueued with, so the report it produces can say which guidance its results reflect. Separate from
 * {@link AgentInsightsJobService} because the publisher needs it and that service already depends on the publisher.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AgentInsightsRunGuidanceService {

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull @Config("serviceToggles") ServiceTogglesConfig serviceToggles;

    /**
     * Empty while guidance is not active or the project has no job. Otherwise the current guidance text (null when
     * cleared) and its version, which still identifies the "no guidance" state after a clear.
     */
    public Optional<AgentInsightsJobDAO.RunGuidance> find(@NonNull String workspaceId, @NonNull UUID projectId) {
        if (!serviceToggles.isAgentInsightsGuidanceActive()) {
            return Optional.empty();
        }
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AgentInsightsJobDAO.class).findRunGuidance(workspaceId, projectId));
    }

    /**
     * Called before the run goes on the queue, so the consumer can never see the run ahead of its stamp. A null
     * version (guidance not active) makes the next report record that its results reflect no guidance.
     */
    public void markEnqueued(@NonNull String workspaceId, @NonNull UUID projectId, Integer guidanceVersion) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AgentInsightsJobDAO.class).markRunGuidanceVersion(workspaceId, projectId,
                    guidanceVersion);
            return null;
        });
    }

    /**
     * Clears the stamp a run set, if it is still that run's version: the run never got its guidance (enqueue or
     * trigger failed, or guidance was turned off before it went out). A newer stamp from another run is kept.
     */
    public void clearIfStamped(@NonNull String workspaceId, @NonNull UUID projectId, int guidanceVersion) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AgentInsightsJobDAO.class).clearRunGuidanceVersion(workspaceId, projectId,
                    guidanceVersion);
            return null;
        });
    }
}
