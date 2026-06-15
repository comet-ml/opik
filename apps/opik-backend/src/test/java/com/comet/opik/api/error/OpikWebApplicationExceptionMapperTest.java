package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetrics;
import jakarta.inject.Provider;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpikWebApplicationExceptionMapper Unit Test")
class OpikWebApplicationExceptionMapperTest {

    @Mock
    private ErrorMetrics errorMetrics;
    @Mock
    private Provider<RequestContext> requestContext;
    @Mock
    private Provider<UriInfo> uriInfoProvider;
    @Mock
    private UriInfo uriInfo;

    @Test
    @DisplayName("records server errors and preserves the original response")
    void recordsServerErrors() {
        when(uriInfoProvider.get()).thenReturn(uriInfo);
        var mapper = new OpikWebApplicationExceptionMapper(errorMetrics, requestContext, uriInfoProvider);
        var exception = new InternalServerErrorException("db down");

        var response = mapper.toResponse(exception);

        assertThat(response).isSameAs(exception.getResponse());
        assertThat(response.getStatus()).isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        verify(errorMetrics).record(exception, uriInfo, requestContext);
    }

    @Test
    @DisplayName("does not record client errors but still preserves the response")
    void ignoresClientErrors() {
        var mapper = new OpikWebApplicationExceptionMapper(errorMetrics, requestContext, uriInfoProvider);
        var exception = new NotFoundException();

        var response = mapper.toResponse(exception);

        assertThat(response).isSameAs(exception.getResponse());
        assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_FOUND.getStatusCode());
        verify(errorMetrics, never()).record(any(), any(), any());
        verifyNoInteractions(uriInfoProvider);
    }

    @Test
    @DisplayName("records explicitly built 5xx WebApplicationExceptions")
    void recordsExplicit5xx() {
        when(uriInfoProvider.get()).thenReturn(uriInfo);
        var mapper = new OpikWebApplicationExceptionMapper(errorMetrics, requestContext, uriInfoProvider);
        var exception = new WebApplicationException(Response.status(503).build());

        var response = mapper.toResponse(exception);

        assertThat(response.getStatus()).isEqualTo(503);
        verify(errorMetrics).record(exception, uriInfo, requestContext);
    }
}
