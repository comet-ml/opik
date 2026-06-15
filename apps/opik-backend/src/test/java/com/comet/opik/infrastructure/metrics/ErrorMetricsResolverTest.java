package com.comet.opik.infrastructure.metrics;

import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.UriInfo;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("ErrorMetricsResolver Unit Test")
class ErrorMetricsResolverTest {

    @Test
    @DisplayName("errorType: unwraps to the root cause simple class name")
    void errorTypeUnwrapsToRootCause() {
        var rootCause = new java.sql.SQLTimeoutException("read timed out");
        var wrapped = new RuntimeException("query failed", new IllegalStateException("boom", rootCause));

        assertThat(ErrorMetricsResolver.errorType(wrapped)).isEqualTo("SQLTimeoutException");
    }

    @Test
    @DisplayName("errorType: uses the throwable itself when there is no cause")
    void errorTypeWithoutCause() {
        assertThat(ErrorMetricsResolver.errorType(new NullPointerException())).isEqualTo("NullPointerException");
    }

    @Test
    @DisplayName("errorType: null throwable resolves to unknown")
    void errorTypeNull() {
        assertThat(ErrorMetricsResolver.errorType(null)).isEqualTo(ErrorMetrics.UNKNOWN);
    }

    @Test
    @DisplayName("endpoint: reconstructs the full matched route template")
    void endpointReconstructsTemplate() {
        var uriInfo = mock(ExtendedUriInfo.class);
        // getMatchedTemplates() is ordered most-specific first
        when(uriInfo.getMatchedTemplates())
                .thenReturn(List.of(new UriTemplate("/{id}"), new UriTemplate("/v1/private/traces")));

        assertThat(ErrorMetricsResolver.endpoint(uriInfo)).isEqualTo("/v1/private/traces/{id}");
    }

    @Test
    @DisplayName("endpoint: empty matched templates resolve to unknown")
    void endpointEmptyTemplates() {
        var uriInfo = mock(ExtendedUriInfo.class);
        when(uriInfo.getMatchedTemplates()).thenReturn(List.of());

        assertThat(ErrorMetricsResolver.endpoint(uriInfo)).isEqualTo(ErrorMetrics.UNKNOWN);
    }

    @Test
    @DisplayName("endpoint: non-extended UriInfo resolves to unknown")
    void endpointNonExtended() {
        assertThat(ErrorMetricsResolver.endpoint(mock(UriInfo.class))).isEqualTo(ErrorMetrics.UNKNOWN);
        assertThat(ErrorMetricsResolver.endpoint(null)).isEqualTo(ErrorMetrics.UNKNOWN);
    }

    @Test
    @DisplayName("workspaceId/workspaceName: read from the request context")
    void workspaceFromContext() {
        var requestContext = RequestContext.builder()
                .workspaceId("ws-123")
                .workspaceName("my-workspace")
                .build();
        Provider<RequestContext> provider = () -> requestContext;

        assertThat(ErrorMetricsResolver.workspaceId(provider)).isEqualTo("ws-123");
        assertThat(ErrorMetricsResolver.workspaceName(provider)).isEqualTo("my-workspace");
    }

    @Test
    @DisplayName("workspaceId/workspaceName: request-scope access failure resolves to unknown")
    void workspaceWhenContextUnavailable() {
        Provider<RequestContext> provider = () -> {
            throw new IllegalStateException("no request scope");
        };

        assertThat(ErrorMetricsResolver.workspaceId(provider)).isEqualTo(ErrorMetrics.UNKNOWN);
        assertThat(ErrorMetricsResolver.workspaceName(provider)).isEqualTo(ErrorMetrics.UNKNOWN);
    }
}
