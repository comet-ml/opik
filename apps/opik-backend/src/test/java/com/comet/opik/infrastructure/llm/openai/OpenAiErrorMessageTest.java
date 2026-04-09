package com.comet.opik.infrastructure.llm.openai;

import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class OpenAiErrorMessageTest {

    static Stream<Arguments> errorCodeMappings() {
        return Stream.of(
                Arguments.of("invalid_api_key", 401, "some error message"),
                Arguments.of("internal_error", 500, "some error message"),
                Arguments.of("invalid_request_error", 400, "some error message"),
                Arguments.of("rate_limit_exceeded", 429, "some error message"),
                Arguments.of("insufficient_quota", 402, "some error message"),
                Arguments.of("model_not_found", 404, "some error message"));
    }

    @ParameterizedTest
    @MethodSource("errorCodeMappings")
    void toErrorMessageWhenErrorCodeReturnsExpectedHttpStatus(String errorCode, int expectedHttpStatus,
            String message) {
        var error = new OpenAiErrorMessage(
                new OpenAiErrorMessage.OpenAiError(message, errorCode, "some_type"));

        var expected = new ErrorMessage(expectedHttpStatus, message);
        var actual = error.toErrorMessage();

        assertThat(actual)
                .usingRecursiveComparison()
                .isEqualTo(expected);
    }

    static Stream<Arguments> unknownErrorCodeCases() {
        return Stream.of(
                Arguments.of("unknown_code", "unknown error"),
                Arguments.of("some_other_error", "another error"));
    }

    @ParameterizedTest
    @MethodSource("unknownErrorCodeCases")
    void toErrorMessageWhenErrorCodeUnknownReturns500WithDetails(String errorCode, String message) {
        var error = new OpenAiErrorMessage(
                new OpenAiErrorMessage.OpenAiError(message, errorCode, "some_type"));

        var expected = new ErrorMessage(500, message, errorCode);
        var actual = error.toErrorMessage();

        assertThat(actual)
                .usingRecursiveComparison()
                .isEqualTo(expected);
    }

    static Stream<Arguments> nullMessageCases() {
        return Stream.of(
                Arguments.of("rate_limit_exceeded"),
                Arguments.of("unknown_code"));
    }

    @ParameterizedTest
    @MethodSource("nullMessageCases")
    void toErrorMessageWhenMessageNullReturnsNull(String errorCode) {
        var error = new OpenAiErrorMessage(
                new OpenAiErrorMessage.OpenAiError(null, errorCode, "some_type"));

        assertThat(error.toErrorMessage()).isNull();
    }
}
