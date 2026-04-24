package com.comet.opik.infrastructure.llm;

/**
 * Maps OpenAI-compat error codes to HTTP statuses. Shared between native OpenAI
 * and Custom LLM providers so the same error code surfaces as the same status
 * regardless of which provider wrapper produced the error body.
 */
public final class OpenAiCompatStatusCodes {

    private OpenAiCompatStatusCodes() {
    }

    /**
     * Returns the HTTP status for a recognized OpenAI-compat error code, or
     * {@code null} for unrecognized codes. Callers decide how to handle the
     * unrecognized case (e.g., fall back to 400 or 500).
     *
     * @param code the OpenAI-compat error code string (may be {@code null})
     * @return the mapped HTTP status, or {@code null} if the code is unknown
     */
    public static Integer fromCode(String code) {
        if (code == null) {
            return null;
        }
        return switch (code) {
            case "invalid_api_key" -> 401;
            case "internal_error" -> 500;
            case "invalid_request_error" -> 400;
            case "rate_limit_exceeded" -> 429;
            case "insufficient_quota" -> 402;
            case "model_not_found" -> 404;
            default -> null;
        };
    }
}
