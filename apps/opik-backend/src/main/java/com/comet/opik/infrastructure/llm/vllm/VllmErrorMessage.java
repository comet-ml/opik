package com.comet.opik.infrastructure.llm.vllm;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;

import static com.comet.opik.infrastructure.llm.vllm.VllmErrorMessage.VllmError;

@JsonIgnoreProperties(ignoreUnknown = true)
public record VllmErrorMessage(VllmError error, String object, String message, String type, String param,
        Integer code) implements LlmProviderError<VllmError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record VllmError(String message, String code, String type) {
    }

    public ErrorMessage toErrorMessage() {
        String errorMessage = null;

        if (error != null && error.message() != null) {
            errorMessage = error.message();
        } else if (message != null) {
            errorMessage = message;
        }

        if (errorMessage == null || errorMessage.trim().isEmpty()) {
            errorMessage = "Unknown vLLM error";
        }

        Integer errorCode = getErrorCode();

        if (errorCode != null) {
            return new ErrorMessage(errorCode, errorMessage);
        }

        return new ErrorMessage(500, errorMessage, getErrorType());
    }

    @Override
    public VllmError error() {
        if (error != null) {
            return error;
        }

        if (message != null || type != null || (code != null && code > 0)) {
            return new VllmError(
                    message != null ? message : "Unknown error",
                    code != null ? String.valueOf(code) : "unknown",
                    type != null ? type : "unknown");
        }

        return null;
    }

    private Integer getErrorCode() {
        if (code != null && code > 0) {
            return code;
        }

        if (error != null && error.code() != null) {
            return switch (error.code()) {
                case "invalid_api_key" -> 401;
                case "internal_error" -> 500;
                case "400" -> 400;
                case "401" -> 401;
                case "403" -> 403;
                case "404" -> 404;
                case "429" -> 429;
                case "500" -> 500;
                case "502" -> 502;
                case "503" -> 503;
                default -> {
                    try {
                        yield Integer.parseInt(error.code());
                    } catch (NumberFormatException e) {
                        yield null;
                    }
                }
            };
        }

        if (type != null && type.contains("BadRequest")) {
            return 400;
        }

        return null;
    }

    private String getErrorType() {
        if (error != null && error.type() != null) {
            return error.type();
        }
        return type != null ? type : "VllmError";
    }
}