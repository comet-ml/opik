package com.comet.opik.api.events;

import com.comet.opik.api.SpanUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

/**
 * Fired on span update so the Cost Intelligence subscriber can refresh the cipx_spend table. Carries the
 * affected span ids plus the SpanUpdate applied to all of them. project_id is part of the cipx_spend merge
 * key but neither {@code spanUpdate.projectId()} (nullable) nor the batch update path carries it, so the
 * subscriber resolves span -> project from the persisted spans, off the request path. Span creation reuses
 * {@link SpansCreated}. Posted on every span update; the subscriber filters to cipx-call spans so the
 * metadata parse stays off the ingestion flow.
 */
@Getter
@Accessors(fluent = true)
public class SpanCostIntelligenceChanged extends BaseEvent {

    private final @NonNull Set<UUID> spanIds;
    private final @NonNull SpanUpdate spanUpdate;

    public SpanCostIntelligenceChanged(@NonNull Set<UUID> spanIds, @NonNull SpanUpdate spanUpdate,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.spanIds = spanIds;
        this.spanUpdate = spanUpdate;
    }
}
