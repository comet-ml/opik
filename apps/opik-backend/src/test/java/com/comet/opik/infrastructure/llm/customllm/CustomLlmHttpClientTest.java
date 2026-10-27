package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.HttpResponseReceived;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import lombok.Builder;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class CustomLlmHttpClientTest {

    private static final String PATH = "/v1/chat/completions";
    private static final int ERROR_BODY_MAX_SIZE = 64;
    private static final Duration WAIT = Duration.ofSeconds(10);
    private static final ProviderBaseUrlGuard RELAXED_GUARD = ProviderBaseUrlGuard.builder()
            .mode(DestinationGuard.Mode.RELAXED)
            .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
            .build();

    private WireMockServer wireMock;
    private OkHttpClient okHttpClient;
    private HttpClient client;

    /** The four ways a call reports a non-2xx response. */
    enum CallPath {
        EXECUTE,
        EXECUTE_ASYNC,
        LISTENER_STREAM,
        STREAM
    }

    @BeforeAll
    void setUpAll() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        okHttpClient = new CustomLlmModule().llmHttpClient(TestConfigUtils.loadConfigTest().getLlmProviderClient(),
                RELAXED_GUARD);
        client = new CustomLlmHttpClientBuilder(okHttpClient, ERROR_BODY_MAX_SIZE)
                .readTimeout(WAIT)
                .build();
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    @Test
    void sendsTheBodyIntactWithItsUtf8Length() {
        var expectedBody = "{\"content\": \"%s héllo ✓ 😀\"}"
                .formatted(RandomStringUtils.secure().nextAlphanumeric(20));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{}")));

        client.execute(request(expectedBody));

        wireMock.verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Content-Type", equalTo("application/json"))
                .withHeader("Content-Length",
                        equalTo(String.valueOf(expectedBody.getBytes(StandardCharsets.UTF_8).length)))
                .withRequestBody(equalTo(expectedBody)));
    }

    @ParameterizedTest
    @EnumSource(CallPath.class)
    void truncatesTheErrorBodyAtTheLimit(CallPath callPath) throws Exception {
        var errorBody = RandomStringUtils.secure().nextAlphanumeric(ERROR_BODY_MAX_SIZE * 100);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400).withBody(errorBody)));

        Throwable actualError = failure(callPath, request("{}"));

        assertThat(actualError).isExactlyInstanceOf(HttpException.class)
                .hasMessage(errorBody.substring(0, ERROR_BODY_MAX_SIZE));
        assertThat(((HttpException) actualError).statusCode()).isEqualTo(400);
    }

    /** A body cut short reads as a fixed message: the read failure's own message can name the destination. */
    @ParameterizedTest
    @EnumSource(CallPath.class)
    void anUnreadableErrorBodyIsReportedWithoutItsReadFailure(CallPath callPath) throws Exception {
        var partialBody = RandomStringUtils.secure().nextAlphanumeric(ERROR_BODY_MAX_SIZE / 2);
        try (var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            // the body is shorter than its declared length, then the connection closes
            Thread.ofVirtual().start(() -> {
                try (Socket socket = server.accept()) {
                    socket.getOutputStream().write("""
                            HTTP/1.1 400 Bad Request\r
                            Content-Length: %d\r
                            \r
                            %s""".formatted(ERROR_BODY_MAX_SIZE, partialBody).getBytes(StandardCharsets.UTF_8));
                    socket.getOutputStream().flush();
                } catch (IOException ignored) {
                    // the call reports what it read
                }
            });
            var request = HttpRequest.builder()
                    .method(HttpMethod.POST)
                    .url("http://localhost:%d%s".formatted(server.getLocalPort(), PATH))
                    .body("{}")
                    .build();

            Throwable actualError = failure(callPath, request);

            assertThat(actualError).isExactlyInstanceOf(HttpException.class)
                    .hasMessage(CustomLlmHttpClient.UNREADABLE_ERROR_BODY);
            assertThat(((HttpException) actualError).statusCode()).isEqualTo(400);
        }
    }

    @Test
    void executeAsyncReturnsTheWholeBody() {
        var expectedBody = RandomStringUtils.secure().nextAlphanumeric(ERROR_BODY_MAX_SIZE * 100);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(201).withBody(expectedBody)));

        SuccessfulHttpResponse actualResponse = client.executeAsync(request("{}")).orTimeout(10, TimeUnit.SECONDS)
                .join();

        var expectedResponse = SuccessfulHttpResponse.builder()
                .statusCode(201)
                .headers(actualResponse.headers())
                .body(expectedBody)
                .build();
        assertThat(actualResponse).usingRecursiveComparison().isEqualTo(expectedResponse);
    }

    @Test
    void cancellingExecuteAsyncCancelsTheCall() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withFixedDelay(30_000)));

        var future = client.executeAsync(request("{}"));
        await().atMost(WAIT).until(() -> okHttpClient.dispatcher().runningCallsCount() == 1);
        future.cancel(true);

        await().atMost(WAIT).until(() -> okHttpClient.dispatcher().runningCallsCount() == 0);
    }

    /** OkHttp's default dispatcher runs 5 calls per host at once; the JDK client had no such limit. */
    @Test
    void runsManyStreamsToOneHostAtOnce() {
        int streams = 10;
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: x\n\n")
                .withFixedDelay(5_000)));

        var actualStreams = IntStream.range(0, streams)
                .mapToObj(i -> collect(client.stream(request("{}"), new DefaultServerSentEventParser()),
                        Long.MAX_VALUE))
                .toList();

        // queued 5 at a time, the dispatcher would never run all of them together
        await().atMost(WAIT).until(() -> okHttpClient.dispatcher().runningCallsCount() == streams);
        CompletableFuture.allOf(actualStreams.toArray(CompletableFuture[]::new)).orTimeout(10, TimeUnit.SECONDS)
                .join();
    }

    /** The dispatcher limits of a client. */
    @Builder(toBuilder = true)
    private record DispatcherLimits(int maxRequests, int maxRequestsPerHost) {
        static DispatcherLimits of(OkHttpClient client) {
            return new DispatcherLimits(client.dispatcher().getMaxRequests(),
                    client.dispatcher().getMaxRequestsPerHost());
        }
    }

    /**
     * Over the limit, calls wait in the dispatcher and all of them complete. Each finishing call hands the next one to
     * the executor while its own thread is still busy, so a pool that refused work it can't start at once would fail
     * those calls instead.
     */
    @Test
    void callsBeyondTheLimitWaitAndAllComplete() throws Exception {
        int limit = 2;
        int streams = 6;
        var config = TestConfigUtils.loadConfigTest().getLlmProviderClient();
        config.setHttpClientMaxThreads(limit);
        config.setHttpClientMaxRequests(limit);
        config.setHttpClientMaxRequestsPerHost(limit);
        var boundedOkHttpClient = new CustomLlmModule().llmHttpClient(config, RELAXED_GUARD);
        try {
            var boundedClient = new CustomLlmHttpClientBuilder(boundedOkHttpClient, ERROR_BODY_MAX_SIZE)
                    .readTimeout(WAIT)
                    .build();
            var data = RandomStringUtils.secure().nextAlphanumeric(10);
            wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                    .withHeader("Content-Type", "text/event-stream")
                    .withBody("data: %s\n\n".formatted(data))
                    .withFixedDelay(1_000)));

            var actualStreams = IntStream.range(0, streams)
                    .mapToObj(i -> collect(boundedClient.stream(request("{}"), new DefaultServerSentEventParser()),
                            Long.MAX_VALUE))
                    .toList();

            assertThat(DispatcherLimits.of(boundedOkHttpClient)).isEqualTo(new DispatcherLimits(limit, limit));
            await().atMost(WAIT).until(() -> boundedOkHttpClient.dispatcher().runningCallsCount() == limit
                    && boundedOkHttpClient.dispatcher().queuedCallsCount() == streams - limit);
            var expectedEvents = IntStream.range(0, streams)
                    .mapToObj(i -> List.of(new ServerSentEvent(null, data)))
                    .toList();
            var actualEvents = new ArrayList<List<HttpStreamingEvent>>();
            for (var stream : actualStreams) {
                var events = stream.get(30, TimeUnit.SECONDS);
                actualEvents.add(events.subList(1, events.size()));
            }
            assertThat(actualEvents).isEqualTo(expectedEvents);
            await().atMost(WAIT).until(() -> boundedOkHttpClient.dispatcher().runningCallsCount() == 0);
        } finally {
            // this test's own threads and connections, which nothing else releases
            boundedOkHttpClient.dispatcher().executorService().shutdownNow();
            boundedOkHttpClient.connectionPool().evictAll();
        }
    }

    @Test
    void streamEmitsTheResponseHeadThenEachEventInOrder() throws Exception {
        var expectedData = List.of(RandomStringUtils.secure().nextAlphanumeric(10),
                RandomStringUtils.secure().nextAlphanumeric(10), RandomStringUtils.secure().nextAlphanumeric(10));
        var body = "data: %s\n\ndata: %s\n\ndata: %s".formatted(expectedData.toArray());
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody(body)));

        List<HttpStreamingEvent> actualEvents = collect(
                client.stream(request("{}"), new DefaultServerSentEventParser()),
                Long.MAX_VALUE).get(10, TimeUnit.SECONDS);

        assertThat(actualEvents.getFirst()).isExactlyInstanceOf(HttpResponseReceived.class);
        assertThat(((HttpResponseReceived) actualEvents.getFirst()).response().statusCode()).isEqualTo(200);
        // the last event has no closing blank line, so only the flush at the end of the body emits it
        var expectedEvents = expectedData.stream().map(data -> new ServerSentEvent(null, data)).toList();
        assertThat(actualEvents.subList(1, actualEvents.size())).isEqualTo(expectedEvents);
    }

    @Test
    void cancellingAStreamCancelsTheCall() throws Exception {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: x\n\n".repeat(100))
                .withChunkedDribbleDelay(100, 30_000)));

        List<HttpStreamingEvent> actualEvents = collect(
                client.stream(request("{}"), new DefaultServerSentEventParser()), 1).get(10, TimeUnit.SECONDS);

        assertThat(actualEvents).singleElement().isExactlyInstanceOf(HttpResponseReceived.class);
        await().atMost(WAIT).until(() -> okHttpClient.dispatcher().runningCallsCount() == 0);
    }

    @Test
    void anInterruptedCallKeepsTheInterruptFlag() {
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{}")));

        Thread.currentThread().interrupt();
        try {
            assertThatThrownBy(() -> client.execute(request("{}")))
                    .isExactlyInstanceOf(RuntimeException.class)
                    .hasCauseExactlyInstanceOf(InterruptedIOException.class);
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
    }

    @Test
    void sendsAFormDataFileWithoutAContentType() {
        var fileName = RandomStringUtils.secure().nextAlphanumeric(10) + ".bin";
        var content = RandomStringUtils.secure().nextAlphanumeric(32);
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{}")));

        client.execute(HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(wireMock.baseUrl() + PATH)
                .addFormDataFile("file", fileName, null, content.getBytes(StandardCharsets.UTF_8))
                .build());

        var expectedPart = "Content-Disposition: form-data; name=\"file\"; filename=\"%s\"\r\nContent-Length: 32\r\n\r\n%s"
                .formatted(fileName, content);
        assertThat(wireMock.getAllServeEvents().getFirst().getRequest().getBodyAsString()).contains(expectedPart);
    }

    private HttpRequest request(String body) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(wireMock.baseUrl() + PATH)
                .addHeader("Content-Type", "application/json")
                .body(body)
                .build();
    }

    private Throwable failure(CallPath callPath, HttpRequest request) throws Exception {
        return switch (callPath) {
            case EXECUTE -> catchFailure(() -> client.execute(request));
            case EXECUTE_ASYNC -> catchFailure(() -> client.executeAsync(request).orTimeout(10, TimeUnit.SECONDS)
                    .join());
            case LISTENER_STREAM -> {
                var error = new CompletableFuture<Throwable>();
                client.execute(request, new ServerSentEventListener() {
                    @Override
                    public void onEvent(ServerSentEvent event) {
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        error.complete(throwable);
                    }
                });
                yield error.get(10, TimeUnit.SECONDS);
            }
            case STREAM -> catchFailure(() -> collect(client.stream(request, new DefaultServerSentEventParser()),
                    Long.MAX_VALUE).orTimeout(10, TimeUnit.SECONDS).join());
        };
    }

    /** Collects the first {@code count} events of a stream, or all of them, then cancels the subscription. */
    private static CompletableFuture<List<HttpStreamingEvent>> collect(Flow.Publisher<HttpStreamingEvent> publisher,
            long count) {
        var result = new CompletableFuture<List<HttpStreamingEvent>>();
        publisher.subscribe(new Flow.Subscriber<>() {
            private final List<HttpStreamingEvent> events = new ArrayList<>();
            private Flow.Subscription subscription;

            @Override
            public void onSubscribe(Flow.Subscription subscription) {
                this.subscription = subscription;
                subscription.request(count);
            }

            @Override
            public void onNext(HttpStreamingEvent event) {
                events.add(event);
                if (events.size() == count) {
                    subscription.cancel();
                    result.complete(events);
                }
            }

            @Override
            public void onError(Throwable throwable) {
                result.completeExceptionally(throwable);
            }

            @Override
            public void onComplete() {
                result.complete(events);
            }
        });
        return result;
    }

    /** The failure of a call, unwrapped from the future's completion exception. */
    private static Throwable catchFailure(Runnable call) {
        try {
            call.run();
        } catch (CompletionException exception) {
            return exception.getCause();
        } catch (RuntimeException exception) {
            return exception;
        }
        throw new AssertionError("The call did not fail");
    }
}
