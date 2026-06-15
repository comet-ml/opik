package com.comet.opik.api.error;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.metrics.ErrorMetrics;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("OpikGenericExceptionMapper Unit Test")
class OpikGenericExceptionMapperTest {

    @Mock
    private ErrorMetrics errorMetrics;
    @Mock
    private Provider<RequestContext> requestContext;
    @Mock
    private Provider<UriInfo> uriInfoProvider;
    @Mock
    private UriInfo uriInfo;

    @Test
    @DisplayName("records the uncaught error and delegates to the 500 rendering")
    void recordsAndReturns500() {
        when(uriInfoProvider.get()).thenReturn(uriInfo);
        var mapper = new OpikGenericExceptionMapper(errorMetrics, requestContext, uriInfoProvider);
        var exception = new IllegalStateException("unexpected");

        var response = mapper.toResponse(exception);

        assertThat(response.getStatus()).isEqualTo(Response.Status.INTERNAL_SERVER_ERROR.getStatusCode());
        verify(errorMetrics).record(exception, uriInfo, requestContext);
    }
}
