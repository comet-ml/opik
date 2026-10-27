package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.infrastructure.http.BodyDeadline;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import okhttp3.Interceptor;
import okhttp3.Response;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;

/**
 * An application interceptor that cancels a call whose response headers haven't arrived within the timeout, as the
 * JDK client's request timeout did. A stream's client takes the stream read timeout as its read timeout, which bounds
 * each read of the body, so this bounds the wait for the headers. Both apply to the one call only: nothing is set on
 * a connection that another exchange shares.
 */
@RequiredArgsConstructor(staticName = "within")
public final class ResponseHeadersDeadline implements Interceptor {

    private final @NonNull Duration timeout;

    @Override
    public Response intercept(@NonNull Chain chain) throws IOException {
        if (timeout.isZero()) {
            return chain.proceed(chain.request());
        }
        var call = chain.call();
        try (var deadline = BodyDeadline.closing(call::cancel, timeout)) {
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
}
