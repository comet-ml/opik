package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.domain.llm.ProviderErrorMessageValidator.DocumentedErrors;
import com.comet.opik.infrastructure.llm.LlmProviderError;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import io.dropwizard.jersey.errors.ErrorMessage;

import static com.comet.opik.infrastructure.llm.antropic.AnthropicErrorMessage.AnthropicError;

/** Anthropic's {@code {"type": "error", "error": {"type": "...", "message": "..."}}} body. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record AnthropicErrorMessage(AnthropicError error) implements LlmProviderError<AnthropicError> {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record AnthropicError(String type, String message) {
    }

    // the status comes from the exception langchain4j raised, see LlmProviderAnthropic
    public ErrorMessage toErrorMessage() {
        return new ErrorMessage(error.message());
    }

    public boolean isDocumented() {
        return DocumentedErrors.ANTHROPIC.documents(error.type());
    }
}
