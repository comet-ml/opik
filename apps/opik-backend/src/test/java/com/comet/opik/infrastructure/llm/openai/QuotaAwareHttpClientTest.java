package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quota Aware HttpClient")
class QuotaAwareHttpClientTest {

    private static final int TOO_MANY_REQUESTS = 429;

    private static final String RATE_LIMIT_BODY = """
            {"error":{"message":"Rate limit reached","type":"requests","code":"rate_limit_exceeded"}}""";

    @Mock
    private HttpClient delegate;

    @Mock
    private HttpRequest request;

    @InjectMocks
    private QuotaAwareHttpClient client;

    @ParameterizedTest(name = "429 insufficient_quota with code casing ''{0}'' is non-retryable")
    @ValueSource(strings = {"insufficient_quota", "INSUFFICIENT_QUOTA", "Insufficient_Quota"})
    @DisplayName("when 429 insufficient_quota (any casing), then rethrow as NonRetriableException with body and no cause")
    void execute__when429InsufficientQuota__thenNonRetriableWithBodyAndNoCause(String code) {
        // Both LangChain4j's internal retry and Opik's outer retry skip NonRetriableException. The body must be
        // the message (so it still maps to 402 downstream) and there must be no HttpException cause (otherwise
        // ExceptionMapper.findRoot() would unwrap it and re-map the 429 to a retryable RateLimitException).
        var body = """
                {"error":{"message":"You exceeded your current quota","type":"%s","code":"%s"}}""".formatted(code,
                code);
        when(delegate.execute(request)).thenThrow(new HttpException(TOO_MANY_REQUESTS, body));

        assertThatThrownBy(() -> client.execute(request))
                .isInstanceOf(NonRetriableException.class)
                .hasMessage(body)
                .hasNoCause();
    }

    private static Stream<Arguments> retryablePassThrough() {
        return Stream.of(
                Arguments.of("429 rate_limit_exceeded", new HttpException(TOO_MANY_REQUESTS, RATE_LIMIT_BODY)),
                Arguments.of("400 invalid_request", new HttpException(400, """
                        {"error":{"code":"invalid_request_error"}}""")));
    }

    @ParameterizedTest(name = "{0} is passed through unchanged (stays retryable)")
    @MethodSource("retryablePassThrough")
    @DisplayName("when a non-quota error, then rethrow the original HttpException")
    void execute__whenNonQuotaError__thenRethrowOriginal(String name, HttpException original) {
        when(delegate.execute(request)).thenThrow(original);

        assertThatThrownBy(() -> client.execute(request)).isSameAs(original);
    }

    @Test
    @DisplayName("when successful, then return the delegate response")
    void execute__whenSuccessful__thenReturnResponse() {
        var response = SuccessfulHttpResponse.builder().statusCode(200).body("ok").build();
        when(delegate.execute(request)).thenReturn(response);

        assertThat(client.execute(request)).isSameAs(response);
    }
}
