package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;

import java.util.UUID;

/**
 * A span row written by an update that arrived before its create (PATCH-before-create). Unlike {@link SpansUpdated},
 * it carries the span's own id and project, which is what span_weeks needs: such an id may be past-dated, so its week
 * cannot be assumed from the create path.
 */
@Getter
@Accessors(fluent = true)
public class SpanInsertedByUpdate extends BaseEvent {
    private final @NonNull UUID spanId;
    private final @NonNull UUID spanTraceId;
    private final @NonNull UUID projectId;

    public SpanInsertedByUpdate(@NonNull UUID spanId, @NonNull UUID spanTraceId, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.spanId = spanId;
        this.spanTraceId = spanTraceId;
        this.projectId = projectId;
    }
}
