package com.comet.opik.domain;

import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.ForbiddenException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

/**
 * The gate every guardrails endpoint shares: the deployment runs guardrails, and the organization of the caller's
 * workspace has them enabled.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class GuardrailsAccess {

    /** FR-5: what a workspace whose organization is not enabled is told. */
    public static final String NOT_ENABLED_MESSAGE = "Guardrails is available on the Enterprise plan";

    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull WorkspaceFeaturesService workspaceFeaturesService;

    /**
     * @throws ForbiddenException if the deployment does not run guardrails or the organization does not have them
     */
    public void check() {
        featureFlags.checkGuardrailsValidationsEnabled();
        if (!workspaceFeaturesService.isEnabled(requestContext.get(), WorkspaceFeature.GUARDRAILS)) {
            throw new ForbiddenException(NOT_ENABLED_MESSAGE);
        }
    }
}
