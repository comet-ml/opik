package com.comet.opik.api.events;

import com.comet.opik.domain.EntityType;
import lombok.NonNull;

import java.util.Set;
import java.util.UUID;

/**
 * Guardrail results were stored for these traces, all in one project.
 */
public class GuardrailsCreated extends EntityProjectEvent {

    public GuardrailsCreated(@NonNull Set<UUID> traceIds, @NonNull String workspaceId, @NonNull String userName,
            @NonNull UUID projectId) {
        super(traceIds, EntityType.TRACE, workspaceId, userName, projectId);
    }
}
