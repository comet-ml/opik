package com.comet.opik.infrastructure.inference;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.Arrays;
import java.util.Optional;

/** The opik-inference v1 error codes, with the HTTP status and retryability {@code api/v1.json} gives each. */
@Getter
@RequiredArgsConstructor
public enum InferenceErrorCode {
    UNAUTHORIZED("unauthorized", 401, false),
    ORG_NOT_ALLOWED("org_not_allowed", 403, false),
    INVALID_REQUEST("invalid_request", 400, false),
    INVALID_SCHEMA("invalid_schema", 400, false),
    INPUT_TOO_LONG("input_too_long", 413, false),
    NOT_FOUND("not_found", 404, false),
    QUOTA_EXCEEDED("quota_exceeded", 429, true),
    OVERLOADED("overloaded", 503, true),
    DEADLINE_EXCEEDED("deadline_exceeded", 504, true),
    UPSTREAM_ERROR("upstream_error", 502, true),
    SCHEMA_VIOLATION("schema_violation", 500, true);

    private final String value;
    private final int httpStatus;
    private final boolean retryable;

    public static Optional<InferenceErrorCode> find(String value) {
        return Arrays.stream(values())
                .filter(code -> code.value.equals(value))
                .findFirst();
    }
}
