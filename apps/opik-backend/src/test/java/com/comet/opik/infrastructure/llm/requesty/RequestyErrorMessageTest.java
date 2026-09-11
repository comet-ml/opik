package com.comet.opik.infrastructure.llm.requesty;

import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class RequestyErrorMessageTest {

    private static final String MESSAGE = "Invalid authorization token";

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 403, 404, 429, 500, 502, 503})
    @DisplayName("a 4xx or 5xx code in the payload is used as the HTTP status")
    void toErrorMessageUsesPayloadCodeWhenItIsAnErrorStatus(int code) {
        var error = new RequestyErrorMessage(new RequestyErrorMessage.RequestyError(MESSAGE, code));

        assertThat(error.toErrorMessage())
                .usingRecursiveComparison()
                .isEqualTo(new ErrorMessage(code, MESSAGE));
    }

    static Stream<Arguments> nonErrorCodes() {
        return Stream.of(
                Arguments.of(0),
                Arguments.of(200),
                Arguments.of(302),
                Arguments.of(-1),
                Arguments.of(999));
    }

    @ParameterizedTest
    @MethodSource("nonErrorCodes")
    @DisplayName("a payload code outside the 4xx and 5xx families is degraded to 500")
    void toErrorMessageDegradesNonErrorCodesTo500(int code) {
        var error = new RequestyErrorMessage(new RequestyErrorMessage.RequestyError(MESSAGE, code));

        assertThat(error.toErrorMessage())
                .usingRecursiveComparison()
                .isEqualTo(new ErrorMessage(500, MESSAGE));
    }

    @Test
    @DisplayName("without a payload code the HTTP status of the router response is used")
    void toErrorMessageFallsBackToResponseStatusWhenPayloadHasNoCode() {
        var error = new RequestyErrorMessage(new RequestyErrorMessage.RequestyError(MESSAGE, null));

        assertThat(error.toErrorMessage(403))
                .usingRecursiveComparison()
                .isEqualTo(new ErrorMessage(403, MESSAGE));
    }

    @Test
    @DisplayName("the payload code wins over the HTTP status of the router response")
    void toErrorMessagePrefersPayloadCodeOverResponseStatus() {
        var error = new RequestyErrorMessage(new RequestyErrorMessage.RequestyError(MESSAGE, 429));

        assertThat(error.toErrorMessage(500))
                .usingRecursiveComparison()
                .isEqualTo(new ErrorMessage(429, MESSAGE));
    }

    @Test
    @DisplayName("without a payload code or a known response status the error is a 500")
    void toErrorMessageDefaultsTo500WhenNothingIsKnown() {
        var error = new RequestyErrorMessage(new RequestyErrorMessage.RequestyError(MESSAGE, null));

        assertThat(error.toErrorMessage())
                .usingRecursiveComparison()
                .isEqualTo(new ErrorMessage(500, MESSAGE));
        assertThat(error.toErrorMessage(null))
                .usingRecursiveComparison()
                .isEqualTo(new ErrorMessage(500, MESSAGE));
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    @DisplayName("a blank message yields null so the caller falls through to the generic handling")
    void toErrorMessageReturnsNullWhenMessageIsBlank(String message) {
        var error = new RequestyErrorMessage(new RequestyErrorMessage.RequestyError(message, 404));

        assertThat(error.toErrorMessage()).isNull();
        assertThat(error.toErrorMessage(404)).isNull();
    }
}
