package com.comet.opik.api.events;

import com.comet.opik.api.SpanUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Map;
import java.util.UUID;

/**
 * Fired on span update so the Cost Intelligence subscriber can refresh the cipx_spend table. Carries
 * the affected spans as spanId -> resolved projectId (project_id is part of the cipx_spend merge key,
 * and the resolved project is authoritative — {@code spanUpdate.projectId()} may be null) plus the
 * SpanUpdate applied to all of them. Span creation reuses {@link SpansCreated}. Posted only when the
 * update's metadata contains cipx data.
 */
@Getter
@Accessors(fluent = true)
public class SpanCostIntelligenceChanged extends BaseEvent {

    private final @NonNull Map<UUID, UUID> spanProjectIds;
    private final @NonNull SpanUpdate spanUpdate;

    public SpanCostIntelligenceChanged(@NonNull Map<UUID, UUID> spanProjectIds, @NonNull SpanUpdate spanUpdate,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.spanProjectIds = spanProjectIds;
        this.spanUpdate = spanUpdate;
    }
}
