package com.comet.opik.api.events;

import com.comet.opik.api.Span;
import com.comet.opik.infrastructure.events.BaseEvent;
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

    public SpansCreated(@NonNull List<Span> spans, @NonNull String workspaceId, @NonNull String userName) {
        super(workspaceId, userName);
        this.spans = spans;
    }

    public Set<UUID> projectIds() {
        return spans.stream()
                .map(Span::projectId)
                .collect(Collectors.toSet());
    }
}
