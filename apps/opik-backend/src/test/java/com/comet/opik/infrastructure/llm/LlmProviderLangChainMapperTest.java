package com.comet.opik.infrastructure.llm;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.dropwizard.jersey.errors.ErrorMessage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class LlmProviderLangChainMapperTest {

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
    @DisplayName("OpenAI-format string code resolves to the right status (present -> caller returns 4xx, not empty -> 500)")
    void resolvesOpenAiStringCodeWithoutParseWarning(String code, int expectedStatus) {
        var body = """
                {"error":{"message":"boom","type":"some_type","code":"%s"}}""".formatted(code);
        var logger = testLogger("openai-" + code);
        var appender = attach(logger);

        Optional<ErrorMessage> result = mapper.getErrorObject(providerErrorWith(body), logger);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(expectedStatus);
        // The OpenRouter (Integer code) model is not attempted for a string code, so the doomed parse
        // and its WARN stack no longer happen.
        assertThat(warnings(appender)).isEmpty();
    }

    @Test
    @DisplayName("OpenRouter-format numeric code maps straight to its HTTP status (no 429 -> 500 regression)")
    void resolvesOpenRouterNumericCode() {
        var body = """
                {"error":{"code":429,"message":"Rate limited by upstream"}}""";
        var logger = testLogger("openrouter");
        var appender = attach(logger);

        Optional<ErrorMessage> result = mapper.getErrorObject(providerErrorWith(body), logger);

        assertThat(result).isPresent();
        assertThat(result.get().getCode()).isEqualTo(429);
        assertThat(warnings(appender)).isEmpty();
    }

    @Test
    @DisplayName("a throwable without a JSON payload yields empty (caller maps this to 500)")
    void returnsEmptyWhenNoJsonPayload() {
        var logger = testLogger("nojson");

        assertThat(mapper.getErrorObject(new RuntimeException("connection reset"), logger)).isEmpty();
    }

    private static Logger testLogger(String name) {
        return (Logger) LoggerFactory.getLogger("LlmProviderLangChainMapperTest-" + name);
    }

    private static ListAppender<ILoggingEvent> attach(Logger logger) {
        var appender = new ListAppender<ILoggingEvent>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }

    private static List<ILoggingEvent> warnings(ListAppender<ILoggingEvent> appender) {
        return appender.list.stream().filter(event -> event.getLevel() == Level.WARN).toList();
    }
}
