package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.net.DestinationGuardDns;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.http.client.HttpClient;
import dev.langchain4j.http.client.HttpRequest;
import dev.langchain4j.http.client.SuccessfulHttpResponse;
import dev.langchain4j.http.client.sse.HttpResponseReceived;
import dev.langchain4j.http.client.sse.HttpStreamingEvent;
import dev.langchain4j.http.client.sse.ServerSentEvent;
import dev.langchain4j.http.client.sse.ServerSentEventContext;
import dev.langchain4j.http.client.sse.ServerSentEventListener;
import dev.langchain4j.http.client.sse.ServerSentEventParser;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import mutiny.zero.BackpressureStrategy;
import mutiny.zero.Tube;
import mutiny.zero.TubeConfiguration;
import mutiny.zero.ZeroPublisher;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.MultipartBody;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.BufferedSink;
import okio.BufferedSource;
import okio.Utf8;

import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow;
import java.util.concurrent.TimeUnit;

import static dev.langchain4j.http.client.sse.ServerSentEventListenerUtils.ignoringExceptions;

/**
 * The custom provider client's transport: OkHttp, whose {@link DestinationGuardDns} checks each address connected to.
 * Otherwise it behaves as the JDK client it replaces: the read timeout bounds the wait for the response headers but
 * not the body, a non-2xx response is an {@link HttpException} carrying its body, and a stream is read on a client
 * thread. Unlike the JDK client, a non-2xx body is read only up to {@link #errorBodyMaxSize}: callers pass on just a
 * short message parsed from it.
 */
@Slf4j
class CustomLlmHttpClient implements HttpClient {

    /** The buffer between a {@link #stream} and its subscriber, in events: the JDK client's default. */
    static final int STREAMING_BUFFER_SIZE = 16384;

    /** Size of each read from a streamed body, in bytes. */
    private static final int STREAMING_READ_SIZE = 8192;

    /** The message of the {@link HttpException} for a non-2xx response whose body could not be read. */
    static final String UNREADABLE_ERROR_BODY = "Cannot read error response body";

    /** Derived from one of the shared clients, whose connection pool and dispatcher it uses. */
    @Getter(AccessLevel.PACKAGE)
    private final OkHttpClient client;

    /**
     * {@link #client} for streams, on the same pool and dispatcher: the read timeout bounds the wait for the headers,
     * and the stream read timeout each read of the body, see {@link StreamCallTimeouts}.
     */
    private final OkHttpClient streamingClient;

    /** Most bytes read from a non-2xx response body; the rest is discarded. */
    private final int errorBodyMaxSize;

    CustomLlmHttpClient(@NonNull OkHttpClient client, int errorBodyMaxSize, @NonNull Duration streamReadTimeout) {
        this.client = client;
        this.streamingClient = StreamCallTimeouts
                .forStreams(Duration.ofMillis(client.readTimeoutMillis()), streamReadTimeout)
                .applyTo(client);
        this.errorBodyMaxSize = errorBodyMaxSize;
    }

    @Override
    public SuccessfulHttpResponse execute(@NonNull HttpRequest request) {
        var call = client.newCall(toOkHttpRequest(request));
        try {
            return toResponse(call, call.execute());
        } catch (IOException exception) {
            throw failure(exception);
        }
    }

