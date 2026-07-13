package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
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
}
