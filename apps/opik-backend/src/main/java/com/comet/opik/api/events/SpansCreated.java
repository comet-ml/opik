package com.comet.opik.api.events;

import com.comet.opik.api.Span;
import com.comet.opik.infrastructure.events.BaseEvent;
import jakarta.annotation.Nullable;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Accessors(fluent = true)
public class SpansCreated extends BaseEvent {
    private final @NonNull List<Span> spans;
    /**
     * Resolved from {@code RequestContext.WORKSPACE_NAME} at publish time ({@code SpanService}). May be
     * null/blank for callers that don't carry it; consumers fall back to {@code workspaceId}.
     */
    private final @Nullable String workspaceName;
    /**
     * Resolved from {@code RequestContext.CIPX_DEVICE_ID} at publish time ({@code SpanService}): the cipx
     * subscriber runs off the request path and cannot read the request context. Null/blank for every
     * non-device-token caller. Carried for symmetry with trace creation; the cipx span tables do not
     * store it yet.
     */
    private final @Nullable String cipxDeviceId;

    public SpansCreated(@NonNull List<Span> spans, @NonNull String workspaceId, @NonNull String userName) {
        this(spans, workspaceId, userName, null);
    }

    public SpansCreated(@NonNull List<Span> spans, @NonNull String workspaceId, @NonNull String userName,
            @Nullable String workspaceName) {
        this(spans, workspaceId, userName, workspaceName, null);
    }

    public SpansCreated(@NonNull List<Span> spans, @NonNull String workspaceId, @NonNull String userName,
            @Nullable String workspaceName, @Nullable String cipxDeviceId) {
        super(workspaceId, userName);
        this.spans = spans;
        this.workspaceName = workspaceName;
        this.cipxDeviceId = cipxDeviceId;
    }

    public Set<UUID> projectIds() {
        return spans.stream()
                .map(Span::projectId)
                .collect(Collectors.toSet());
    }
}
