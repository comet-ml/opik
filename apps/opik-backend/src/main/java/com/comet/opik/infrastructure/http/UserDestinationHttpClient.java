package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.net.DestinationGuardDnsResolver;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.concurrent.Cancellable;
import org.apache.hc.core5.http.ClassicHttpRequest;
import org.apache.hc.core5.http.HttpHost;
import org.apache.hc.core5.http.io.entity.HttpEntityWrapper;
import org.apache.hc.core5.http.protocol.HttpContext;
import org.apache.hc.core5.io.CloseMode;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Closing a response stream reads the rest of the body so the connection can be reused. A user destination
 * controls how long that takes, so its connection is cancelled instead; one already read to the end is unaffected.
 */
@RequiredArgsConstructor
class UserDestinationHttpClient extends CloseableHttpClient {

    private final @NonNull CloseableHttpClient delegate;
    private final @NonNull DestinationGuardDnsResolver resolver;

    @Override
    protected CloseableHttpResponse doExecute(HttpHost target, ClassicHttpRequest request, HttpContext context)
            throws IOException {
        var response = delegate.execute(target, request, context);
        if (resolver.inScope() && response.getEntity() != null && request instanceof Cancellable cancellable) {
            response.setEntity(new HttpEntityWrapper(response.getEntity()) {
                @Override
                public InputStream getContent() throws IOException {
                    return new FilterInputStream(super.getContent()) {
                        @Override
                        public void close() {
                            cancellable.cancel();
                            try {
                                super.close();
                            } catch (IOException ignored) {
                                // the connection was just cancelled
                            }
                        }
                    };
                }
            });
        }
        return response;
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public void close(CloseMode closeMode) {
        delegate.close(closeMode);
    }
}
