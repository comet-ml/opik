package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.mcpoauth.McpOAuthService;
import com.comet.opik.infrastructure.CipxTokenValidationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.lang3.RandomStringUtils;
import org.glassfish.jersey.server.ExtendedUriInfo;
import org.glassfish.jersey.uri.PathTemplate;
import org.glassfish.jersey.uri.UriTemplate;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("AuthFilter")
class AuthFilterTest {

    private static List<UriTemplate> templates(String... templatesInMatchingOrder) {
        return Stream.of(templatesInMatchingOrder)
                .map(template -> (UriTemplate) new PathTemplate(template))
                .toList()
                .reversed();
    }

    @Nested
    @DisplayName("branch selection")
    class BranchSelection {

        @Mock
        private AuthService authService;

        @Mock
        private McpOAuthService mcpOAuthService;

        @Mock
        private CipxTokenValidationService cipxTokenValidationService;

        private final RequestContext requestContext = new RequestContext();
        private final MultivaluedMap<String, String> requestHeaders = new MultivaluedHashMap<>();

        private AuthFilter filter;

        @BeforeEach
        void setUp() {
            filter = new AuthFilter(authService, mcpOAuthService, cipxTokenValidationService,
                    new OpikConfiguration(), () -> requestContext);
        }

        static Stream<Arguments> authenticatedTemplates() {
            return Stream.of(
                    arguments(templates("/v1/private/projects")),
                    arguments(templates("/v1/private/projects", "/{id}")),
                    arguments(templates("/v1/internal/analytics-queries")),
                    arguments(templates("/v1/internal/analytics-queries", "/projects/{projectId}")),
                    arguments(List.of()));
        }

        @ParameterizedTest
        @MethodSource("authenticatedTemplates")
        @DisplayName("authenticates private resources, the analytics query executor, and anything unmatched")
        void authenticates(List<UriTemplate> matchedTemplates) throws IOException {
            var uriInfo = uriInfo(matchedTemplates);
            var context = authenticatedRequest(uriInfo);

            filter.filter(context);

            assertAuthenticated(uriInfo);
            verify(uriInfo).getMatchedTemplates();
            verifyNoMoreInteractions(uriInfo);
        }

        @Test
        @DisplayName("authenticates when the uri info carries no matching information")
        void authenticatesWithPlainUriInfo() throws IOException {
            var uriInfo = mock(UriInfo.class);
            var context = authenticatedRequest(uriInfo);

            filter.filter(context);

            assertAuthenticated(uriInfo);
            verifyNoInteractions(uriInfo);
        }

        @Test
        @DisplayName("checks the session cookie only for session resources")
        void authenticatesSessionResources() throws IOException {
            var uriInfo = uriInfo(templates("/v1/session/redirect"));
            var context = request(uriInfo);

            filter.filter(context);

            verify(authService).authenticateSession(isNull());
            verifyNoMoreInteractions(authService);
            verifyNoInteractions(mcpOAuthService, cipxTokenValidationService);
            verify(uriInfo).getMatchedTemplates();
            verifyNoMoreInteractions(uriInfo);
            assertThat(requestContext.getHeaders()).isSameAs(requestHeaders);
        }

        static Stream<Arguments> unauthenticatedTemplates() {
            return Stream.of(
                    arguments(templates("/v1/internal/usage", "/workspace-trace-counts")),
                    arguments(templates("/v1/internal/agent-insights/enrollment")),
                    arguments(templates("/is-alive", "/ping")));
        }

        @ParameterizedTest
        @MethodSource("unauthenticatedTemplates")
        @DisplayName("leaves the other resources unauthenticated")
        void skipsAuthentication(List<UriTemplate> matchedTemplates) throws IOException {
            var uriInfo = uriInfo(matchedTemplates);
            var context = request(uriInfo);

            filter.filter(context);

            verifyNoInteractions(authService, mcpOAuthService, cipxTokenValidationService);
            verify(uriInfo).getMatchedTemplates();
            verifyNoMoreInteractions(uriInfo);
            assertThat(requestContext.getHeaders()).isSameAs(requestHeaders);
        }

        private void assertAuthenticated(UriInfo uriInfo) {
            var expectedContextInfo = ContextInfoHolder.builder()
                    .uriInfo(uriInfo)
                    .method("GET")
                    .requiredPermissions(List.of())
                    .build();
            var headers = ArgumentCaptor.forClass(HttpHeaders.class);
            verify(authService).authenticate(headers.capture(), isNull(), eq(
                    expectedContextInfo));
            assertThat(headers.getValue().getRequestHeaders()).isSameAs(requestHeaders);
            verifyNoMoreInteractions(authService);
            verifyNoInteractions(mcpOAuthService, cipxTokenValidationService);
            assertThat(requestContext.getHeaders()).isSameAs(requestHeaders);
        }

        private static ExtendedUriInfo uriInfo(List<UriTemplate> matchedTemplates) {
            var uriInfo = mock(ExtendedUriInfo.class);
            when(uriInfo.getMatchedTemplates()).thenReturn(matchedTemplates);
            return uriInfo;
        }

        private ContainerRequestContext request(UriInfo uriInfo) {
            var context = mock(ContainerRequestContext.class);
            when(context.getCookies()).thenReturn(Map.of());
            when(context.getUriInfo()).thenReturn(uriInfo);
            when(context.getHeaders()).thenReturn(requestHeaders);
            return context;
        }

