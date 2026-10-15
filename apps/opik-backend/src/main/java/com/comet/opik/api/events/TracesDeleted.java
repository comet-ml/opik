package com.comet.opik.api.events;

import com.comet.opik.infrastructure.events.BaseEvent;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NonNull;
import lombok.experimental.Accessors;
import lombok.experimental.SuperBuilder;

import java.util.Set;
import java.util.UUID;

/**
 * {@code projectId} is required: {@code TraceServiceImpl} resolves every deleted trace's owning project and posts one
 * event per project group (OPIK-7483), so there is no project-less emission to represent. Declaring it non-null is what
 * lets the cascade this event drives — {@code TraceDeletedListener} into {@code SpanService.deleteByTraceIds} — require
 * a project too, rather than re-deciding downstream what to do about an absent one.
 */
@SuperBuilder(toBuilder = true)
@Data
@EqualsAndHashCode(callSuper = true)
@Accessors(fluent = true)
public class TracesDeleted extends BaseEvent {
    private final @NonNull Set<UUID> traceIds;
    private final @NonNull UUID projectId;

    public TracesDeleted(@NonNull Set<UUID> traceIds, @NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        this.traceIds = traceIds;
        this.projectId = projectId;
    }
}
