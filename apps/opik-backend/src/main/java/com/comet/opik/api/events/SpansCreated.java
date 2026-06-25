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
    // Resolved from RequestContext.WORKSPACE_NAME at publish time (SpanService). May be null/blank
    // for callers that don't carry it; consumers fall back to workspaceId.
    private final @Nullable String workspaceName;

    public SpansCreated(@NonNull List<Span> spans, @NonNull String workspaceId, @NonNull String userName) {
        this(spans, workspaceId, userName, null);
    }

    public SpansCreated(@NonNull List<Span> spans, @NonNull String workspaceId, @NonNull String userName,
            @Nullable String workspaceName) {
        super(workspaceId, userName);
        this.spans = spans;
        this.workspaceName = workspaceName;
    }

    public Set<UUID> projectIds() {
        return spans.stream()
                .map(Span::projectId)
                .collect(Collectors.toSet());
    }
}
