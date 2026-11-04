package com.comet.opik.infrastructure.llm.customllm;

import lombok.Getter;

/**
 * A token fetch for a custom provider's {@code auth_config} recipe failed. The message is
 * user-facing by contract: it carries the upstream status/body with credential values and tokens
 * already redacted, so callers can surface it verbatim (eval logs, playground, test-connection).
 */
@Getter
public class AuthTokenException extends RuntimeException {

    /**
     * Whether another fetch may succeed, as the token endpoint was too slow or answered a transient status; a fetch
     * that couldn't reach it carries its transport failure as the cause instead.
     */
    private final boolean retriable;

    public AuthTokenException(String message) {
        this(message, null, false);
    }

    public AuthTokenException(String message, Throwable cause) {
        this(message, cause, false);
    }

    /** A failed fetch, which {@code retriable} says another fetch may succeed at. */
    public AuthTokenException(String message, Throwable cause, boolean retriable) {
        super(message, cause);
        this.retriable = retriable;
    }
}
