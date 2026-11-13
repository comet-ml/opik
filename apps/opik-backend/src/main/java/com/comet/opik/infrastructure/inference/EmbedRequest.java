package com.comet.opik.infrastructure.inference;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * {@code POST /v1/embed}: 1 to {@value InferenceClient#MAX_EMBED_INPUTS} non-blank texts. Callers set neither
 * {@code request_id} nor {@code deadline_ms}: {@link InferenceClient#embed} overwrites both, with a new request id per
 * call and the time left before its deadline, at most the service's maximum.
 */
@Builder(toBuilder = true)
public record EmbedRequest(@NonNull String orgId, String requestId, Long deadlineMs, boolean cache,
        @NonNull List<String> inputs) {

    public EmbedRequest {
        inputs = List.copyOf(inputs);
    }
}