        private ContainerRequestContext authenticatedRequest(UriInfo uriInfo) {
            var context = request(uriInfo);
            when(context.getMethod()).thenReturn("GET");
            return context;
        }
    }

    /**
     * The CIPX branch of the filter, driven end to end with the real {@link CipxTokenValidationService}, to pin the
     * one property that is invisible from inside the service: the branch never reads {@code Comet-Workspace}, so a
     * device token authenticates without it and every header variant shares one cache entry. The API-key and
     * MCP OAuth branches still require the header and are not exercised here.
     */
    @Nested
    @DisplayName("CIPX device token branch")
    class CipxToken {

        // The prefix stays literal: it is what triggers the branch. Only the secret part is data.
        private static final String TOKEN = CipxTokenUtils.ACCESS_PREFIX + RandomStringUtils.secure()
                .nextAlphanumeric(32);
        private static final String TOKEN_CACHE_KEY = "cipx-sha256:" + DigestUtils.sha256Hex(TOKEN);
        private static final String INGEST_PATH = "/v1/private/traces";
        private static final String WORKSPACE_ID = UUID.randomUUID().toString();
        // The __ai_spend_ fence is the contract shape of a device's bound workspace; only the org part is data.
        private static final String BOUND_WORKSPACE = "__ai_spend_" + RandomStringUtils.secure().nextAlphanumeric(8)
                + "__";
        private static final String DEVICE_ID = UUID.randomUUID().toString();
        // What the validator really returns as the user name: the device's MDM-provisioned address, not a
        // Comet username. The cipx-device-<id> form is a fallback cost-api owns and tests.
        private static final String MDM_EMAIL = "dev-" + UUID.randomUUID() + "@acme.com";

        @Mock
        private AuthService authService;

        @Mock
        private McpOAuthService mcpOAuthService;

        @Mock
        private Client client;

        @Mock
        private CacheService cacheService;

        private final RequestContext requestContext = new RequestContext();

        private AuthFilter filter;

        @BeforeEach
        void setUp() {
            var config = new OpikConfiguration();
            config.setCipxTokenValidation(CipxTokenValidationConfig.builder()
                    .enabled(true)
                    .url("http://ai-cost-backend")
                    .build());
            var cipxTokenValidationService = new CipxTokenValidationService(client, config, cacheService,
                    () -> requestContext);
            filter = new AuthFilter(authService, mcpOAuthService, cipxTokenValidationService, config,
                    () -> requestContext);
        }

        @Test
        @DisplayName("authenticates a device token with no Comet-Workspace header at all")
        void authenticatesWithoutTheWorkspaceHeader() throws IOException {
            cacheHit();
            var context = requestContext(null);

            filter.filter(context);

            // The workspace comes from the token's enrollment binding, not from anything the client sent.
            assertThat(requestContext.getWorkspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(requestContext.getWorkspaceName()).isEqualTo(BOUND_WORKSPACE);
            assertThat(requestContext.getCipxDeviceId()).isEqualTo(DEVICE_ID);
            verify(context, never()).getHeaderString(RequestContext.WORKSPACE_HEADER);
            // Neither the API-key nor the MCP OAuth branch runs for this credential.
            verifyNoInteractions(authService, mcpOAuthService);
        }

        @Test
        @DisplayName("two requests with different workspace headers share one cache entry")
        void differentWorkspaceHeadersShareOneCacheEntry() throws IOException {
            cacheHit();

            filter.filter(requestContext("__ai_spend_acme__"));
            filter.filter(requestContext("something-else-entirely"));

            // Same key both times, so a client varying the header cannot force a cold validate per variant.
            verify(cacheService, times(2)).resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN_CACHE_KEY, "",
                    List.of());
            verify(cacheService, never()).cache(any(), any(), any(), any());
        }

        private void cacheHit() {
            when(cacheService.resolveApiKeyUserAndWorkspaceIdFromCache(TOKEN_CACHE_KEY, "", List.of()))
                    .thenReturn(Optional.of(CacheService.AuthCredentials.builder()
                            .userName(MDM_EMAIL)
                            .workspaceId(WORKSPACE_ID)
                            .workspaceName(BOUND_WORKSPACE)
                            .quotas(List.of())
                            .permissions(List.of())
                            .deviceId(DEVICE_ID)
                            .build()));
        }

        /**
         * @param workspaceHeader stubbed leniently on purpose: it is present on the request and must never be read.
         */
        private ContainerRequestContext requestContext(String workspaceHeader) {
            ExtendedUriInfo uriInfo = mock(ExtendedUriInfo.class);
            when(uriInfo.getRequestUri()).thenReturn(URI.create("http://localhost:8080" + INGEST_PATH));
            when(uriInfo.getMatchedTemplates()).thenReturn(templates(INGEST_PATH));

            ContainerRequestContext context = mock(ContainerRequestContext.class);
            when(context.getCookies()).thenReturn(Map.of());
            when(context.getUriInfo()).thenReturn(uriInfo);
            when(context.getMethod()).thenReturn("POST");
            when(context.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(TOKEN);
            when(context.getHeaders()).thenReturn(new MultivaluedHashMap<>());
            lenient().when(context.getHeaderString(RequestContext.WORKSPACE_HEADER)).thenReturn(workspaceHeader);
            return context;
        }
    }
}
