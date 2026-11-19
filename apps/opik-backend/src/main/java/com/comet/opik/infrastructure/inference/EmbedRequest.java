package com.comet.opik.infrastructure.inference;

import lombok.Builder;
import lombok.NonNull;

import java.util.List;

/**
 * {@code POST /v1/embed}: 1 to {@value InferenceClient#MAX_EMBED_INPUTS} texts. {@link InferenceClient#embed} adds the
 * {@code request_id} and {@code deadline_ms} of each call to the body it sends.
 */
@Builder(toBuilder = true)
public record EmbedRequest(@NonNull String orgId, boolean cache, @NonNull List<String> inputs) {

    public EmbedRequest {
        inputs = List.copyOf(inputs);
    }
}
