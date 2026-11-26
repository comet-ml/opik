package com.comet.opik.domain.topics;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.annotation.Nullable;
import lombok.Builder;
import lombok.NonNull;

/**
 * One LLM call an algorithm asks for. The run makes it, with the deadline of its {@code call}. {@code tag} names the
 * call in its request id ({@code <runId>:<tag>}) and the logs; it defaults to the call's value. Two calls of one kind in
 * one run need their own tags ({@code naming}, {@code naming_new}).
 */
@Builder(toBuilder = true)
record LlmRequest(@NonNull LlmCall call, @Nullable String tag, @NonNull String prompt, @NonNull JsonNode schema,
        int maxTokens) {

    LlmRequest {
        tag = tag == null ? call.getValue() : tag;
    }
}
