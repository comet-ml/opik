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

    private static final long LIMIT = 10L * 1024 * 1024;

    private final RequestSizeLimitFilter filter = new RequestSizeLimitFilter(LIMIT);

    private ContainerRequestContext contextWithContentLength(String value) {
        var context = mock(ContainerRequestContext.class);
        when(context.getHeaderString(HttpHeaders.CONTENT_LENGTH)).thenReturn(value);
        return context;
    }

    @Test
    @DisplayName("aborts with 413 when Content-Length exceeds the limit")
    void abortsWhenOverLimit() {
        var context = contextWithContentLength(Long.toString(LIMIT + 1));

        filter.filter(context);

        var response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertThat(response.getValue().getStatus())
                .isEqualTo(Response.Status.REQUEST_ENTITY_TOO_LARGE.getStatusCode());
    }

    @Test
    @DisplayName("aborts with 413 for a body larger than Integer.MAX_VALUE (parsed as long)")
    void abortsWhenOverIntegerMax() {
        var context = contextWithContentLength(Long.toString((long) Integer.MAX_VALUE + 1));

        filter.filter(context);

        verify(context).abortWith(any());
    }

    @Test
    @DisplayName("passes through when Content-Length is within the limit")
    void passesWhenUnderLimit() {
        var context = contextWithContentLength(Long.toString(LIMIT));

        filter.filter(context);

        verify(context, never()).abortWith(any());
    }

    @Test
    @DisplayName("passes through when Content-Length header is absent (chunked)")
    void passesWhenHeaderAbsent() {
        var context = contextWithContentLength(null);

        filter.filter(context);

        verify(context, never()).abortWith(any());
    }

    @Test
    @DisplayName("fails closed with 400 on a malformed Content-Length")
    void failsClosedOnMalformed() {
        var context = contextWithContentLength("not-a-number");

        filter.filter(context);

        var response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertThat(response.getValue().getStatus())
                .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    @DisplayName("fails closed with 400 on a multi-valued Content-Length")
    void failsClosedOnMultiValue() {
        var context = contextWithContentLength("123, 456");

        filter.filter(context);

        var response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertThat(response.getValue().getStatus())
                .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }

    @Test
    @DisplayName("fails closed with 400 on a negative Content-Length")
    void failsClosedOnNegative() {
        var context = contextWithContentLength("-1");

        filter.filter(context);

        var response = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(response.capture());
        assertThat(response.getValue().getStatus())
                .isEqualTo(Response.Status.BAD_REQUEST.getStatusCode());
    }
}
