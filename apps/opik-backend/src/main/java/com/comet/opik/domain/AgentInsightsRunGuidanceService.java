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

    /** Stamped for a run that carries no guidance; same as guidance_version 0, "never saved". */
    public static final int NO_GUIDANCE = 0;

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
     * version (guidance not active) is stamped as {@link #NO_GUIDANCE}: NULL stays "no run pending".
     */
    public void markEnqueued(@NonNull String workspaceId, @NonNull UUID projectId, Integer guidanceVersion) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AgentInsightsJobDAO.class).markRunGuidanceVersion(workspaceId, projectId,
                    stampOf(guidanceVersion));
            return null;
        });
    }

    /** The stamp a run queued with this message version carries. */
    public static int stampOf(Integer guidanceVersion) {
        return guidanceVersion == null ? NO_GUIDANCE : guidanceVersion;
    }

    /**
     * Replaces the stamp a run set, if it is still that run's: with {@link #NO_GUIDANCE} when the run goes out
     * without its guidance, with null when it never runs (enqueue or trigger failed). Another run's stamp is kept.
     */
    public void replaceIfStamped(@NonNull String workspaceId, @NonNull UUID projectId, int stamped,
            Integer replacement) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AgentInsightsJobDAO.class).swapRunGuidanceVersion(workspaceId, projectId, stamped,
                    replacement);
            return null;
        });
    }
}
