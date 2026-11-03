package com.comet.opik.infrastructure.http;

import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.RequestBody;
import okhttp3.Response;
import okio.BufferedSink;

import java.io.IOException;

/**
 * Lets an OkHttp client fail over to a host's next address without ever sending a request twice.
 *
 * <p>OkHttp recovers from a failure on its own in two ways, both only with {@code retryOnConnectionFailure} on. When
 * connecting fails, before any of the request went out, it tries the host's next address. It also remembers an address
 * whose first exchange failed, a cancelled one included, and tries it after the others from then on, so a client that
 * can't fail over is left with the addresses it can't reach, IPv6 ones on a network without IPv6 for instance. Once the
 * request started going out, on a pooled connection the server had closed for instance, it sends the request again,
 * and so it does after a 408, a 503 asking for an immediate retry or a 421, unless the request's body is one-shot.
 *
 * <p>Every body this interceptor passes on is one-shot, so a client gets the fail-over and never the resend. A request
 * without a body, a GET or a HEAD, may still be sent again, which repeats no action.
 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class OneShotRequestBodies implements Interceptor {

    private static final OneShotRequestBodies INSTANCE = new OneShotRequestBodies();

    /**
     * {@code builder} failing over to a host's next address when connecting fails, and never resending a request
     * whose body started going out. An application interceptor, so it sees each call once, before any OkHttp retry.
     */
    public static OkHttpClient.Builder failOverWithoutResending(@NonNull OkHttpClient.Builder builder) {
        return builder
                .retryOnConnectionFailure(true)
                .addInterceptor(INSTANCE);
    }

    @Override
    public @NonNull Response intercept(@NonNull Chain chain) throws IOException {
        var request = chain.request();
        var body = request.body();
        if (body == null || body.isOneShot()) {
            return chain.proceed(request);
        }
        return chain.proceed(request.newBuilder()
                .method(request.method(), new OneShotRequestBody(body))
                .build());
    }

    /** {@code delegate}, which OkHttp may write at most once. */
    @RequiredArgsConstructor
    private static final class OneShotRequestBody extends RequestBody {

        private final RequestBody delegate;

        @Override
        public MediaType contentType() {
            return delegate.contentType();
        }

        @Override
        public long contentLength() throws IOException {
            return delegate.contentLength();
        }

        @Override
        public void writeTo(@NonNull BufferedSink sink) throws IOException {
            delegate.writeTo(sink);
        }

        @Override
        public boolean isDuplex() {
            return delegate.isDuplex();
        }

        @Override
        public boolean isOneShot() {
            return true;
        }
    }
}
