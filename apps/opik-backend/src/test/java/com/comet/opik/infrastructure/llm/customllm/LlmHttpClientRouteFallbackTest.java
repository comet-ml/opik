package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.github.tomakehurst.wiremock.http.Fault;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpMethod;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.DefaultServerSentEventParser;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import lombok.NonNull;
import okhttp3.Call;
import okhttp3.EventListener;
import okhttp3.OkHttpClient;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * The shared LLM client against a provider host with an address that can't be reached beside one that can: the
 * provider listens on 127.0.0.1 only, so connecting to ::1, the other address the host resolves to, fails at once,
 * whether the machine has IPv6 or not.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class LlmHttpClientRouteFallbackTest {

    private static final String HOST = "llm-provider.test";
    private static final String PATH = "/v1/chat/completions";
    private static final int ERROR_BODY_MAX_SIZE = 64;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration WAIT = Duration.ofSeconds(30);

    private WireMockServer wireMock;
    private InetAddress reachable;
    private InetAddress unreachable;
    private OkHttpClient okHttpClient;

    @BeforeAll
    void setUpAll() throws UnknownHostException {
        reachable = InetAddress.getByName("127.0.0.1");
        unreachable = InetAddress.getByName("::1");
        wireMock = new WireMockServer(WireMockConfiguration.options().bindAddress("127.0.0.1").dynamicPort());
        wireMock.start();
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
    }

    @AfterEach
    void tearDown() {
        if (okHttpClient != null) {
            okHttpClient.dispatcher().executorService().shutdownNow();
            okHttpClient.connectionPool().evictAll();
        }
    }

    /** The production client, with its own connection pool and route database, resolving the host as given. */
    private OkHttpClient newOkHttpClient(DestinationGuard.Mode mode, InetAddress... addresses) {
        var guard = DestinationGuard.builder()
                .mode(mode)
                .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
                .resolver(host -> addresses.clone())
                .build();
        okHttpClient = CustomLlmModule.llmHttpClient(TestConfigUtils.loadConfigTest().getLlmProviderClient(), guard);
        return okHttpClient;
    }

    private static HttpClient client(OkHttpClient okHttpClient, Duration readTimeout, Duration streamReadTimeout) {
        return new CustomLlmHttpClientBuilder(okHttpClient, ERROR_BODY_MAX_SIZE, streamReadTimeout)
                .connectTimeout(CONNECT_TIMEOUT)
                .readTimeout(readTimeout)
                .build();
    }

    private HttpRequest request(String path) {
        return HttpRequest.builder()
                .method(HttpMethod.POST)
                .url("http://%s:%d%s".formatted(HOST, wireMock.port(), path))
                .addHeader("Content-Type", "application/json")
                .body("{\"content\": \"%s\"}".formatted(RandomStringUtils.secure().nextAlphanumeric(20)))
                .build();
    }

    private void stubSuccess(String path, String body) {
        wireMock.stubFor(post(urlEqualTo(path)).willReturn(aResponse().withStatus(200).withBody(body)));
    }

    @Test
    void anAddressThatCannotBeReachedFailsOverToTheNextOne() {
        var expectedBody = RandomStringUtils.secure().nextAlphanumeric(20);
        stubSuccess(PATH, expectedBody);
        var client = client(newOkHttpClient(DestinationGuard.Mode.RELAXED, unreachable, reachable), READ_TIMEOUT,
                READ_TIMEOUT);

        var actualResponse = client.execute(request(PATH));

        assertThat(actualResponse.body()).isEqualTo(expectedBody);
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    /**
     * A stream cancelled before its headers, on a connection that has completed no call, makes OkHttp try that address
     * after the host's others from then on. With the reachable address tried last, calls still reach it, the timed
     * out ones included, and none is sent twice.
     */
    @Test
    void callsCancelledBeforeTheirHeadersDoNotLeaveOnlyTheUnreachableAddress() throws Exception {
        var streamPath = "/stream";
        var headerTimeout = Duration.ofMillis(300);
        wireMock.stubFor(post(urlEqualTo(streamPath)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "text/event-stream")
                .withBody("data: late\n\n")
                .withFixedDelay(5_000)));
        var expectedBody = RandomStringUtils.secure().nextAlphanumeric(20);
        stubSuccess(PATH, expectedBody);
        var okHttpClient = newOkHttpClient(DestinationGuard.Mode.RELAXED, reachable, unreachable);
        var timedOutClient = client(okHttpClient, headerTimeout, headerTimeout);
        var client = client(okHttpClient, READ_TIMEOUT, READ_TIMEOUT);

        var actualFirstError = streamFailure(timedOutClient, request(streamPath));
        var actualSecondError = streamFailure(timedOutClient, request(streamPath));
        var actualBodies = Stream.of(request(PATH), request(PATH))
                .map(client::execute)
                .map(SuccessfulHttpResponse::body)
                .toList();

        assertThat(actualFirstError).isExactlyInstanceOf(TimeoutException.class);
        assertThat(actualSecondError).isExactlyInstanceOf(TimeoutException.class);
        assertThat(actualBodies).isEqualTo(List.of(expectedBody, expectedBody));
        wireMock.verify(2, postRequestedFor(urlEqualTo(streamPath)));
        wireMock.verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    static Stream<Arguments> aRequestWhoseSendingStartedIsNeverSentAgain() {
        return Stream.of(Fault.CONNECTION_RESET_BY_PEER, Fault.EMPTY_RESPONSE)
                .flatMap(fault -> Stream.of(arguments(fault, true), arguments(fault, false)));
    }

    /**
     * A provider that closes the connection after taking the request may have acted on it, so the failure is reported
     * rather than the request sent again, whether the connection was a pooled one, which OkHttp would otherwise take
     * for a stale connection and resend on a fresh one, or a new one.
     */
    @ParameterizedTest(name = "{0}, pooled connection: {1}")
    @MethodSource
    void aRequestWhoseSendingStartedIsNeverSentAgain(Fault fault, boolean pooled) {
        var warmUpPath = "/warm-up";
        stubSuccess(warmUpPath, "{}");
        wireMock.stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withFault(fault)));
        var connections = new ConnectionCounter();
        var counted = newOkHttpClient(DestinationGuard.Mode.RELAXED, reachable).newBuilder()
                .eventListener(connections)
                .build();
        var client = client(counted, READ_TIMEOUT, READ_TIMEOUT);
        if (pooled) {
            client.execute(request(warmUpPath));
        }
        var expectedConnections = 1;

        var actualError = catchThrowable(() -> client.execute(request(PATH)));

        assertThat(actualError).isNotNull();
        wireMock.verify(1, postRequestedFor(urlEqualTo(PATH)));
        assertThat(connections.connects).hasValue(expectedConnections);
    }

    /** Failing over never reaches an address the destination guard refused: the host is refused as a whole. */
    @Test
    void aHostWithAnAddressTheGuardRefusesIsNeverConnectedTo() throws UnknownHostException {
        stubSuccess(PATH, "{}");
        var client = client(newOkHttpClient(DestinationGuard.Mode.STRICT, InetAddress.getByName("192.0.2.1"),
                reachable), READ_TIMEOUT, READ_TIMEOUT);

        var actualError = catchThrowable(() -> client.execute(request(PATH)));

        assertThat(actualError).isExactlyInstanceOf(ProviderRequestRefusedException.class);
        wireMock.verify(0, anyRequestedFor(anyUrl()));
    }

    private static Throwable streamFailure(HttpClient client, HttpRequest request) throws Exception {
        var result = new CompletableFuture<Throwable>();
        client.execute(request, new DefaultServerSentEventParser(), new ServerSentEventListener() {
            @Override
            public void onEvent(ServerSentEvent event) {
                result.complete(null);
            }

            @Override
            public void onError(Throwable throwable) {
                result.complete(throwable);
            }
        });
        return result.get(WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    /** Counts the connections a client opens. */
    private static final class ConnectionCounter extends EventListener {
        private final AtomicInteger connects = new AtomicInteger();

        @Override
        public void connectStart(@NonNull Call call, @NonNull InetSocketAddress address, @NonNull Proxy proxy) {
            connects.incrementAndGet();
        }
    }
}
