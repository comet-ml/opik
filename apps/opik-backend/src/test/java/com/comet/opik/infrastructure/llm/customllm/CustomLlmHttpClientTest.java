package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
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
import lombok.NonNull;
import okhttp3.Call;
import okhttp3.ConnectionPool;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
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
import java.util.concurrent.atomic.AtomicInteger;
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
    private static final Duration STREAM_READ_TIMEOUT = Duration.ofSeconds(120);
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
        client = new CustomLlmHttpClientBuilder(okHttpClient, ERROR_BODY_MAX_SIZE, STREAM_READ_TIMEOUT)
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

    /** What a raw server sends on its one connection, once it has read the request. */
    @FunctionalInterface
    private interface RawReply {
        void send(OutputStream out) throws IOException, InterruptedException;
    }

    /**
     * A server on a loopback port that answers one connection as scripted: WireMock can't send headers and then stay
     * silent, nor never answer at all. The request is read first, so closing doesn't reset the connection.
     */
    private static ServerSocket rawServer(RawReply reply) throws IOException {
        var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept(); var out = socket.getOutputStream()) {
                readRequest(socket.getInputStream());
                reply.send(out);
                out.flush();
            } catch (IOException | InterruptedException ignored) {
                // the call reports what it got
            }
        });
        return server;
    }

    private static void readRequest(InputStream in) throws IOException {
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int read = in.read();
            if (read == -1) {
                return;
            }
            head.append((char) read);
        }
        var contentLength = head.toString().lines()
                .filter(line -> line.regionMatches(true, 0, "Content-Length:", 0, 15))
                .map(line -> Integer.parseInt(line.substring(15).trim()))
                .findFirst()
                .orElse(0);
        in.readNBytes(contentLength);
    }

    private static HttpRequest rawRequest(ServerSocket server) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("http://localhost:%d%s".formatted(server.getLocalPort(), PATH))
                .body("{}")
                .build();
    }

    private static HttpClient clientWithReadTimeout(Duration readTimeout, OkHttpClient okHttpClient) {
        return clientWithTimeouts(readTimeout, STREAM_READ_TIMEOUT, okHttpClient);
    }

    private static HttpClient clientWithTimeouts(Duration readTimeout, Duration streamReadTimeout,
            OkHttpClient okHttpClient) {
        return new CustomLlmHttpClientBuilder(okHttpClient, ERROR_BODY_MAX_SIZE, streamReadTimeout)
                .readTimeout(readTimeout)
                .build();
    }

    /** A body cut short reads as a fixed message: the read failure's own message can name the destination. */
    @ParameterizedTest
    @EnumSource(CallPath.class)
    void anUnreadableErrorBodyIsReportedWithoutItsReadFailure(CallPath callPath) throws Exception {
        var partialBody = RandomStringUtils.secure().nextAlphanumeric(ERROR_BODY_MAX_SIZE / 2);
        // the body is shorter than its declared length, then the connection closes
        try (var server = rawServer(out -> out.write("""
                HTTP/1.1 400 Bad Request\r
                Content-Length: %d\r
                \r
                %s""".formatted(ERROR_BODY_MAX_SIZE, partialBody).getBytes(StandardCharsets.UTF_8)))) {

            Throwable actualError = failure(callPath, rawRequest(server));

            assertThat(actualError).isExactlyInstanceOf(HttpException.class)
                    .hasMessage(CustomLlmHttpClient.UNREADABLE_ERROR_BODY);
            assertThat(((HttpException) actualError).statusCode()).isEqualTo(400);
        }
    }

    /** The two ways a stream is read. */
    enum StreamPath {
        LISTENER_STREAM,
        STREAM
    }

    /** A stream that sends its headers, then nothing for {@code silence}, then one event. */
    private static RawReply silentStream(Duration silence, String data) {
        return delayedStream(Duration.ZERO, silence, data);
    }

    /** A stream that waits {@code headerDelay} before its headers, then {@code silence} before its one event. */
    private static RawReply delayedStream(Duration headerDelay, Duration silence, String data) {
        return out -> {
            Thread.sleep(headerDelay);
            out.write("""
                    HTTP/1.1 200 OK\r
                    Content-Type: text/event-stream\r
                    Connection: close\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8));
            out.flush();
            Thread.sleep(silence);
            out.write("data: %s\n\n".formatted(data).getBytes(StandardCharsets.UTF_8));
        };
    }

    /**
     * The read timeout bounds the wait for the headers only, the stream read timeout the body only, in either order of
     * the two: headers slower than the stream read timeout but within the read timeout arrive, and a body silent for
     * longer than the stream read timeout then fails at it.
     */
    @ParameterizedTest
    @EnumSource(StreamPath.class)
    void aStreamReadTimeoutShorterThanTheReadTimeoutBoundsTheBodyOnly(StreamPath streamPath) throws Exception {
        var readTimeout = Duration.ofSeconds(3);
        var streamReadTimeout = Duration.ofMillis(500);
        var headerDelay = Duration.ofSeconds(1);
        var data = RandomStringUtils.secure().nextAlphanumeric(10);
        var timedClient = clientWithTimeouts(readTimeout, streamReadTimeout, okHttpClient);
        try (var server = rawServer(delayedStream(headerDelay, Duration.ZERO, data))) {
            var expectedEvents = List.of(new ServerSentEvent(null, data));

            var actualEvents = streamedEvents(timedClient, streamPath, rawRequest(server));

            assertThat(actualEvents).isEqualTo(expectedEvents);
        }
        try (var server = rawServer(delayedStream(headerDelay, Duration.ofSeconds(30), data))) {
            long startNanos = System.nanoTime();

            Throwable actualError = failure(timedClient, CallPath.valueOf(streamPath.name()), rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualError).isExactlyInstanceOf(TimeoutException.class);
            assertThat(actualElapsed).isBetween(headerDelay.plus(streamReadTimeout), readTimeout);
        }
    }

    /** An unset read timeout leaves the header wait unbounded, as with the JDK client; the body keeps its bound. */
    @ParameterizedTest
    @EnumSource(StreamPath.class)
    void anUnsetReadTimeoutLeavesTheHeaderWaitUnbounded(StreamPath streamPath) throws Exception {
        var streamReadTimeout = Duration.ofMillis(500);
        var headerDelay = Duration.ofMillis(1_500);
        var data = RandomStringUtils.secure().nextAlphanumeric(10);
        var timedClient = clientWithTimeouts(Duration.ZERO, streamReadTimeout, okHttpClient);
        try (var server = rawServer(delayedStream(headerDelay, Duration.ZERO, data))) {
            var expectedEvents = List.of(new ServerSentEvent(null, data));

            var actualEvents = streamedEvents(timedClient, streamPath, rawRequest(server));

            assertThat(actualEvents).isEqualTo(expectedEvents);
        }
        try (var server = rawServer(delayedStream(Duration.ZERO, Duration.ofSeconds(30), data))) {
            long startNanos = System.nanoTime();

            Throwable actualError = failure(timedClient, CallPath.valueOf(streamPath.name()), rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualError).isExactlyInstanceOf(TimeoutException.class);
            assertThat(actualElapsed).isBetween(streamReadTimeout, Duration.ofSeconds(5));
        }
    }

    /**
     * The header wait starts once the connection is established: resolving the host, which the connect timeout
     * bounds, takes longer than the read timeout here, and the headers that follow within it still arrive.
     */
    @ParameterizedTest
    @EnumSource(StreamPath.class)
    void connectingDoesNotCountAgainstTheReadTimeout(StreamPath streamPath) throws Exception {
        var readTimeout = Duration.ofSeconds(1);
        var slowResolution = Duration.ofMillis(1_500);
        var data = RandomStringUtils.secure().nextAlphanumeric(10);
        var slowDnsGuard = DestinationGuard.builder()
                .mode(DestinationGuard.Mode.RELAXED)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .resolver(host -> {
                    try {
                        Thread.sleep(slowResolution);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return InetAddress.getAllByName(host);
                })
                .build();
        var slowDnsClient = DestinationGuardDns.clientBuilder(slowDnsGuard).protocols(List.of(Protocol.HTTP_1_1))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
        var timedClient = clientWithTimeouts(readTimeout, STREAM_READ_TIMEOUT, slowDnsClient);
        try (var server = rawServer(delayedStream(Duration.ofMillis(300), Duration.ZERO, data))) {
            var expectedEvents = List.of(new ServerSentEvent(null, data));
            long startNanos = System.nanoTime();

            var actualEvents = streamedEvents(timedClient, streamPath, rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualEvents).isEqualTo(expectedEvents);
            assertThat(actualElapsed).isGreaterThan(slowResolution);
        } finally {
            slowDnsClient.dispatcher().executorService().shutdownNow();
            slowDnsClient.connectionPool().evictAll();
        }
    }

    /**
     * The read timeout bounds only the wait for a stream's headers: a model that reasons before it answers can send
     * nothing for longer. Silent for less than the stream read timeout, the stream completes.
     */
    @ParameterizedTest
    @EnumSource(StreamPath.class)
    void aStreamSilentForLessThanTheStreamReadTimeoutCompletes(StreamPath streamPath) throws Exception {
        var readTimeout = Duration.ofMillis(500);
        var streamReadTimeout = Duration.ofSeconds(5);
        var silence = Duration.ofMillis(2_000);
        var data = RandomStringUtils.secure().nextAlphanumeric(10);
        var silentClient = clientWithTimeouts(readTimeout, streamReadTimeout, okHttpClient);
        try (var server = rawServer(silentStream(silence, data))) {
            var expectedEvents = List.of(new ServerSentEvent(null, data));
            long startNanos = System.nanoTime();

            var actualEvents = streamedEvents(silentClient, streamPath, rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualEvents).isEqualTo(expectedEvents);
            assertThat(actualElapsed).isGreaterThanOrEqualTo(silence);
        }
    }

    /** A stream that stalls past the stream read timeout fails then, rather than holding its call. */
    @ParameterizedTest
    @EnumSource(StreamPath.class)
    void aStreamSilentForLongerThanTheStreamReadTimeoutFailsAtIt(StreamPath streamPath) throws Exception {
        var readTimeout = Duration.ofMillis(500);
        var streamReadTimeout = Duration.ofSeconds(1);
        var expectedMaxElapsed = Duration.ofSeconds(5);
        var stalledClient = clientWithTimeouts(readTimeout, streamReadTimeout, okHttpClient);
        try (var server = rawServer(silentStream(Duration.ofSeconds(30), "late"))) {
            long startNanos = System.nanoTime();

            Throwable actualError = failure(stalledClient, CallPath.valueOf(streamPath.name()), rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualError).isExactlyInstanceOf(TimeoutException.class);
            assertThat(actualElapsed).isBetween(streamReadTimeout, expectedMaxElapsed);
        }
    }

    /** Counts the connections a client opens. */
    private static final class ConnectionCounter extends EventListener {
        private final AtomicInteger connects = new AtomicInteger();

        @Override
        public void connectStart(@NonNull Call call, @NonNull InetSocketAddress address, @NonNull Proxy proxy) {
            connects.incrementAndGet();
        }
    }

    private static OkHttpClient isolatedClient(ConnectionCounter counter, List<Protocol> protocols) {
        return new CustomLlmModule().llmHttpClient(TestConfigUtils.loadConfigTest().getLlmProviderClient(),
                RELAXED_GUARD)
                .newBuilder()
                .connectionPool(new ConnectionPool())
                .protocols(protocols)
                .eventListener(counter)
                .build();
    }

    private HttpRequest requestTo(String path) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url(wireMock.baseUrl() + path)
                .addHeader("Content-Type", "application/json")
                .body("{}")
                .build();
    }

    /**
     * A stream's timeouts are its call's own: a call that isn't a stream, made next on the same pooled connection,
     * still fails at the read timeout rather than waiting for the stream read timeout.
     */
    @Test
    void aCallAfterAStreamOnTheSameConnectionKeepsTheReadTimeout() throws Exception {
        var counter = new ConnectionCounter();
        var isolated = isolatedClient(counter, List.of(Protocol.HTTP_1_1));
        var timedClient = clientWithTimeouts(Duration.ofMillis(500), Duration.ofSeconds(30), isolated);
        var data = RandomStringUtils.secure().nextAlphanumeric(10);
        wireMock.stubFor(post(urlEqualTo("/stream")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: %s\n\n".formatted(data))));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{}")
                .withFixedDelay(3_000)));
        var expectedEvents = List.of(new ServerSentEvent(null, data));
        var expectedMaxElapsed = Duration.ofMillis(2_500);
        try {
            var actualEvents = streamedEvents(timedClient, StreamPath.STREAM, requestTo("/stream"));
            long startNanos = System.nanoTime();

            Throwable actualError = failure(timedClient, CallPath.EXECUTE, request("{}"));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualEvents).isEqualTo(expectedEvents);
            assertThat(actualError).isExactlyInstanceOf(TimeoutException.class);
            assertThat(actualElapsed).isLessThan(expectedMaxElapsed);
            // the call reused the stream's connection
            assertThat(counter.connects).hasValue(1);
        } finally {
            isolated.dispatcher().executorService().shutdownNow();
            isolated.connectionPool().evictAll();
        }
    }

    /**
     * Two exchanges at once on one HTTP/2 connection keep their own timeouts: the call that isn't a stream fails at
     * its read timeout, not the stream read timeout of the stream beside it, and its failure leaves the stream to
     * complete. WireMock sends an HTTP/2 stream's headers with its first chunk, so the stream's gaps stay within the
     * read timeout here; the silence past it is covered on HTTP/1.1 above.
     */
    @Test
    void concurrentExchangesOnOneHttp2ConnectionKeepTheirOwnTimeouts() throws Exception {
        var counter = new ConnectionCounter();
        var isolated = isolatedClient(counter, List.of(Protocol.H2_PRIOR_KNOWLEDGE));
        var readTimeout = Duration.ofSeconds(1);
        var timedClient = clientWithTimeouts(readTimeout, Duration.ofSeconds(30), isolated);
        var data = RandomStringUtils.secure().nextAlphanumeric(10);
        wireMock.stubFor(post(urlEqualTo("/stream")).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: %s\n\n".formatted(data))
                .withChunkedDribbleDelay(4, 2_000)));
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200).withBody("{}")
                .withFixedDelay(5_000)));
        var expectedMaxElapsed = Duration.ofMillis(3_000);
        var expectedEvents = List.of(new ServerSentEvent(null, data));
        wireMock.stubFor(post(urlEqualTo("/warm-up")).willReturn(aResponse().withStatus(200).withBody("{}")));
        try {
            // opens the one HTTP/2 connection, so the two exchanges below share it rather than race to open two
            timedClient.execute(requestTo("/warm-up"));
            var stream = CompletableFuture.supplyAsync(() -> {
                try {
                    return streamedEvents(timedClient, StreamPath.STREAM, requestTo("/stream"));
                } catch (Exception exception) {
                    throw new CompletionException(exception);
                }
            });
            long startNanos = System.nanoTime();
            var call = timedClient.executeAsync(request("{}"));

            var actualError = catchFailure(() -> call.orTimeout(10, TimeUnit.SECONDS).join());
            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            var actualEvents = stream.get(10, TimeUnit.SECONDS);

            assertThat(actualError).isExactlyInstanceOf(TimeoutException.class);
            assertThat(actualElapsed).isBetween(readTimeout, expectedMaxElapsed);
            assertThat(actualEvents).isEqualTo(expectedEvents);
            assertThat(counter.connects).hasValue(1);
        } finally {
            isolated.dispatcher().executorService().shutdownNow();
            isolated.connectionPool().evictAll();
        }
    }

    @ParameterizedTest
    @EnumSource(StreamPath.class)
    void aStreamWhoseHeadersNeverArriveFailsAtTheReadTimeout(StreamPath streamPath) throws Exception {
        var readTimeout = Duration.ofMillis(500);
        var expectedMaxElapsed = Duration.ofSeconds(5);
        var silentClient = clientWithReadTimeout(readTimeout, okHttpClient);
        try (var server = rawServer(out -> Thread.sleep(30_000))) {
            long startNanos = System.nanoTime();

            Throwable actualError = failure(silentClient, CallPath.valueOf(streamPath.name()), rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualError).isExactlyInstanceOf(TimeoutException.class);
            assertThat(actualElapsed).isBetween(readTimeout, expectedMaxElapsed);
        }
    }

    /** A non-2xx body read as a whole within the read timeout, however slowly each byte comes. */
    @ParameterizedTest
    @EnumSource(CallPath.class)
    void aTricklingErrorBodyIsCutAtTheReadTimeout(CallPath callPath) throws Exception {
        var readTimeout = Duration.ofMillis(500);
        var expectedMaxElapsed = Duration.ofSeconds(5);
        var trickleClient = clientWithReadTimeout(readTimeout, okHttpClient);
        // a byte every 100 ms, within the read timeout each time, for 100 seconds in all
        try (var server = rawServer(out -> {
            out.write("""
                    HTTP/1.1 400 Bad Request\r
                    Content-Length: 1000\r
                    \r
                    """.getBytes(StandardCharsets.UTF_8));
            for (int i = 0; i < 1_000; i++) {
                out.write('x');
                out.flush();
                Thread.sleep(100);
            }
        })) {
            long startNanos = System.nanoTime();

            Throwable actualError = failure(trickleClient, callPath, rawRequest(server));

            var actualElapsed = Duration.ofNanos(System.nanoTime() - startNanos);
            assertThat(actualError).isExactlyInstanceOf(HttpException.class)
                    .hasMessage(CustomLlmHttpClient.UNREADABLE_ERROR_BODY);
            assertThat(((HttpException) actualError).statusCode()).isEqualTo(400);
            assertThat(actualElapsed).isLessThan(expectedMaxElapsed);
        }
    }

    /** The events of a stream, without its response head. */
    private static List<ServerSentEvent> streamedEvents(HttpClient client, StreamPath streamPath, HttpRequest request)
            throws Exception {
        return switch (streamPath) {
            case LISTENER_STREAM -> {
                var events = new ArrayList<ServerSentEvent>();
                var done = new CompletableFuture<List<ServerSentEvent>>();
                client.execute(request, new ServerSentEventListener() {
                    @Override
                    public void onEvent(ServerSentEvent event) {
                        events.add(event);
                    }

                    @Override
                    public void onError(Throwable throwable) {
                        done.completeExceptionally(throwable);
                    }

                    @Override
                    public void onClose() {
                        done.complete(events);
                    }
                });
                yield done.get(10, TimeUnit.SECONDS);
            }
            case STREAM -> {
                var events = collect(client.stream(request, new DefaultServerSentEventParser()), Long.MAX_VALUE)
                        .get(10, TimeUnit.SECONDS);
                yield events.subList(1, events.size()).stream().map(ServerSentEvent.class::cast).toList();
            }
        };
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
            var boundedClient = new CustomLlmHttpClientBuilder(boundedOkHttpClient, ERROR_BODY_MAX_SIZE,
                    STREAM_READ_TIMEOUT)
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
        return failure(client, callPath, request);
    }

    private static Throwable failure(HttpClient client, CallPath callPath, HttpRequest request) throws Exception {
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
