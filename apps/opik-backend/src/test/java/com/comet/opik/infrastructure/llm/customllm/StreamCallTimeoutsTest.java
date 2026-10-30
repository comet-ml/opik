package com.comet.opik.infrastructure.llm.customllm;

import jakarta.ws.rs.core.HttpHeaders;
import okhttp3.Call;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class StreamCallTimeoutsTest {

    /** A raw server answering one call: nothing for {@code headerDelay}, then a JSON reply. */
    private static ServerSocket delayedHeadersServer(Duration headerDelay) throws IOException {
        var server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread.ofVirtual().start(() -> {
            try (Socket socket = server.accept(); var out = socket.getOutputStream()) {
                readRequestHead(socket.getInputStream());
                Thread.sleep(headerDelay);
                out.write("""
                        HTTP/1.1 200 OK\r
                        Content-Type: application/json\r
                        Content-Length: 2\r
                        \r
                        {}""".getBytes(StandardCharsets.UTF_8));
                out.flush();
            } catch (IOException ignored) {
                // the call reports what it got
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        return server;
    }

    private static void readRequestHead(InputStream in) throws IOException {
        var head = new StringBuilder();
        while (!head.toString().endsWith("\r\n\r\n")) {
            int read = in.read();
            if (read == -1) {
                return;
            }
            head.append((char) read);
        }
    }

    /** Calls a server that holds its headers for longer than the read timeout, within the stream read timeout. */
    private static Throwable callWithHeldHeaders(String accept) throws Exception {
        try (var server = delayedHeadersServer(Duration.ofMillis(1_000))) {
            var client = StreamCallTimeouts.forEventStreams(Duration.ofMillis(300), Duration.ofSeconds(5))
                    .applyTo(new OkHttpClient());
            var request = new Request.Builder()
                    .url("http://localhost:%d/v1/responses".formatted(server.getLocalPort()))
                    .header(HttpHeaders.ACCEPT, accept)
                    .build();

            return catchThrowable(() -> {
                try (var response = client.newCall(request).execute()) {
                    response.body().string();
                }
            });
        }
    }

    @ParameterizedTest(name = "Accept ''{0}''")
    @ValueSource(strings = {"text/event-stream", "application/json, Text/Event-Stream;q=0.5"})
    void anAcceptedEventStreamWaitsForItsHeadersUpToTheStreamReadTimeout(String accept) throws Exception {
        var actualError = callWithHeldHeaders(accept);

        assertThat(actualError).isNull();
    }

    /** Only an entry that is exactly {@code text/event-stream}, with a quality above 0, is a stream. */
    @ParameterizedTest(name = "Accept ''{0}''")
    @ValueSource(strings = {
            "text/event-stream;q=0",
            "text/event-stream;q=0.0, application/json",
            "text/event-streaming",
            "text/event-stream;q=x",
            "text/"})
    void anyOtherAcceptWaitsForItsHeadersUpToTheReadTimeout(String accept) throws Exception {
        var actualError = callWithHeldHeaders(accept);

        assertThat(actualError).isExactlyInstanceOf(SocketTimeoutException.class)
                .hasMessage("the response headers took longer than their timeout");
    }

    private static Stream<Arguments> aReplyWithoutABodyIsPassedOn() {
        var readTimeout = Duration.ofSeconds(1);
        var streamReadTimeout = Duration.ofSeconds(5);
        return Stream.of(
                arguments("streams only", StreamCallTimeouts.forStreams(readTimeout, streamReadTimeout)),
                arguments("event streams", StreamCallTimeouts.forEventStreams(readTimeout, streamReadTimeout)));
    }

    /**
     * Over the wire, OkHttp hands a network interceptor an empty body rather than none, so a reply without one is
     * built here: the interceptor treats it as no stream and passes it on.
     */
    @ParameterizedTest(name = "{0}")
    @MethodSource
    void aReplyWithoutABodyIsPassedOn(String interceptorName, StreamCallTimeouts interceptor) throws Exception {
        var request = new Request.Builder().url("http://localhost/v1/responses").build();
        var expectedResponse = new Response.Builder()
                .request(request)
                .protocol(Protocol.HTTP_1_1)
                .code(204)
                .message("No Content")
                .build();
        var chain = mock(Interceptor.Chain.class);
        when(chain.request()).thenReturn(request);
        when(chain.call()).thenReturn(mock(Call.class));
        when(chain.proceed(any())).thenReturn(expectedResponse);

        var actualResponse = interceptor.intercept(chain);

        assertThat(actualResponse).isSameAs(expectedResponse);
    }
}
