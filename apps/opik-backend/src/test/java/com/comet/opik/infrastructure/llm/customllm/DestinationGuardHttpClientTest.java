package com.comet.opik.infrastructure.llm.customllm;

import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.adapter.JdkFlowAdapter;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The guard applies to the asynchronous and publisher calls as it does to the synchronous one. */
@ExtendWith(MockitoExtension.class)
class DestinationGuardHttpClientTest {

    @Mock
    private HttpClient delegate;

    @Mock
    private ProviderBaseUrlGuard guard;

    @Mock
    private ServerSentEventParser parser;

    private HttpRequest request() {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("https://%s.example.test/chat/completions".formatted(
                        RandomStringUtils.secure().nextAlphabetic(10).toLowerCase()))
                .body("{}")
                .build();
    }

    private ProviderRequestRefusedException refuse(HttpRequest request) {
        var refusal = new ProviderRequestRefusedException(ProviderRequestRefusedException.Reason.DESTINATION);
        doThrow(refusal).when(guard).validateRequest(request.url());
        return refusal;
    }

    @Test
    void executeAsyncForwardsAnAllowedRequest() {
        var request = request();
        var expectedResponse = SuccessfulHttpResponse.builder().statusCode(200).body("{}").build();
        when(delegate.executeAsync(request)).thenReturn(CompletableFuture.completedFuture(expectedResponse));
        var client = new DestinationGuardHttpClient(delegate, guard);

        var actualResponse = client.executeAsync(request).join();

        assertThat(actualResponse).isSameAs(expectedResponse);
        verify(guard).validateRequest(request.url());
    }

    @Test
    void executeAsyncRefusalFailsTheFutureWithoutACall() {
        var request = request();
        var expectedRefusal = refuse(request);
        var client = new DestinationGuardHttpClient(delegate, guard);

        assertThatThrownBy(() -> client.executeAsync(request).join())
                .isExactlyInstanceOf(CompletionException.class)
                .hasCauseReference(expectedRefusal);
        verify(delegate, never()).executeAsync(any(HttpRequest.class));
    }

    @Test
    void streamForwardsAnAllowedRequest() {
        var request = request();
        var expectedEvent = new ServerSentEvent("message", RandomStringUtils.secure().nextAlphanumeric(12));
        when(delegate.stream(request, parser))
                .thenReturn(JdkFlowAdapter.publisherToFlowPublisher(Flux.just(expectedEvent)));
        var client = new DestinationGuardHttpClient(delegate, guard);

        StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(client.stream(request, parser)))
                .expectNext(expectedEvent)
                .verifyComplete();
        verify(guard).validateRequest(request.url());
    }

    @Test
    void streamRefusalFailsTheSubscriptionWithoutACall() {
        var request = request();
        var expectedRefusal = refuse(request);
        var client = new DestinationGuardHttpClient(delegate, guard);

        StepVerifier.create(JdkFlowAdapter.flowPublisherToFlux(client.stream(request, parser)))
                .verifyErrorSatisfies(actualError -> assertThat(actualError).isSameAs(expectedRefusal));
        verify(delegate, never()).stream(any(HttpRequest.class), any());
    }
}
