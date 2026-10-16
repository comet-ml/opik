package com.comet.opik.infrastructure.llm;

import dev.langchain4j.exception.HttpException;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderLangChainMapperTest {

    private static final Logger log = LoggerFactory.getLogger(LlmProviderLangChainMapperTest.class);

    private final LlmProviderLangChainMapper mapper = LlmProviderLangChainMapper.INSTANCE;

    private static Throwable providerErrorWith(String body) {
        return new RuntimeException("dev.langchain4j upstream error: " + body);
    }

    static Stream<Arguments> openAiStringCodeErrors() {
        return Stream.of(
                Arguments.of("rate_limit_exceeded", 429),
                Arguments.of("insufficient_quota", 402),
                Arguments.of("invalid_api_key", 401));
    }

    @ParameterizedTest
    @MethodSource("openAiStringCodeErrors")
    @DisplayName("OpenAI-format string code resolves to its HTTP status")
    void resolvesOpenAiStringCode(String code, int expectedStatus) {
        var body = """
                {"error":{"message":"boom","type":"some_type","code":"%s"}}""".formatted(code);

        Optional<ErrorMessage> result = mapper.getErrorObject(providerErrorWith(body), log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("OpenRouter-format numeric code maps straight to its HTTP status (no 429 -> 500 regression)")
    void resolvesOpenRouterNumericCode() {
        var body = """
                {"error":{"code":429,"message":"Rate limited by upstream"}}""";

        Optional<ErrorMessage> result = mapper.getErrorObject(providerErrorWith(body), log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("a throwable without a JSON payload yields empty (caller maps this to 500)")
    void returnsEmptyWhenNoJsonPayload() {
        assertThat(mapper.getErrorObject(new RuntimeException("connection reset"), log)).isEmpty();
    }

    private static final String REQUESTY_ROUTER_ERROR = """
            {"error":{"origin":"router","message":"Invalid authorization token"}}""";

    @ParameterizedTest
    @ValueSource(ints = {403, 404, 429, 502})
    @DisplayName("Requesty router error without a code takes the HTTP status kept in the langchain4j chain")
    void requestyErrorWithoutCodeUsesHttpStatusFromChain(int status) {
        var cause = new HttpException(status, REQUESTY_ROUTER_ERROR);
        var throwable = new RuntimeException("dev.langchain4j upstream error", cause);

        Optional<ErrorMessage> result = mapper.getRequestyErrorObject(throwable, log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(status);
        assertThat(result.get().getMessage()).isEqualTo("Invalid authorization token");
    }

    @Test
    @DisplayName("Requesty router error without a code and without an HTTP status in the chain is a 500")
    void requestyErrorWithoutCodeOrStatusIs500() {
        Optional<ErrorMessage> result = mapper.getRequestyErrorObject(providerErrorWith(REQUESTY_ROUTER_ERROR),
                log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(500);
        assertThat(result.get().getMessage()).isEqualTo("Invalid authorization token");
    }

    @Test
    @DisplayName("Requesty error carrying a numeric code keeps that code over the HTTP status")
    void requestyErrorWithNumericCodePrefersPayloadCode() {
        var body = """
                {"error":{"origin":"provider","code":429,"message":"Rate limited by upstream"}}""";
        var cause = new HttpException(500, body);
        var throwable = new RuntimeException("dev.langchain4j upstream error", cause);

        Optional<ErrorMessage> result = mapper.getRequestyErrorObject(throwable, log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(429);
    }

    @Test
    @DisplayName("Requesty error with a 2xx code in the payload is degraded to 500, never an IllegalArgumentException")
    void requestyErrorWithNonErrorCodeIs500() {
        var body = """
                {"error":{"code":200,"message":"odd but seen in the wild"}}""";

        Optional<ErrorMessage> result = mapper.getRequestyErrorObject(providerErrorWith(body), log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(500);
    }

    @ParameterizedTest
    @MethodSource("openAiStringCodeErrors")
    @DisplayName("Requesty error forwarded in OpenAI string-code format falls back to the OpenAI mapping")
    void requestyErrorWithOpenAiStringCodeFallsBackToOpenAi(String code, int expectedStatus) {
        var body = """
                {"error":{"message":"boom","type":"some_type","code":"%s"}}""".formatted(code);

        Optional<ErrorMessage> result = mapper.getRequestyErrorObject(providerErrorWith(body), log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(expectedStatus);
    }

    @Test
    @DisplayName("Requesty error without a JSON payload yields empty like the other providers")
    void requestyErrorWithoutJsonPayloadIsEmpty() {
        assertThat(mapper.getRequestyErrorObject(new RuntimeException("connection reset"), log)).isEmpty();
        assertThat(mapper.getRequestyErrorObject(new HttpException(503, "Service Unavailable"), log)).isEmpty();
    }
}
