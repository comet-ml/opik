package com.comet.opik.infrastructure.llm.openai;

import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiErrorMessageTest {

    static Stream<Arguments> knownErrorCodes() {
        return Stream.of(
                Arguments.of("invalid_api_key", 401),
                Arguments.of("internal_error", 500),
                Arguments.of("invalid_request_error", 400),
                Arguments.of("rate_limit_exceeded", 429),
                Arguments.of("insufficient_quota", 429),
                Arguments.of("model_not_found", 404));
    }

    @ParameterizedTest
    @MethodSource("knownErrorCodes")
    void shouldMapKnownErrorCodesToCorrectHttpStatus(String errorCode, int expectedHttpStatus) {
        var error = new OpenAiErrorMessage(
                new OpenAiErrorMessage.OpenAiError("some error message", errorCode, "some_type"));

        ErrorMessage result = error.toErrorMessage();

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(expectedHttpStatus);
        assertThat(result.getMessage()).isEqualTo("some error message");
    }

    @Test
    void shouldFallbackTo500ForUnknownErrorCode() {
        var error = new OpenAiErrorMessage(
                new OpenAiErrorMessage.OpenAiError("unknown error", "unknown_code", "some_type"));

        ErrorMessage result = error.toErrorMessage();

        assertThat(result).isNotNull();
        assertThat(result.getCode()).isEqualTo(500);
    }

    @Test
    void shouldReturnNullWhenMessageIsNull() {
        var error = new OpenAiErrorMessage(
                new OpenAiErrorMessage.OpenAiError(null, "rate_limit_exceeded", "some_type"));

        ErrorMessage result = error.toErrorMessage();

        assertThat(result).isNull();
    }
}
