package com.comet.opik.infrastructure.llm.customllm;

import lombok.NonNull;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

/**
 * A network interceptor giving a stream the stream read timeout once its headers arrived. OkHttp sets the read
 * timeout, which bounds the wait for the headers, as the socket's and the body's read timeout too, and that would end a stream
 * that is silent for longer, as a model that reasons before answering can be. The stream read timeout is long enough
 * for that, and short enough that a stalled stream can't hold its call forever. Each exchange on a pooled connection
 * sets its own again.
 */
public final class StreamReadTimeoutInterceptor implements Interceptor {

    private final int streamReadTimeoutMillis;
    private final Predicate<Response> isStream;

    private StreamReadTimeoutInterceptor(Duration streamReadTimeout, Predicate<Response> isStream) {
        this.streamReadTimeoutMillis = Math.toIntExact(streamReadTimeout.toMillis());
        this.isStream = isStream;
    }

    /** For a client that makes only streaming calls. */
    public static StreamReadTimeoutInterceptor forEveryResponse(@NonNull Duration streamReadTimeout) {
        return new StreamReadTimeoutInterceptor(streamReadTimeout, response -> true);
    }

    /** For a client that makes both kinds of calls: only an event stream's socket takes the stream read timeout. */
    public static StreamReadTimeoutInterceptor forEventStreams(@NonNull Duration streamReadTimeout) {
        return new StreamReadTimeoutInterceptor(streamReadTimeout, StreamReadTimeoutInterceptor::isEventStream);
    }

    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        var response = chain.proceed(chain.request());
        var connection = chain.connection();
        if (connection != null && response.isSuccessful() && isStream.test(response)) {
            // both bound each read of the body: the socket's, and the one Okio applies over it
            connection.socket().setSoTimeout(streamReadTimeoutMillis);
            response.body().source().timeout().timeout(streamReadTimeoutMillis, TimeUnit.MILLISECONDS);
        }
        return response;
    }

    private static boolean isEventStream(Response response) {
        var contentType = response.body().contentType();
        return contentType != null && "event-stream".equalsIgnoreCase(contentType.subtype());
    }
}
