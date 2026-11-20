package com.comet.opik.infrastructure.inference;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * The pool a {@code /v1/generate} call runs in. {@code realtime} is for a user waiting on the answer; {@code batch} is
 * for background work, which takes a longer deadline and yields to realtime calls. The most deadline each takes is in
 * {@code inference.limits}.
 */
@Getter
@RequiredArgsConstructor
public enum GenerateLane {
    REALTIME("realtime"),
    BATCH("batch");

    @JsonValue
    private final String value;
}
