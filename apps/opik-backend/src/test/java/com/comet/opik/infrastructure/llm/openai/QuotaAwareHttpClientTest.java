package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("Quota Aware HttpClient")
class QuotaAwareHttpClientTest {

    private static final String QUOTA_BODY = "{\"error\":{\"message\":\"You exceeded your current quota, please check "
            + "your plan and billing details.\",\"type\":\"insufficient_quota\",\"code\":\"insufficient_quota\"}}";
    private static final String RATE_LIMIT_BODY = "{\"error\":{\"message\":\"Rate limit reached\",\"type\":\"requests\","
            + "\"code\":\"rate_limit_exceeded\"}}";

    @Mock
    private HttpClient delegate;

    @Mock
    private HttpRequest request;

    @Test
    @DisplayName("when 429 insufficient_quota, then rethrow as NonRetriableException carrying the body and no cause")
    void execute__when429InsufficientQuota__thenNonRetriableWithBodyAndNoHttpCause() {
        var client = new QuotaAwareHttpClient(delegate);
        when(delegate.execute(request)).thenThrow(new HttpException(429, QUOTA_BODY));

        // Both LangChain4j's internal retry and Opik's outer retry skip NonRetriableException. The body must be
        // the message (so it still maps to 402 downstream) and there must be no HttpException cause (otherwise
        // ExceptionMapper.findRoot() would unwrap it and re-map the 429 to a retryable RateLimitException).
        var thrown = catchThrowable(() -> client.execute(request));

        assertThat(thrown)
                .isInstanceOf(NonRetriableException.class)
                .hasMessage(QUOTA_BODY)
                .hasNoCause();
    }

    @Test
    @DisplayName("when 429 rate_limit_exceeded, then rethrow the original HttpException (stays retryable)")
    void execute__when429RateLimit__thenRethrowHttpException() {
        var client = new QuotaAwareHttpClient(delegate);
        var original = new HttpException(429, RATE_LIMIT_BODY);
        when(delegate.execute(request)).thenThrow(original);

        assertThatThrownBy(() -> client.execute(request)).isSameAs(original);
    }

    @Test
    @DisplayName("when a non-429 client error, then rethrow the original HttpException")
    void execute__whenOtherClientError__thenRethrowHttpException() {
        var client = new QuotaAwareHttpClient(delegate);
        var original = new HttpException(400, "{\"error\":{\"code\":\"invalid_request_error\"}}");
        when(delegate.execute(request)).thenThrow(original);

        assertThatThrownBy(() -> client.execute(request)).isSameAs(original);
    }

    @Test
    @DisplayName("when successful, then return the delegate response")
    void execute__whenSuccessful__thenReturnResponse() {
        var client = new QuotaAwareHttpClient(delegate);
        var response = SuccessfulHttpResponse.builder().statusCode(200).body("ok").build();
        when(delegate.execute(request)).thenReturn(response);

        assertThat(client.execute(request)).isSameAs(response);
    }
}
