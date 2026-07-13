package com.comet.opik.infrastructure.llm;

import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
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

    static Stream<Arguments> openAiStringCodeErrors() {
        return Stream.of(
                Arguments.of("rate_limit_exceeded", 429),
                Arguments.of("insufficient_quota", 402),
                Arguments.of("invalid_api_key", 401));
    }

    @ParameterizedTest
    @MethodSource("openAiStringCodeErrors")
    @DisplayName("OpenAI-format error with a String code resolves to the right status without a failed parse")
    void getErrorObjectResolvesOpenAiStringCodeError(String code, int expectedStatus) {
        var body = """
                {"error":{"message":"boom","type":"some_type","code":"%s"}}""".formatted(code);
        var throwable = new RuntimeException("io.dropwizard client error: " + body);

        Optional<ErrorMessage> result = mapper.getErrorObject(throwable, log);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(expectedStatus);
        assertThat(result.get().getMessage()).isEqualTo("boom");
    }

    @DisplayName("no error object is returned when the throwable carries no JSON payload")
    @ParameterizedTest
    @MethodSource("noJsonThrowables")
    void getErrorObjectReturnsEmptyWhenNoJson(Throwable throwable) {
        assertThat(mapper.getErrorObject(throwable, log)).isEmpty();
    }

    static Stream<Arguments> noJsonThrowables() {
        return Stream.of(
                Arguments.of(new RuntimeException("connection reset")),
                Arguments.of(new RuntimeException((String) null)));
    }
}
