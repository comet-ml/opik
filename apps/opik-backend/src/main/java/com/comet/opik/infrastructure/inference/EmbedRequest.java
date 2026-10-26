package com.comet.opik.infrastructure.inference;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * {@code POST /v1/embed}: 1 to {@value InferenceClient#MAX_EMBED_INPUTS} non-blank texts. Callers leave
 * {@code request_id} and {@code deadline_ms} null: the client sets one request id per call and the deadline left.
 */
@Builder(toBuilder = true)
public record EmbedRequest(@NonNull String orgId, String workspaceId, String requestId, Long deadlineMs,
        boolean cache, @NonNull List<String> inputs) {

    public EmbedRequest {
        inputs = List.copyOf(inputs);
    }
}
