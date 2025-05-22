package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.llm.LlmProviderError;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.llm.openai.OpenAiErrorMessage.OpenAiError;

public record OpenAiErrorMessage(OpenAiError error) implements LlmProviderError<OpenAiError> {

    public record OpenAiError(String message, String code) {
    }

    public ErrorMessage toErrorMessage() {
        return switch (error.code) {
            case "internal_error" -> new ErrorMessage(500, error.message(), error.code());
            default -> {
                if (StringUtils.isNotEmpty(error.code) && StringUtils.isNumeric(error.code)) {
                    yield new ErrorMessage(Integer.parseInt(error.code), error.message());
                }
                yield new ErrorMessage(500, error.message(), error.code());
            }
        };
    }
}
