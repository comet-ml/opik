package com.comet.opik.infrastructure.http;

import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.URI;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("MatrixParameterRequestFilter")
class MatrixParameterRequestFilterTest {

    private final MatrixParameterRequestFilter filter = new MatrixParameterRequestFilter();

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1;version=1/private/projects",
            "/v1/private;scope=all/projects",
            "/v1/private/projects;page=2",
            "/v1/private/projects;",
            "/v1/internal/usage/workspace-trace-counts;format=json"})
    @DisplayName("rejects a path carrying matrix parameters on any segment as not found")
    void rejectsMatrixParameters(String rawPath) {
        assertThatThrownBy(() -> filter.filter(requestContext(rawPath)))
                .isInstanceOf(NotFoundException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/private/projects",
            "/v1/private/projects/",
            "/v1/private/projects?name=a;b",
            "/v1/private/prompts/name%3Bwith%3Bencoded",
            "/is-alive/ping"})
    @DisplayName("passes a path without matrix parameters through")
    void passesPlainPaths(String rawPathAndQuery) {
        assertThatCode(() -> filter.filter(requestContext(rawPathAndQuery))).doesNotThrowAnyException();
    }

    private ContainerRequestContext requestContext(String rawPathAndQuery) {
        UriInfo uriInfo = mock(UriInfo.class);
        when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080" + rawPathAndQuery));
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(context.getUriInfo()).thenReturn(uriInfo);
        return context;
    }
}