    @Override
    public CompletableFuture<SuccessfulHttpResponse> executeAsync(@NonNull HttpRequest request) {
        var call = client.newCall(toOkHttpRequest(request));
        var result = new CompletableFuture<SuccessfulHttpResponse>();
        result.whenComplete((response, throwable) -> {
            if (result.isCancelled()) {
                call.cancel();
            }
        });
        call.enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                result.completeExceptionally(failure(exception));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try {
                    result.complete(toResponse(call, response));
                } catch (IOException exception) {
                    result.completeExceptionally(failure(exception));
                } catch (RuntimeException exception) {
                    result.completeExceptionally(exception);
                }
            }
        });
        return result;
    }

    @Override
    public void execute(@NonNull HttpRequest request, @NonNull ServerSentEventParser parser,
            @NonNull ServerSentEventListener listener) {
        streamingClient.newCall(toOkHttpRequest(request)).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                ignoringExceptions(() -> listener.onError(failure(exception)));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                // its timeout is the stream read timeout, see StreamCallTimeouts
                var body = response.body();
                try (response; InputStream bodyStream = body.byteStream()) {
                    if (!response.isSuccessful()) {
                        var exception = new HttpException(response.code(), readErrorBody(call, body));
                        ignoringExceptions(() -> listener.onError(exception));
                        return;
                    }
                    ignoringExceptions(() -> listener.onOpen(toResponse(response, null)));
                    parser.parse(bodyStream, new FailureMappingListener(listener));
                    ignoringExceptions(listener::onClose);
                } catch (IOException | RuntimeException exception) {
                    ignoringExceptions(() -> listener.onError(
                            exception instanceof IOException ioException ? failure(ioException) : exception));
                }
            }
        });
    }

    /**
     * As the JDK client's: a cold publisher whose every subscription makes the call, emitting the response head and
     * then each parsed event through a buffer of {@link #STREAMING_BUFFER_SIZE} events. Any end of the subscription,
     * a cancel or a buffer overflow included, cancels the call, which also ends a read blocked on the body.
     */
    @Override
    public Flow.Publisher<HttpStreamingEvent> stream(@NonNull HttpRequest request,
            @NonNull ServerSentEventParser parser) {
        var okHttpRequest = toOkHttpRequest(request);
        var configuration = new TubeConfiguration()
                .withBackpressureStrategy(BackpressureStrategy.BUFFER)
                .withBufferSize(STREAMING_BUFFER_SIZE);
        return ZeroPublisher.create(configuration, tube -> {
            var call = streamingClient.newCall(okHttpRequest);
            tube.whenTerminates(call::cancel);
            call.enqueue(new Callback() {
                @Override
                public void onFailure(@NonNull Call call, @NonNull IOException exception) {
                    tube.fail(failure(exception));
                }

                @Override
                public void onResponse(@NonNull Call call, @NonNull Response response) {
                    try (response) {
                        streamEvents(call, response, parser, tube);
                    } catch (IOException exception) {
                        tube.fail(failure(exception));
                    } catch (RuntimeException exception) {
                        tube.fail(exception);
                    }
                }
            });
        });
    }

    /** Sends the response head and then each event parsed from the body, until the body ends or the tube does. */
    private void streamEvents(Call call, Response response, ServerSentEventParser parser,
            Tube<HttpStreamingEvent> tube) throws IOException {
        // its timeout is the stream read timeout, see StreamCallTimeouts
        var body = response.body();
        if (!response.isSuccessful()) {
            tube.fail(new HttpException(response.code(), readErrorBody(call, body)));
            return;
        }
        tube.send(new HttpResponseReceived(toResponse(response, null)));
        var incremental = parser.incremental();
        BufferedSource source = body.source();
        var chunk = new byte[STREAMING_READ_SIZE];
        int read;
        while (!tube.cancelled() && (read = source.read(chunk)) != -1) {
            for (ServerSentEvent event : incremental.feed(ByteBuffer.wrap(chunk, 0, read))) {
                tube.send(event);
            }
        }
        if (!tube.cancelled()) {
            incremental.flush().forEach(tube::send);
            tube.complete();
        }
    }

    private static Request toOkHttpRequest(HttpRequest request) {
        // parsed as the JDK client did, so a URL it refused is still refused
        var builder = new Request.Builder().url(HttpUrl.get(URI.create(request.url()).toString()));
        request.headers().forEach((name, values) -> {
            if (values != null) {
                values.forEach(value -> builder.addHeader(name, value));
            }
        });
        // as the JDK client did: OkHttp inflates a compressed reply only when it asked for one itself, and an
        // inflated body has no bound, so an uncompressed one is asked for unless the caller chose otherwise
        if (request.headers().keySet().stream().noneMatch(HttpHeaders.ACCEPT_ENCODING::equalsIgnoreCase)) {
            builder.header(HttpHeaders.ACCEPT_ENCODING, "identity");
        }
        RequestBody body = requestBody(request);
        return builder.method(request.method().name(), switch (request.method()) {
            // OkHttp requires a body on POST, where the JDK client sent an empty one
            case POST -> body != null ? body : RequestBody.create(new byte[0]);
            case GET, DELETE -> body;
        }).build();
    }

    private static RequestBody requestBody(HttpRequest request) {
        if (request.formDataFields().isEmpty() && request.formDataFiles().isEmpty()) {
            return request.body() != null ? new StringRequestBody(request.body()) : null;
        }
        var multipart = new MultipartBody.Builder().setType(MultipartBody.FORM);
        request.formDataFields().forEach(multipart::addFormDataPart);
        // as the JDK client did, a file without a content type is sent without a Content-Type header
        request.formDataFiles().forEach((name, file) -> multipart.addFormDataPart(name, file.fileName(),
                RequestBody.create(file.content(),
                        file.contentType() != null ? MediaType.parse(file.contentType()) : null)));
        return multipart.build();
    }

    /** The JDK client's timeout ended once the headers arrived, so reading the body has none either. */
    private static ResponseBody untimedBody(Response response) {
        var body = response.body();
        body.source().timeout().clearTimeout();
        return body;
    }

    /** The whole body of a 2xx response, which LangChain4j parses, or else an {@link HttpException}. */
    private SuccessfulHttpResponse toResponse(Call call, Response response) throws IOException {
        try (response) {
            var body = untimedBody(response);
            if (!response.isSuccessful()) {
                throw new HttpException(response.code(), readErrorBody(call, body));
            }
            return toResponse(response, body.bytes());
        }
    }

    private static SuccessfulHttpResponse toResponse(Response response, byte[] body) {
        return SuccessfulHttpResponse.builder()
                .statusCode(response.code())
                .headers(response.headers().toMultimap())
                .body(body)
                .build();
    }

    /**
     * At most {@link #errorBodyMaxSize} bytes of a non-2xx body, read within the read timeout as a whole, so a body
     * that trickles in can't hold the call past it. When the body may hold more, the call is cancelled, so the rest
     * is neither read nor drained for connection reuse.
     */
    private String readErrorBody(Call call, ResponseBody body) {
        if (client.readTimeoutMillis() > 0) {
            body.source().timeout().deadline(client.readTimeoutMillis(), TimeUnit.MILLISECONDS);
        }
        try {
            byte[] bytes = body.byteStream().readNBytes(errorBodyMaxSize);
            if (bytes.length == errorBodyMaxSize) {
                call.cancel();
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            // the message can name the destination, so it is neither logged nor passed on
            log.warn("Cannot read the LLM provider error response body, error type '{}'",
                    exception.getClass().getSimpleName());
            return UNREADABLE_ERROR_BODY;
        }
    }

    /**
     * Maps a transport failure as the JDK client did: a timeout to a {@link TimeoutException}, and an interrupt
     * restores the thread's interrupt flag before it's wrapped.
     */
    private static RuntimeException failure(IOException exception) {
        if (DestinationGuardDns.isRefused(exception)) {
            // no exception detail: it describes a user-supplied URL and how our network resolves it
            log.warn("Refused LLM provider destination");
            return new ProviderRequestRefusedException(ProviderRequestRefusedException.Reason.DESTINATION);
        }
        if (exception instanceof SocketTimeoutException) {
            return new TimeoutException(exception);
        }
        if (exception instanceof InterruptedIOException) {
            Thread.currentThread().interrupt();
        }
        return new RuntimeException(exception);
    }

    /** Passes on what the parser reports, a read failure mapped as any other transport failure, timeouts included. */
    @RequiredArgsConstructor
    private static class FailureMappingListener implements ServerSentEventListener {

        private final ServerSentEventListener delegate;

        @Override
        public void onEvent(@NonNull ServerSentEvent event, @NonNull ServerSentEventContext context) {
            delegate.onEvent(event, context);
        }

        @Override
        public void onEvent(@NonNull ServerSentEvent event) {
            delegate.onEvent(event);
        }

        @Override
        public void onError(@NonNull Throwable throwable) {
            delegate.onError(throwable instanceof IOException ioException ? failure(ioException) : throwable);
        }
    }

    /**
     * A string request body written straight to the wire, so no encoded copy is held, and written again whenever
     * OkHttp resends the request. It has no media type: the request's own Content-Type header is sent as is.
     */
    @RequiredArgsConstructor
    private static class StringRequestBody extends RequestBody {

        private final String body;

        @Override
        public MediaType contentType() {
            return null;
        }

        @Override
        public long contentLength() {
            return Utf8.size(body);
        }

        @Override
        public void writeTo(@NonNull BufferedSink sink) throws IOException {
            sink.writeUtf8(body);
        }
    }
}
