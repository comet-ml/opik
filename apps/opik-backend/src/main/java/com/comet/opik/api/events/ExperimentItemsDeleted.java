package com.comet.opik.api.events;

import com.comet.opik.domain.ExperimentItemRef;
import com.comet.opik.infrastructure.events.BaseEvent;
import com.google.common.base.Preconditions;
import lombok.Getter;
import lombok.NonNull;
import lombok.experimental.Accessors;
import org.apache.commons.collections4.CollectionUtils;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Getter
@Accessors(fluent = true)
public class ExperimentItemsDeleted extends BaseEvent {
    private final Set<ExperimentItemRef> itemRefs;

    public ExperimentItemsDeleted(Set<ExperimentItemRef> itemRefs, @NonNull String workspaceId,
            @NonNull String userName) {
        super(workspaceId, userName);
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(itemRefs),
                "Argument 'itemRefs' must not be empty");
        this.itemRefs = itemRefs;
    }

    public Set<UUID> experimentIds() {
        return itemRefs.stream().map(ExperimentItemRef::experimentId).collect(Collectors.toUnmodifiableSet());
    }

    public Set<UUID> itemIds() {
        return itemRefs.stream().map(ExperimentItemRef::itemId).collect(Collectors.toUnmodifiableSet());
    }
}
