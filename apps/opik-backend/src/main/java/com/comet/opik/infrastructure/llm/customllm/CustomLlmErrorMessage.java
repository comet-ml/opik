package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

import static com.comet.opik.infrastructure.llm.customllm.CustomLlmErrorMessage.CustomProviderError;

@JsonIgnoreProperties(ignoreUnknown = true)
public record CustomLlmErrorMessage(
        CustomProviderError error, String object, String message, String type, String param, Integer code)
        implements
            LlmProviderError<CustomProviderError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CustomProviderError(String message, String code, String type) {
    }

    public ErrorMessage toErrorMessage() {
        String errorMessage = "unknown error occurred";

        if (error != null && StringUtils.isNotEmpty(error.message())) {
            errorMessage = error.message();
        } else if (StringUtils.isNotEmpty(message)) {
            errorMessage = message;
        }

        Integer errorCode = getErrorCode();

        if (errorCode != null) {
            return new ErrorMessage(errorCode, errorMessage);
        }

        return new ErrorMessage(500, errorMessage, getErrorType());
    }

    @Override
    public CustomProviderError error() {
        if (error != null) {
            return error;
        }

        if (message != null || type != null || (code != null && code > 0)) {
            return new CustomProviderError(
                    Optional.ofNullable(message).orElse("Unknown error"),
                    Optional.ofNullable(code).map(String::valueOf).orElse("unknown"),
                    Optional.ofNullable(type).orElse("unknown"));
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
                default -> {
                    if (StringUtils.isNumeric(error.code())) {
                        yield Integer.parseInt(error.code());
                    }
                    yield null;
                }
            };
        }

        if (type != null && type.contains("BadRequest")) {
            return 400;
        }

        return null;
    }

    private String getErrorType() {
        return Optional.ofNullable(error)
                .map(CustomProviderError::type)
                .filter(StringUtils::isNotEmpty)
                .orElse("CustomLlmError");
    }
}