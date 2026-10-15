package com.comet.opik.infrastructure.inference;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/** Callers leave {@code request_id} null: the client sets one per submit call. */
@Builder(toBuilder = true)
public record BatchDecideRequest(@NonNull String orgId, String workspaceId, String requestId, boolean cache,
        @NonNull List<BatchDecideItem> items) {
}
