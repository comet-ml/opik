package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.NonNull;

import java.util.Map;

/** Response of the synchronous {@code POST /v1/decide}. {@code result.answers} is keyed like the request's questions. */
@Builder(toBuilder = true)
@JsonIgnoreProperties(ignoreUnknown = true)
public record DecideResponse(String requestId, Usage usage, boolean cacheHit, long latencyMs,
        @NonNull Result result) {

    @Builder(toBuilder = true)
    @JsonIgnoreProperties(ignoreUnknown = true)
    public record Result(@NonNull Map<String, Answer> answers) {
    }
}
