package com.comet.opik.infrastructure;

import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("RequestSizeLimitFilter")
class RequestSizeLimitFilterTest {

    private static final long LIMIT = 50L * 1024 * 1024; // 50MB

    private RequestSizeLimitFilter newFilter() {
        JacksonConfig config = new JacksonConfig();
        config.setMaxRequestSizeBytes(LIMIT);
        return new RequestSizeLimitFilter(config);
    }

    private ContainerRequestContext contextWithContentLength(String value) {
        ContainerRequestContext ctx = mock(ContainerRequestContext.class);
        when(ctx.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn(value);
        return ctx;
    }

    private int abortedStatus(ContainerRequestContext ctx) {
        ArgumentCaptor<Response> captor = ArgumentCaptor.forClass(Response.class);
        verify(ctx).abortWith(captor.capture());
        return captor.getValue().getStatus();
    }

    @Test
    @DisplayName("body over the limit is rejected with 413 before parsing")
    void overLimit_rejected413() {
        ContainerRequestContext ctx = contextWithContentLength(String.valueOf(LIMIT + 1));

        newFilter().filter(ctx);

        assertThat(abortedStatus(ctx)).isEqualTo(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
    }

    @Test
    @DisplayName("body at the limit passes")
    void atLimit_passes() {
        ContainerRequestContext ctx = contextWithContentLength(String.valueOf(LIMIT));

        newFilter().filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("missing Content-Length passes (still bounded later by maxDocumentLength)")
    void noContentLength_passes() {
        ContainerRequestContext ctx = contextWithContentLength(null);

        newFilter().filter(ctx);

        verify(ctx, never()).abortWith(any());
    }

    @Test
    @DisplayName("body larger than Integer.MAX_VALUE (~2GB) is still rejected — header read as long")
    void aboveIntegerMax_rejected413() {
        ContainerRequestContext ctx = contextWithContentLength("3221225472"); // 3GB, overflows int

        newFilter().filter(ctx);

        assertThat(abortedStatus(ctx)).isEqualTo(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
    }

    @Test
    @DisplayName("malformed Content-Length fails closed with 400")
    void malformedContentLength_rejected400() {
        ContainerRequestContext ctx = contextWithContentLength("not-a-number");

        newFilter().filter(ctx);

        assertThat(abortedStatus(ctx)).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    @DisplayName("negative Content-Length fails closed with 400")
    void negativeContentLength_rejected400() {
        ContainerRequestContext ctx = contextWithContentLength("-1");

        newFilter().filter(ctx);

        assertThat(abortedStatus(ctx)).isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }
}
