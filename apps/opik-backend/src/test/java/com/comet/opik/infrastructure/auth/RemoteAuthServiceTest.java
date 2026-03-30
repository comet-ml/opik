package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.UriInfo;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import uk.co.jemos.podam.api.PodamFactory;

import java.net.URI;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_QUERY_PARAM;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteAuthServiceTest {

    private static final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private RemoteAuthService remoteAuthService;
    private RequestContext requestContext;

    @BeforeAll
    void setUpAll() {
        WIRE_MOCK.server().start();
        var client = TestHttpClientUtils.client();
        remoteAuthService = new RemoteAuthService(client,
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                new NoopCacheService());
    }

    @AfterAll
    void tearDownAll() {
        WIRE_MOCK.server().stop();
    }

    @BeforeEach
    void beforeEach() {
        requestContext = new RequestContext();
        WIRE_MOCK.server().resetAll();
    }

    static Stream<Arguments> successfulAuthArgs() {
        return Stream.of(
                arguments(true, null),
                arguments(false, "VERSION_1"),
                arguments(false, "version_2"),
                arguments(false, "version_unknown"));
    }

    @ParameterizedTest
    @MethodSource("successfulAuthArgs")
    void testAuthSuccessful(boolean workspaceViaHeader, String opikVersionStr)
            throws JsonProcessingException {
        var opikVersion = OpikVersion.fromValue(opikVersionStr);
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class).toBuilder()
                .opikVersion(opikVersion)
                .build();
        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();

        // Serialize via Map to inject the raw opikVersionStr (e.g. "VERSION_1", "version_unknown")
        // directly into JSON, bypassing @JsonValue which would normalize the casing
        Map<String, Object> responseMap = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(authResponse), Map.class);
        responseMap.put("opikVersion", opikVersionStr);
        var responseJson = OBJECT_MAPPER.writeValueAsString(responseMap);
        WIRE_MOCK.server().stubFor(post("/opik/auth").willReturn(okJson(responseJson)));

        remoteAuthService.authenticate(
                getHeadersMock(workspaceViaHeader ? workspaceName : null, apiKey), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo(workspaceViaHeader
                                ? "/priv/something"
                                : "/priv/something?%s=%s".formatted(WORKSPACE_QUERY_PARAM, workspaceName)))
                        .method("GET")
                        .build());

        var expectedRequestContext = RequestContext.builder()
                .userName(authResponse.user())
                .workspaceId(authResponse.workspaceId())
                .workspaceName(authResponse.workspaceName())
                .apiKey(apiKey)
                .opikVersion(opikVersion)
                .quotas(authResponse.quotas())
                .build();
        assertThat(requestContext).isEqualTo(expectedRequestContext);
    }

    @Test
    void testAuthCacheHit_preservesAllCredentials() throws JsonProcessingException {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var mockCache = mock(CacheService.class);
        when(mockCache.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, null))
                .thenReturn(Optional.empty());
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(authResponse))));

        var cachingService = new RemoteAuthService(TestHttpClientUtils.client(),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                mockCache);
        var contextInfo = ContextInfoHolder.builder()
                .uriInfo(createMockUriInfo("/priv/something"))
                .method("GET")
                .build();

        // First call: cache miss → EM → from(AuthResponse) → toAuthCredentials() → cache write
        cachingService.authenticate(getHeadersMock(workspaceName, apiKey), null, contextInfo);
        // Copying status of context after authenticate with cache miss
        var contextAfterCacheMiss = requestContext.toBuilder().build();

        var credentialsCaptor = ArgumentCaptor.forClass(CacheService.AuthCredentials.class);
        verify(mockCache).cache(eq(apiKey), eq(workspaceName), isNull(), credentialsCaptor.capture());

        // Second call: cache hit → from(AuthCredentials) → setCredentialIntoContext
        requestContext = new RequestContext();
        when(mockCache.resolveApiKeyUserAndWorkspaceIdFromCache(apiKey, workspaceName, null))
                .thenReturn(Optional.of(credentialsCaptor.getValue()));

        cachingService.authenticate(getHeadersMock(workspaceName, apiKey), null, contextInfo);

        assertThat(requestContext).isEqualTo(contextAfterCacheMiss);
    }

    static Stream<Arguments> unauthorizedArgs() {
        return Stream.of(
                arguments(HttpStatus.SC_UNAUTHORIZED,
                        ClientErrorException.class,
                        "test error message"),
                arguments(HttpStatus.SC_FORBIDDEN,
                        ClientErrorException.class,
                        NOT_ALLOWED_TO_ACCESS_WORKSPACE),
                arguments(HttpStatus.SC_SERVER_ERROR,
                        InternalServerErrorException.class,
                        "HTTP 500 Internal Server Error"));
    }

    @ParameterizedTest
    @MethodSource("unauthorizedArgs")
    void testUnauthorized(
            int remoteAuthStatusCode, Class<? extends Exception> expectedExceptionClass, String expectedMessage) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var apiKey = "apiKey-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withStatus(remoteAuthStatusCode)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody(JsonUtils.readTree(
                                new ReactServiceErrorResponse("test error message",
                                        remoteAuthStatusCode)))));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, apiKey), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(expectedExceptionClass)
                .hasMessage(expectedMessage);
    }

    @Test
    void testAuthNoWorkspace() {
        var apiKey = "apiKey-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/auth").willReturn(ok()));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock("", apiKey), null, ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(MISSING_WORKSPACE);
    }

    @Test
    void testAuthNoApiKey() {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        WIRE_MOCK.server().stubFor(post("/auth").willReturn(ok()));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, ""), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .requiredPermissions(null)
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(MISSING_API_KEY);
    }

    @ParameterizedTest
    @MethodSource("successfulAuthArgs")
    void testSessionAuthSuccessful(boolean workspaceViaHeader, String opikVersionStr)
            throws JsonProcessingException {
        var opikVersion = OpikVersion.fromValue(opikVersionStr);
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class).toBuilder()
                .opikVersion(opikVersion)
                .build();
        var sessionTokenValue = "session-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();

        // Serialize via Map to inject raw opikVersionStr, bypassing @JsonValue normalization
        Map<String, Object> responseMap = OBJECT_MAPPER.readValue(
                OBJECT_MAPPER.writeValueAsString(authResponse), Map.class);
        responseMap.put("opikVersion", opikVersionStr);
        var responseJson = OBJECT_MAPPER.writeValueAsString(responseMap);
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .withCookie(RequestContext.SESSION_COOKIE, equalTo(sessionTokenValue))
                .willReturn(okJson(responseJson)));

        remoteAuthService.authenticate(
                getHeadersMock(workspaceViaHeader ? workspaceName : null, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo(workspaceViaHeader
                                ? "/priv/something"
                                : "/priv/something?%s=%s".formatted(WORKSPACE_QUERY_PARAM, workspaceName)))
                        .method("GET")
                        .build());

        var expectedRequestContext = RequestContext.builder()
                .userName(authResponse.user())
                .workspaceId(authResponse.workspaceId())
                .workspaceName(authResponse.workspaceName())
                .apiKey(sessionTokenValue)
                .opikVersion(opikVersion)
                .quotas(authResponse.quotas())
                .build();
        assertThat(requestContext).isEqualTo(expectedRequestContext);
    }

    @Test
    void testSessionAuth__whenDefaultWorkspace__thenForbidden() {
        var sessionTokenValue = "session-" + UUID.randomUUID();

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(DEFAULT_WORKSPACE_NAME, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(NOT_ALLOWED_TO_ACCESS_WORKSPACE);
    }

    @Test
    void testSessionAuthNoWorkspace() {
        var sessionTokenValue = "session-" + UUID.randomUUID();

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock("", ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(MISSING_WORKSPACE);
    }

    @ParameterizedTest
    @MethodSource("unauthorizedArgs")
    void testSessionAuthUnauthorized(int remoteAuthStatusCode, Class<? extends Exception> expectedExceptionClass,
            String expectedMessage) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var sessionTokenValue = "session-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .willReturn(aResponse().withStatus(remoteAuthStatusCode)
                        .withHeader("Content-Type", "application/json")
                        .withJsonBody(JsonUtils.readTree(
                                new ReactServiceErrorResponse("test error message",
                                        remoteAuthStatusCode)))));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(expectedExceptionClass)
                .hasMessage(expectedMessage);
    }

    private static Cookie sessionCookie(String value) {
        return new Cookie.Builder(RequestContext.SESSION_COOKIE).value(value).build();
    }

    private HttpHeaders getHeadersMock(String workspaceName, String apiKey) {
        var headersMock = mock(HttpHeaders.class);
        when(headersMock.getHeaderString(RequestContext.WORKSPACE_HEADER)).thenReturn(workspaceName);
        when(headersMock.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(apiKey);
        return headersMock;
    }

    private UriInfo createMockUriInfo(String stringUri) {
        UriInfo uriInfo = mock(UriInfo.class);
        URI uri = URI.create(stringUri);
        when(uriInfo.getRequestUri()).thenReturn(uri);
        when(uriInfo.getQueryParameters()).thenReturn(getQueryParams(uri));
        return uriInfo;
    }

    private MultivaluedMap<String, String> getQueryParams(URI uri) {
        String query = uri.getQuery(); // Extract query string

        MultivaluedMap<String, String> paramMap = new MultivaluedHashMap<>();

        if (query != null) {
            Arrays.stream(query.split("&"))
                    .map(param -> param.split("="))
                    .forEach(pair -> paramMap.add(pair[0], pair[1]));
        }

        return paramMap;
    }
}
