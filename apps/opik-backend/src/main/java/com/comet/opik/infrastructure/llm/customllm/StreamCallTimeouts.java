package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.http.BodyDeadline;
import lombok.NonNull;
import okhttp3.Interceptor;
import okhttp3.OkHttpClient;
import okhttp3.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * The timeouts of a call that may be a stream, as the JDK client applied them: the read timeout bounds the wait for
 * the response headers, and once a stream's headers arrived, the stream read timeout bounds each read of its body.
 *
 * <p>A network interceptor, so the header wait starts once the connection is established and connecting stays
 * bounded by the connect timeout alone. The client's own read timeout, which OkHttp applies to each read, is
 * {@link #clientReadTimeout} so that it never fires before either of these. Everything is the call's own: the body's
 * timeout belongs to its exchange, and nothing is set on a connection another exchange shares.
 *
 * <p>An unset, zero read timeout leaves the header wait unbounded, as it did with the JDK client; the stream read
 * timeout still bounds the body.
 */
public final class StreamCallTimeouts implements Interceptor {

    private final Duration readTimeout;
    private final Duration streamReadTimeout;
    private final Predicate<Response> isStream;

    private StreamCallTimeouts(Duration readTimeout, Duration streamReadTimeout, Predicate<Response> isStream) {
        this.readTimeout = readTimeout;
        this.streamReadTimeout = streamReadTimeout;
        this.isStream = isStream;
    }

    /** For a client that makes only streaming calls. */
    public static StreamCallTimeouts forStreams(@NonNull Duration readTimeout, @NonNull Duration streamReadTimeout) {
        return new StreamCallTimeouts(readTimeout, streamReadTimeout, response -> true);
    }

    /** For a client that makes both kinds of calls: only an event stream's body takes the stream read timeout. */
    public static StreamCallTimeouts forEventStreams(@NonNull Duration readTimeout,
            @NonNull Duration streamReadTimeout) {
        return new StreamCallTimeouts(readTimeout, streamReadTimeout, StreamCallTimeouts::isEventStream);
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
        if (response.isSuccessful() && isStream.test(response)) {
            response.body().source().timeout().timeout(streamReadTimeout.toMillis(), TimeUnit.MILLISECONDS);
        }
        return response;
    }

    private Response withHeaderDeadline(Chain chain) throws IOException {
        if (readTimeout.isZero()) {
            return chain.proceed(chain.request());
        }
        var call = chain.call();
        try (var deadline = BodyDeadline.closing(call::cancel, readTimeout)) {
            try {
                return chain.proceed(chain.request());
            } catch (IOException exception) {
                if (deadline.expired()) {
                    var timedOut = new SocketTimeoutException("the response headers took longer than the read timeout");
                    timedOut.initCause(exception);
                    throw timedOut;
                }
                throw exception;
            }
        }
    }

    private static boolean isEventStream(Response response) {
        var contentType = response.body().contentType();
        return contentType != null && "event-stream".equalsIgnoreCase(contentType.subtype());
    }
}
