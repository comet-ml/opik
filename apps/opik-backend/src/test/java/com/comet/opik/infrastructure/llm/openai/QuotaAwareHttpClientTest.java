package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.apache.commons.lang3.RandomStringUtils;
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
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
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

    @ParameterizedTest(name = "async 429 insufficient_quota with code casing ''{0}'' is non-retryable")
    @ValueSource(strings = {"insufficient_quota", "INSUFFICIENT_QUOTA"})
    @DisplayName("when async 429 insufficient_quota, then fail with NonRetriableException with body and no cause")
    void executeAsync__when429InsufficientQuota__thenNonRetriableWithBodyAndNoCause(String code) {
        var body = """
                {"error":{"message":"%s","type":"%s","code":"%s"}}""".formatted(
                RandomStringUtils.secure().nextAlphanumeric(12), code, code);
        when(delegate.executeAsync(request))
                .thenReturn(CompletableFuture.failedFuture(new HttpException(TOO_MANY_REQUESTS, body)));

        assertThatThrownBy(() -> client.executeAsync(request).join())
                .isExactlyInstanceOf(CompletionException.class)
                .cause()
                .isExactlyInstanceOf(NonRetriableException.class)
                .hasMessage(body)
                .hasNoCause();
    }

    @ParameterizedTest(name = "async {0} is passed through unchanged (stays retryable)")
    @MethodSource("retryablePassThrough")
    @DisplayName("when async non-quota error, then fail with the original HttpException")
    void executeAsync__whenNonQuotaError__thenFailWithOriginal(String name, HttpException original) {
        when(delegate.executeAsync(request)).thenReturn(CompletableFuture.failedFuture(original));

        assertThatThrownBy(() -> client.executeAsync(request).join())
                .isExactlyInstanceOf(CompletionException.class)
                .hasCauseReference(original);
    }

    @Test
    @DisplayName("when async successful, then complete with the delegate response")
    void executeAsync__whenSuccessful__thenCompleteWithResponse() {
        var expectedResponse = SuccessfulHttpResponse.builder().statusCode(200).body("ok").build();
        when(delegate.executeAsync(request)).thenReturn(CompletableFuture.completedFuture(expectedResponse));

        var actualResponse = client.executeAsync(request).join();

        assertThat(actualResponse).isSameAs(expectedResponse);
    }

    @Test
    @DisplayName("when the async result is cancelled, then the delegate call is cancelled")
    void executeAsync__whenCancelled__thenCancelDelegate() {
        var delegateFuture = new CompletableFuture<SuccessfulHttpResponse>();
        when(delegate.executeAsync(request)).thenReturn(delegateFuture);

        client.executeAsync(request).cancel(true);

        assertThat(delegateFuture).isCancelled();
    }

    @Test
    @DisplayName("when streaming, then return the delegate publisher")
    void stream__whenCalled__thenReturnDelegatePublisher() {
        var parser = mock(ServerSentEventParser.class);
        Flow.Publisher<HttpStreamingEvent> expectedPublisher = JdkFlowAdapter.publisherToFlowPublisher(Flux.empty());
        when(delegate.stream(request, parser)).thenReturn(expectedPublisher);

        var actualPublisher = client.stream(request, parser);

        assertThat(actualPublisher).isSameAs(expectedPublisher);
    }
}
