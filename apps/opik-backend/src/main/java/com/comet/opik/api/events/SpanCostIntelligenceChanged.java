package com.comet.opik.api.events;

import com.comet.opik.api.SpanUpdate;
import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.Set;
import java.util.UUID;

/**
 * Fired on span update so the Cost Intelligence subscriber can refresh the cipx_spends table. Carries the
 * affected span ids plus the SpanUpdate applied to all of them. project_id and trace_id are part of the
 * cipx_spends merge key but the update carries neither per span (a batch reuses one SpanUpdate for spans
 * that may span different traces), so the subscriber resolves span -> (project, trace) from the persisted
 * spans, off the request path. Span creation reuses
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
