package com.comet.opik.infrastructure.llm.customllm;

/**
 * A token fetch for a custom provider's {@code auth_config} recipe failed. The message is
 * user-facing by contract: it carries the upstream status/body with credential values and tokens
 * already redacted, so callers can surface it verbatim (eval logs, playground, test-connection).
 */
public class AuthTokenException extends RuntimeException {

    public AuthTokenException(String message) {
        super(message);
    }

    public AuthTokenException(String message, Throwable cause) {
        super(message, cause);
    }
}
