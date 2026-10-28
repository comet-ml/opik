package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.http.BodyDeadline;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import lombok.AccessLevel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.apache.commons.lang3.Strings;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The timeouts of a call that may be a stream. A call that isn't a stream waits for its response headers up to the
 * read timeout, as the JDK client did. A stream waits for them up to the longer of the read timeout and the stream read
 * timeout, as a provider can hold its headers while the model reasons, which is silence as much as a pause in the body;
 * once a stream's headers arrived, the stream read timeout bounds each read of its body.
 *
 * <p>A network interceptor, so the header wait starts once the connection is established and connecting stays
 * bounded by the connect timeout alone. The client's own read timeout, which OkHttp applies to each read, is
 * {@link #clientReadTimeout} so that it never fires before either of these. Everything is the call's own: the body's
 * timeout belongs to its exchange, and nothing is set on a connection another exchange shares.
 *
 * <p>An unset, zero read timeout leaves the header wait unbounded, as it did with the JDK client; the stream read
 * timeout still bounds the body.
 */
@RequiredArgsConstructor(access = AccessLevel.PRIVATE)
public final class StreamCallTimeouts implements Interceptor {

    private final Duration readTimeout;
    private final Duration streamReadTimeout;
    private final Predicate<Request> asksForStream;
    private final Predicate<Response> isStream;

    /** For a client that makes only streaming calls. */
    public static StreamCallTimeouts forStreams(@NonNull Duration readTimeout, @NonNull Duration streamReadTimeout) {
        return new StreamCallTimeouts(readTimeout, streamReadTimeout, request -> true, response -> true);
    }

    /**
     * For a client that makes both kinds of calls: only a call that accepts an event stream waits for its headers as a
     * stream, and only an event stream's body takes the stream read timeout.
     */
    public static StreamCallTimeouts forEventStreams(@NonNull Duration readTimeout,
            @NonNull Duration streamReadTimeout) {
        return new StreamCallTimeouts(readTimeout, streamReadTimeout, StreamCallTimeouts::acceptsEventStream,
                StreamCallTimeouts::isEventStream);
    }

    /**
     * The read timeout to give the client these timeouts are added to: the longer of the two, so it fires before
     * neither, or none when the read timeout is unset, so the header wait is unbounded.
     */
    public static Duration clientReadTimeout(@NonNull Duration readTimeout, @NonNull Duration streamReadTimeout) {
        if (readTimeout.isZero()) {
            return Duration.ZERO;
        }
        return readTimeout.compareTo(streamReadTimeout) >= 0 ? readTimeout : streamReadTimeout;
    }

    /** {@code client} with these timeouts, on the same connection pool and dispatcher. */
    public OkHttpClient applyTo(@NonNull OkHttpClient client) {
        return client.newBuilder()
                .readTimeout(clientReadTimeout(readTimeout, streamReadTimeout))
                .addNetworkInterceptor(this)
                .build();
    }

    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        var response = withHeaderDeadline(chain);
        // a reply without a body, such as a 204, isn't a stream
        var body = response.body();
        if (body != null && response.isSuccessful() && isStream.test(response)) {
            body.source().timeout().timeout(streamReadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return response;
    }

    private Response withHeaderDeadline(Chain chain) throws IOException {
        var request = chain.request();
        var headerTimeout = asksForStream.test(request)
                ? clientReadTimeout(readTimeout, streamReadTimeout)
                : readTimeout;
        if (headerTimeout.isZero()) {
            return chain.proceed(request);
        }
        var call = chain.call();
        try (var deadline = BodyDeadline.closing(call::cancel, headerTimeout)) {
            try {
                return chain.proceed(request);
            } catch (IOException exception) {
                if (deadline.expired()) {
                    var timedOut = new SocketTimeoutException("the response headers took longer than their timeout");
                    timedOut.initCause(exception);
                    throw timedOut;
                }
                throw exception;
            }
        }
    }

    private static boolean acceptsEventStream(Request request) {
        return request.headers(HttpHeaders.ACCEPT).stream()
                .anyMatch(accept -> Strings.CI.contains(accept, MediaType.SERVER_SENT_EVENTS));
    }

    private static boolean isEventStream(Response response) {
        var contentType = Optional.ofNullable(response.body()).map(ResponseBody::contentType);
        return contentType.filter(type -> "event-stream".equalsIgnoreCase(type.subtype())).isPresent();
    }
}
