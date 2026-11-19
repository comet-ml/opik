package com.comet.opik.infrastructure.inference;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** The client adds a {@code request_id} per submit call to the body it sends. */
@Builder(toBuilder = true)
public record BatchGenerateRequest(@NonNull String orgId, String workspaceId, boolean cache,
        @NonNull List<BatchGenerateItem> items) {

    public BatchGenerateRequest {
        items = List.copyOf(items);
    }
}
