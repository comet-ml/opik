package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.comet.opik.infrastructure.llm.OpenAiCompatStatusCodes;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.StringUtils;

/**
 * Deserializes error bodies from OpenAI-compatible Custom LLM providers and
 * maps them into Opik's {@link ErrorMessage} DTO.
 *
 * <p>Two error shapes are supported out of the box:
 * <ul>
 *   <li>Text shape: {@code {"error": "message"}} — historical behavior; the string
 *       value becomes the user-visible message with HTTP 400.</li>
 *   <li>Object shape: {@code {"error": {"code": "...", "message": "..."}}} — used by
 *       OpenAI itself, Azure OpenAI, and many APIM-style gateways. The nested
 *       {@code message} surfaces to the caller; the {@code code} is mapped to an HTTP
 *       status via {@link OpenAiCompatStatusCodes} (with Azure-specific codes
 *       layered on top), falling back to 400 for unrecognized codes instead
 *       of 500. This keeps gateway-level errors represented as client errors
 *       rather than server errors.</li>
 * </ul>
 *
 * <p>Anything else (missing field, null, unexpected shape) falls back to the
 * JSON-serialized form so the error still reaches the caller rather than
 * being swallowed into a generic 500.
 */
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

    /**
     * Maps the error {@code code} string to an HTTP status. Delegates the core
     * OpenAI-compat codes to {@link OpenAiCompatStatusCodes} so the mapping
     * stays centralized across every provider wrapper, then layers
     * Azure/APIM-specific codes we have seen in the wild (OPIK-4551) on top.
     * Unrecognized codes fall back to 400 rather than 500 because a provider
     * that returned an error body at all is almost always telling us the
     * client request was at fault, not that its own server blew up.
     *
     * @param code the error code string from the provider (may be blank)
     * @return the mapped HTTP status
     */
    private static int getStatusCode(String code) {
        if (StringUtils.isBlank(code)) {
            return DEFAULT_STATUS;
        }
        Integer mapped = OpenAiCompatStatusCodes.fromCode(code);
        if (mapped != null) {
            return mapped;
        }
        // Azure-specific codes (e.g. InvalidAPIVersion, DeploymentNotFound) are
        // not in OpenAiCompatStatusCodes — they fall through to 400 as client
        // errors.
        return DEFAULT_STATUS;
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
