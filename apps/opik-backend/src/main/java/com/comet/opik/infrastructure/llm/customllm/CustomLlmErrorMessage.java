package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.StringUtils;

/// Deserializes error bodies from OpenAI-compatible Custom LLM providers and
/// maps them into Opik's `ErrorMessage` DTO.
///
/// Two error shapes are supported out of the box:
///   - Text shape: `{"error": "message"}` — historical behavior; the string
///     value becomes the user-visible message with HTTP 400.
///   - Object shape: `{"error": {"code": "...", "message": "..."}}` — used by
///     OpenAI itself, Azure OpenAI, and many APIM-style gateways. The nested
///     `message` surfaces to the caller; the `code` is mapped to an HTTP
///     status via the same switch as `OpenAiErrorMessage.getCode`, falling
///     back to 400 for unrecognized codes (Azure / gateway-specific) instead
///     of 500. This keeps gateway-level errors represented as client errors
///     rather than server errors.
///
/// Anything else (missing field, null, unexpected shape) falls back to the
/// JSON-serialized form so the error still reaches the caller rather than
/// being swallowed into a generic 500.
@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomLlmErrorMessage(JsonNode error) implements LlmProviderError<JsonNode> {

    private static final int DEFAULT_STATUS = 400;
    private static final String DEFAULT_DETAILS = "Custom Provider error";

    public ErrorMessage toErrorMessage() {
        if (error == null || error.isNull()) {
            return new ErrorMessage(DEFAULT_STATUS, "Unknown Custom Provider error", DEFAULT_DETAILS);
        }

        if (error.isTextual()) {
            return new ErrorMessage(DEFAULT_STATUS, error.asText(), DEFAULT_DETAILS);
        }

        if (error.isObject()) {
            String message = textOrNull(error, "message");
            String code = textOrNull(error, "code");
            String userMessage = StringUtils.isNotBlank(message)
                    ? message
                    : (StringUtils.isNotBlank(code) ? code : error.toString());
            String details = StringUtils.isNotBlank(code) ? code : DEFAULT_DETAILS;
            return new ErrorMessage(getStatusCode(code), userMessage, details);
        }

        return new ErrorMessage(DEFAULT_STATUS, error.toString(), DEFAULT_DETAILS);
    }

    @Override
    public JsonNode error() {
        return error;
    }

    /// Mirrors the OpenAI-compat code mapping in `OpenAiErrorMessage.getCode`
    /// so Custom LLM providers fronting a real OpenAI-compat backend surface
    /// the same status codes as the native provider (401 for invalid key, 429
    /// for rate limit, etc.). `InvalidAPIVersion` is the only Azure-specific
    /// code we've actually seen in the wild (OPIK-4551, Standard Bank and
    /// FAB) and is listed explicitly so the mapping is self-documenting.
    /// Unrecognized codes fall back to 400 rather than 500, because a
    /// provider that returned an error body at all is almost always telling
    /// us the client request was at fault, not that its own server blew up.
    private static int getStatusCode(String code) {
        if (StringUtils.isBlank(code)) {
            return DEFAULT_STATUS;
        }
        return switch (code) {
            case "invalid_api_key" -> 401;
            case "internal_error" -> 500;
            case "invalid_request_error" -> 400;
            case "rate_limit_exceeded" -> 429;
            case "insufficient_quota" -> 402;
            case "model_not_found" -> 404;
            case "InvalidAPIVersion" -> 400;
            default -> DEFAULT_STATUS;
        };
    }

    private static String textOrNull(JsonNode node, String fieldName) {
        JsonNode value = node.get(fieldName);
        if (value != null && value.isTextual()) {
            String text = value.asText();
            return StringUtils.isBlank(text) ? null : text;
        }
        return null;
    }
}
