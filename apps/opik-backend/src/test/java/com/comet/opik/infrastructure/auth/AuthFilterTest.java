package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.UriInfo;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFilter branch selection")
class AuthFilterTest {

    @Mock
    private AuthService authService;

    @Mock
    private McpOAuthService mcpOAuthService;

    @Mock
    private CipxTokenValidationService cipxTokenValidationService;

    private final RequestContext requestContext = new RequestContext();

    private AuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new AuthFilter(authService, mcpOAuthService, cipxTokenValidationService, new OpikConfiguration(),
                () -> requestContext);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/private/projects",
            "/v1/private/projects|/{id}",
            "/v1/internal/analytics-queries",
            "/v1/internal/analytics-queries|/projects/{projectId}"})
    @DisplayName("authenticates when the matched templates are private or the analytics query executor")
    void authenticatesPrivateResources(String templates) throws IOException {
        filter.filter(requestContext(matched(templates), "/v1/private/projects"));

        verify(authService).authenticate(any(), any(), any());
        verify(authService, never()).authenticateSession(any());
    }

    @Test
    @DisplayName("authenticates when the request carries no matched templates")
    void authenticatesWhenNothingMatched() throws IOException {
        filter.filter(requestContext(matched(""), "/v1/private/projects"));

        verify(authService).authenticate(any(), any(), any());
    }

    @Test
    @DisplayName("authenticates when the uri info is not Jersey's extended one")
    void authenticatesWithPlainUriInfo() throws IOException {
        UriInfo uriInfo = mock(UriInfo.class);
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080/is-alive/ping"));

        filter.filter(requestContext(uriInfo));

        verify(authService).authenticate(any(), any(), any());
    }

    @Test
    @DisplayName("decides on the matched templates rather than on the request URI")
    void decidesOnTemplatesNotRequestUri() throws IOException {
        filter.filter(requestContext(matched("/v1/private/projects"), "/v1;version=1/private;scope=all/projects"));

        verify(authService).authenticate(any(), any(), any());
    }

    @Test
    @DisplayName("checks the session cookie only for session resources")
    void authenticatesSessionResources() throws IOException {
        filter.filter(requestContext(matched("/v1/session/redirect"), "/v1/session/redirect"));

        verify(authService).authenticateSession(any());
        verify(authService, never()).authenticate(any(), any(), any());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/v1/internal/usage|/workspace-trace-counts",
            "/v1/internal/agent-insights/enrollment",
            "/is-alive|/ping"})
    @DisplayName("leaves the other resources unauthenticated")
    void skipsAuthenticationForOtherResources(String templates) throws IOException {
        filter.filter(requestContext(matched(templates), "/v1/internal/usage/workspace-trace-counts"));

        verifyNoInteractions(authService, mcpOAuthService, cipxTokenValidationService);
    }

    private static ExtendedUriInfo matched(String templates) {
        List<UriTemplate> matchedTemplates = templates.isEmpty()
                ? List.of()
                : Arrays.stream(templates.split("\\|")).map(UriTemplate::new).toList().reversed();
        ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
        when(uriInfo.getMatchedTemplates()).thenReturn(matchedTemplates);
        return uriInfo;
    }

    private ContainerRequestContext requestContext(ExtendedUriInfo uriInfo, String requestPath) {
        lenient().when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080" + requestPath));
        return requestContext(uriInfo);
    }

    private ContainerRequestContext requestContext(UriInfo uriInfo) {
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        lenient().when(context.getCookies()).thenReturn(Map.of());
        lenient().when(context.getUriInfo()).thenReturn(uriInfo);
        lenient().when(context.getMethod()).thenReturn("GET");
        lenient().when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
        return context;
    }
}
