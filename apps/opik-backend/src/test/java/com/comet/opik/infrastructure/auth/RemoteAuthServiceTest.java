package com.comet.opik.infrastructure.auth;

import com.codahale.metrics.MetricRegistry;
import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.WorkspaceUserPermissions;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.RemoteWorkspacePermissionsService;
import com.comet.opik.domain.mcpoauth.ValidatedToken;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.http.HttpModule;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.comet.opik.utils.RetryUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.dropwizard.client.JerseyClientBuilder;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ProcessingException;
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

import java.net.SocketTimeoutException;
import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Stream;

import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_API_KEY;
import static com.comet.opik.api.ReactServiceErrorResponse.MISSING_WORKSPACE;
import static com.comet.opik.api.ReactServiceErrorResponse.NOT_ALLOWED_TO_ACCESS_WORKSPACE;
import static com.comet.opik.domain.ProjectService.DEFAULT_WORKSPACE_NAME;
import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_QUERY_PARAM;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.ok;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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

    // Auth-call timeout/retry knobs (see AuthenticationConfig). Retries are disabled for the
    // shared service so the assertions below observe exactly one request per call; the retry and
    // timeout behaviour itself is covered by the dedicated tests near the bottom of this class,
    // which build their own service with retries enabled.
    private static final Duration TEST_AUTH_TIMEOUT = Duration.ofSeconds(3);
    private static final int TEST_AUTH_MAX_RETRIES = 0;
    private static final Duration TEST_AUTH_MIN_BACKOFF = Duration.ofMillis(250);
    private static final Duration TEST_AUTH_MAX_BACKOFF = Duration.ofSeconds(1);

    private static final WireMockUtils.WireMockRuntime WIRE_MOCK = WireMockUtils.startWireMock();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String NOT_LOGGED_USER = "Please login first";
    private static final String DROPWIZARD_UNAUTHORIZED_BODY = "Credentials are required to access this resource.";
    private static final String REMOTE_ERROR_MESSAGE = "remote error message";

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    private RemoteAuthService remoteAuthService;
    private RequestContext requestContext;

    @BeforeAll
    void setUpAll() {
        WIRE_MOCK.server().start();
        var client = TestHttpClientUtils.client();
        remoteAuthService = new RemoteAuthService(client, new RetriableHttpClient(client),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                new NoopCacheService(),
                TEST_AUTH_TIMEOUT, TEST_AUTH_MAX_RETRIES, TEST_AUTH_MIN_BACKOFF, TEST_AUTH_MAX_BACKOFF,
                new RemoteWorkspacePermissionsService(client,
                        new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url(""))),
                false);
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
        return Stream.of(arguments(true), arguments(false));
    }

    @ParameterizedTest
    @MethodSource("successfulAuthArgs")
    void testAuthSuccessful(boolean workspaceViaHeader) throws JsonProcessingException {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();

        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(authResponse))));

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
                .quotas(authResponse.quotas())
                .build();
        assertThat(requestContext).isEqualTo(expectedRequestContext);
    }

    /**
     * EM (or an older EM mid rolling-upgrade) may return fields the backend no longer models — e.g.
     * the removed {@code opik_version}. {@code AuthResponse} is
     * {@code @JsonIgnoreProperties(ignoreUnknown = true)}, so unknown fields must be ignored rather
     * than failing every authenticated request.
     */
    @Test
    void testAuth__whenResponseHasUnknownFields__thenIgnoredAndAuthSucceeds() throws JsonProcessingException {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();

        var responseBody = OBJECT_MAPPER.<ObjectNode>valueToTree(authResponse);
        responseBody.put("opik_version", "version_1");
        responseBody.put("some_unmodeled_future_field", "ignored");
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(responseBody))));

        remoteAuthService.authenticate(
                getHeadersMock(workspaceName, apiKey), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build());

        var expectedRequestContext = RequestContext.builder()
                .userName(authResponse.user())
                .workspaceId(authResponse.workspaceId())
                .workspaceName(authResponse.workspaceName())
                .apiKey(apiKey)
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
                new RetriableHttpClient(TestHttpClientUtils.client()),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                mockCache,
                TEST_AUTH_TIMEOUT, TEST_AUTH_MAX_RETRIES, TEST_AUTH_MIN_BACKOFF, TEST_AUTH_MAX_BACKOFF,
                new RemoteWorkspacePermissionsService(TestHttpClientUtils.client(),
                        new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url(""))),
                false);
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
                        "Unexpected error while authenticating user"));
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
    void testAuth__whenResponseIsGzipCompressed__thenIdentityEncodingAvoidsClientDoubleDecompression()
            throws Exception {
        // Long user name pushes the response body over the server-side gzip minimum entity size,
        // like production auth responses for users with large quota/workspace payloads
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class).toBuilder()
                .user("user-" + "x".repeat(2000))
                .build();
        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();
        var responseJson = OBJECT_MAPPER.writeValueAsString(authResponse);

        // Reproduces the client-side double-decompression defect: with the production client settings
        // (gzipEnabled: true, unlike the shared test client which disables it), Dropwizard wires BOTH
        // Apache HttpClient's automatic content decompression AND Jersey's GZipDecoder. When the remote
        // (correctly) gzips a large enough response — WireMock auto-gzips here, exactly like a
        // Dropwizard/Jetty upstream — Apache decompresses the body but the 'Content-Encoding: gzip'
        // header survives into Jersey, so GZipDecoder gunzips the already-plain stream and readEntity
        // throws 'ZipException: Not in GZIP format', which escapes the auth filter as a
        // ProcessingException and surfaces as a 500. Requesting identity encoding keeps the response
        // uncompressed, so neither layer engages.
        WIRE_MOCK.server().stubFor(post("/opik/auth").willReturn(okJson(responseJson)));

        var gzipEnabledAuthService = new RemoteAuthService(newGzipEnabledClient(),
                new RetriableHttpClient(newGzipEnabledClient()),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                new NoopCacheService(),
                TEST_AUTH_TIMEOUT, TEST_AUTH_MAX_RETRIES, TEST_AUTH_MIN_BACKOFF, TEST_AUTH_MAX_BACKOFF,
                new RemoteWorkspacePermissionsService(TestHttpClientUtils.client(),
                        new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url(""))),
                false);

        gzipEnabledAuthService.authenticate(
                getHeadersMock(workspaceName, apiKey), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build());

        assertThat(requestContext.getUserName()).isEqualTo(authResponse.user());
        assertThat(requestContext.getWorkspaceId()).isEqualTo(authResponse.workspaceId());
        assertThat(requestContext.getWorkspaceName()).isEqualTo(authResponse.workspaceName());
    }

    /**
     * Builds a client with the production gzip settings ({@code gzipEnabled: true}, registering
     * {@code GZipDecoder}). The shared {@link TestHttpClientUtils#client()} runs with
     * {@code gzipEnabled: false} (see config-test.yml), which silently skips the response-decoding
     * path under test here.
     */
    private jakarta.ws.rs.client.Client newGzipEnabledClient() throws Exception {
        var jerseyConfig = TestConfigUtils.loadConfigTest().getJerseyClient();
        jerseyConfig.setGzipEnabled(true);
        var threadFactory = new ThreadFactory() {
            private final AtomicLong counter = new AtomicLong();

            @Override
            public Thread newThread(Runnable runnable) {
                var thread = new Thread(runnable, "gzip-test-client-" + counter.incrementAndGet());
                thread.setDaemon(true);
                return thread;
            }
        };
        var executor = new ThreadPoolExecutor(2, 8, 60L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(64), threadFactory);
        return HttpModule.buildClient(
                new JerseyClientBuilder(new MetricRegistry()).using(executor),
                jerseyConfig);
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
    void testSessionAuthSuccessful(boolean workspaceViaHeader) throws JsonProcessingException {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        var sessionTokenValue = "session-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();

        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .withCookie(RequestContext.SESSION_COOKIE, equalTo(sessionTokenValue))
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(authResponse))));

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

    static Stream<Arguments> nonJsonErrorBodyArgs() {
        return Stream.of(
                // Dropwizard's default UnauthorizedHandler, used by the @Auth filter guarding /opik/auth-session
                arguments(HttpStatus.SC_UNAUTHORIZED, "text/plain", DROPWIZARD_UNAUTHORIZED_BODY, NOT_LOGGED_USER),
                arguments(HttpStatus.SC_UNAUTHORIZED, "text/html", "<html><body>401</body></html>", NOT_LOGGED_USER),
                arguments(HttpStatus.SC_UNAUTHORIZED, "text/plain", "", NOT_LOGGED_USER),
                // a proxy error page far larger than the logged-body cap must still resolve to a client error
                arguments(HttpStatus.SC_UNAUTHORIZED, "text/html", "x".repeat(5_000), NOT_LOGGED_USER),
                arguments(HttpStatus.SC_BAD_REQUEST, "text/plain", "Bad Request", MISSING_WORKSPACE));
    }

    @ParameterizedTest
    @MethodSource("nonJsonErrorBodyArgs")
    void sessionAuth__whenRemoteErrorBodyIsNotJson__thenClientErrorInsteadOfServerError(
            int remoteAuthStatusCode, String contentType, String body, String expectedMessage) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var sessionTokenValue = "session-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .willReturn(aResponse().withStatus(remoteAuthStatusCode)
                        .withHeader("Content-Type", contentType)
                        .withBody(body)));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(expectedMessage)
                .satisfies(throwable -> assertThat(((ClientErrorException) throwable).getResponse().getStatus())
                        .isEqualTo(remoteAuthStatusCode));
    }

    @ParameterizedTest
    @MethodSource("nonJsonErrorBodyArgs")
    void auth__whenRemoteErrorBodyIsNotJson__thenClientErrorInsteadOfServerError(
            int remoteAuthStatusCode, String contentType, String body, String expectedMessage) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var apiKey = "apiKey-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withStatus(remoteAuthStatusCode)
                        .withHeader("Content-Type", contentType)
                        .withBody(body)));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, apiKey), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(expectedMessage)
                .satisfies(throwable -> assertThat(((ClientErrorException) throwable).getResponse().getStatus())
                        .isEqualTo(remoteAuthStatusCode));
    }

    /**
     * The content type claims JSON but the body is not parseable, so the JSON branch is entered and
     * {@code readEntity} fails. Pins the client-facing outcome of that recovery path: a client error carrying the
     * fallback message, never a server error. The path also re-reads the raw body for diagnostics, which is what the
     * up-front buffering enables; the logged body itself is not asserted here.
     */
    @ParameterizedTest
    @MethodSource("malformedJsonErrorBodyArgs")
    void sessionAuth__whenRemoteErrorBodyIsMalformedJson__thenClientErrorAndBodyReReadForDiagnostics(
            int remoteAuthStatusCode, String body, String expectedMessage) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var sessionTokenValue = "session-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .willReturn(aResponse().withStatus(remoteAuthStatusCode)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(expectedMessage)
                .satisfies(throwable -> assertThat(((ClientErrorException) throwable).getResponse().getStatus())
                        .isEqualTo(remoteAuthStatusCode));
    }

    static Stream<Arguments> malformedJsonErrorBodyArgs() {
        return Stream.of(
                arguments(HttpStatus.SC_UNAUTHORIZED, "not json at all", NOT_LOGGED_USER),
                arguments(HttpStatus.SC_UNAUTHORIZED, "{\"msg\":", NOT_LOGGED_USER),
                // valid JSON, but no usable message for the caller
                arguments(HttpStatus.SC_UNAUTHORIZED, "{\"msg\":\"   \"}", NOT_LOGGED_USER),
                arguments(HttpStatus.SC_UNAUTHORIZED, "{}", NOT_LOGGED_USER),
                arguments(HttpStatus.SC_BAD_REQUEST, "not json at all", MISSING_WORKSPACE));
    }

    static Stream<Arguments> errorBodyContentTypeArgs() {
        return Stream.of(
                arguments("application/json", REMOTE_ERROR_MESSAGE),
                arguments("application/json;charset=utf-8", REMOTE_ERROR_MESSAGE),
                // Jackson parses a structured +json suffix and a non-application type just as happily, so gating on
                // APPLICATION_JSON_TYPE.isCompatible would discard a remote message these used to surface
                arguments("application/problem+json", REMOTE_ERROR_MESSAGE),
                arguments("text/json", REMOTE_ERROR_MESSAGE),
                // these say nothing about the body, so the caller-facing fallback is used instead
                arguments("application/octet-stream", NOT_LOGGED_USER),
                arguments("text/plain", NOT_LOGGED_USER),
                arguments("*/*", NOT_LOGGED_USER),
                // a wildcard is not a legal response content type, so it is not trusted even with the +json suffix
                arguments("application/*", NOT_LOGGED_USER),
                arguments("application/*+json", NOT_LOGGED_USER));
    }

    /**
     * Pins which content types are read as JSON: subtype {@code json} or a {@code +json} structured suffix. Anything
     * the Jackson provider can deserialize must keep surfacing the remote {@code msg()}, exactly as it did before this
     * class started gating on the content type; everything else resolves to the caller-facing fallback instead of a
     * server error.
     */
    @ParameterizedTest
    @MethodSource("errorBodyContentTypeArgs")
    void sessionAuth__whenRemoteRepliesUnauthorized__thenJsonSubtypesSurfaceRemoteMessage(
            String contentType, String expectedMessage) {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var sessionTokenValue = "session-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED)
                        .withHeader("Content-Type", contentType)
                        .withBody("{\"msg\":\"%s\",\"code\":401}".formatted(REMOTE_ERROR_MESSAGE))));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(expectedMessage)
                .satisfies(throwable -> assertThat(((ClientErrorException) throwable).getResponse().getStatus())
                        .isEqualTo(HttpStatus.SC_UNAUTHORIZED));
    }

    @Test
    void sessionAuth__whenRemoteRepliesUnauthorizedWithoutContentType__thenFallbackMessage() {
        var workspaceName = "workspace-" + RandomStringUtils.secure().nextAlphanumeric(32);
        var sessionTokenValue = "session-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED)
                        .withBody("{\"msg\":\"%s\",\"code\":401}".formatted(REMOTE_ERROR_MESSAGE))));

        assertThatThrownBy(() -> remoteAuthService.authenticate(
                getHeadersMock(workspaceName, ""),
                sessionCookie(sessionTokenValue),
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(NOT_LOGGED_USER);
    }

    @Test
    void testListEligibleWorkspaces__filtersDefaultWorkspaceAndMapsToWorkspaceInfo() throws JsonProcessingException {
        var sessionTokenValue = "session-" + UUID.randomUUID();
        var production = podamFactory.manufacturePojo(WorkspaceInfo.class);
        var staging = podamFactory.manufacturePojo(WorkspaceInfo.class);
        var responseJson = OBJECT_MAPPER.writeValueAsString(Arrays.asList(
                Map.of("workspaceId", production.id(), "workspaceName", production.name()),
                Map.of("workspaceId", "ws-default", "workspaceName", DEFAULT_WORKSPACE_NAME),
                Map.of("workspaceId", staging.id(), "workspaceName", staging.name())));
        WIRE_MOCK.server().stubFor(get(urlPathEqualTo("/workspaces"))
                .withQueryParam("withoutExtendedData", equalTo("true"))
                .withCookie(RequestContext.SESSION_COOKIE, equalTo(sessionTokenValue))
                .willReturn(okJson(responseJson)));

        var result = remoteAuthService.listEligibleWorkspaces(sessionCookie(sessionTokenValue));

        assertThat(result).containsExactly(production, staging);
    }

    @Test
    void testListEligibleWorkspaces__whenNoSession__thenForbidden() {
        assertThatThrownBy(() -> remoteAuthService.listEligibleWorkspaces(null))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(NOT_LOGGED_USER);
    }

    static Stream<Arguments> listEligibleWorkspacesErrorArgs() {
        return Stream.of(
                arguments(HttpStatus.SC_UNAUTHORIZED, ClientErrorException.class, NOT_LOGGED_USER),
                arguments(HttpStatus.SC_FORBIDDEN, ClientErrorException.class, NOT_LOGGED_USER),
                arguments(HttpStatus.SC_SERVER_ERROR, InternalServerErrorException.class,
                        "Unexpected error while listing workspaces"));
    }

    @ParameterizedTest
    @MethodSource("listEligibleWorkspacesErrorArgs")
    void testListEligibleWorkspaces__whenRemoteFails__thenThrows(
            int remoteStatusCode, Class<? extends Exception> expectedExceptionClass, String expectedMessage) {
        var sessionTokenValue = "session-" + UUID.randomUUID();
        WIRE_MOCK.server().stubFor(get(urlPathEqualTo("/workspaces"))
                .willReturn(aResponse().withStatus(remoteStatusCode)));

        assertThatThrownBy(() -> remoteAuthService.listEligibleWorkspaces(sessionCookie(sessionTokenValue)))
                .isExactlyInstanceOf(expectedExceptionClass)
                .hasMessage(expectedMessage);
    }

    @Test
    void testAuthorizeWorkspace__returnsResolvedUserWorkspace() throws JsonProcessingException {
        var sessionTokenValue = "session-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        WIRE_MOCK.server().stubFor(post("/opik/auth-session")
                .withCookie(RequestContext.SESSION_COOKIE, equalTo(sessionTokenValue))
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(authResponse))));

        var result = remoteAuthService.authorizeWorkspace(sessionCookie(sessionTokenValue), workspaceName);

        assertThat(result).isEqualTo(UserWorkspace.builder()
                .userName(authResponse.user())
                .workspaceId(authResponse.workspaceId())
                .workspaceName(authResponse.workspaceName())
                .build());
    }

    @Test
    void testAuthorizeWorkspace__whenNoSession__thenForbidden() {
        assertThatThrownBy(
                () -> remoteAuthService.authorizeWorkspace(null, "workspace-" + UUID.randomUUID()))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(NOT_LOGGED_USER);
    }

    @Test
    void testAuthorizeWorkspace__whenDefaultWorkspace__thenForbidden() {
        var sessionTokenValue = "session-" + UUID.randomUUID();

        assertThatThrownBy(() -> remoteAuthService.authorizeWorkspace(
                sessionCookie(sessionTokenValue), DEFAULT_WORKSPACE_NAME))
                .isExactlyInstanceOf(ClientErrorException.class)
                .hasMessage(NOT_ALLOWED_TO_ACCESS_WORKSPACE);
    }

    @Test
    void testAuthorizeOAuth__setsCredentialsIntoContext() throws JsonProcessingException {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        var token = ValidatedToken.builder()
                .userName("oauth-user-" + UUID.randomUUID())
                .workspaceName("workspace-" + UUID.randomUUID())
                .build();
        WIRE_MOCK.server().stubFor(post("/opik/auth-by-username")
                .withHeader(OAUTH_USERNAME_HEADER, equalTo(token.userName()))
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(authResponse))));

        remoteAuthService.authorizeOAuth(token, ContextInfoHolder.builder()
                .uriInfo(createMockUriInfo("/priv/something"))
                .method("GET")
                .build());

        // bearer token is mapped to the apiKey slot as null for OAuth
        var expectedRequestContext = RequestContext.builder()
                .userName(authResponse.user())
                .workspaceId(authResponse.workspaceId())
                .workspaceName(authResponse.workspaceName())
                .quotas(authResponse.quotas())
                .build();
        assertThat(requestContext).isEqualTo(expectedRequestContext);
    }

    // --- Auth-call timeout and retry (see AuthenticationConfig / withAuthTimeout / withAuthRetry) ---
    //
    // The class-level service disables retries so the assertions above observe exactly one request.
    // These tests build their own service with retries enabled and assert on the request count
    // WireMock actually received, which is the only way a regression in withAuthRetry is visible.

    private RemoteAuthService authServiceWith(Duration timeout, int maxRetries) {
        return new RemoteAuthService(TestHttpClientUtils.client(),
                new RetriableHttpClient(TestHttpClientUtils.client()),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                new NoopCacheService(),
                timeout, maxRetries, Duration.ofMillis(10), Duration.ofMillis(50),
                new RemoteWorkspacePermissionsService(TestHttpClientUtils.client(),
                        new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url(""))),
                false);
    }

    private void authenticateWith(RemoteAuthService service, String workspaceName, String apiKey) {
        service.authenticate(
                getHeadersMock(workspaceName, apiKey), null,
                ContextInfoHolder.builder()
                        .uriInfo(createMockUriInfo("/priv/something"))
                        .method("GET")
                        .build());
    }

    private int authRequestCount() {
        return WIRE_MOCK.server()
                .countRequestsMatching(postRequestedFor(urlPathEqualTo("/opik/auth")).build())
                .getCount();
    }

    @Test
    void authRetry__whenTransportFailsThenRecovers__thenRetriedAndAuthSucceeds()
            throws JsonProcessingException {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        var scenario = "transient-transport-failure";

        // EMPTY_RESPONSE surfaces as NoHttpResponseException, which RetryUtils treats as retriable.
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .inScenario(scenario).whenScenarioStateIs(Scenario.STARTED)
                .willSetStateTo("recovered")
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .inScenario(scenario).whenScenarioStateIs("recovered")
                .willReturn(okJson(OBJECT_MAPPER.writeValueAsString(authResponse))));

        var apiKey = "apiKey-" + UUID.randomUUID();
        var workspaceName = "workspace-" + UUID.randomUUID();
        authenticateWith(authServiceWith(TEST_AUTH_TIMEOUT, 1), workspaceName, apiKey);

        // The second attempt is what populated the context: without the retry this would have thrown.
        var expectedRequestContext = RequestContext.builder()
                .userName(authResponse.user())
                .workspaceId(authResponse.workspaceId())
                .workspaceName(authResponse.workspaceName())
                .apiKey(apiKey)
                .quotas(authResponse.quotas())
                .build();
        assertThat(requestContext).isEqualTo(expectedRequestContext);
        assertThat(authRequestCount()).isEqualTo(2);
    }

    @Test
    void authRetry__whenTransportKeepsFailing__thenAttemptsAreBoundedByMaxRetries() {
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        var service = authServiceWith(TEST_AUTH_TIMEOUT, 2);

        assertThatThrownBy(() -> authenticateWith(service, "workspace-" + UUID.randomUUID(),
                "apiKey-" + UUID.randomUUID()))
                .isInstanceOf(ProcessingException.class);

        // maxRetries=2 means 1 initial attempt + 2 retries. A regression that retries unbounded,
        // or not at all, changes this number.
        assertThat(authRequestCount()).isEqualTo(3);
    }

    @Test
    void authRetry__whenRetriesDisabled__thenSingleAttempt() {
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));

        var service = authServiceWith(TEST_AUTH_TIMEOUT, 0);

        assertThatThrownBy(() -> authenticateWith(service, "workspace-" + UUID.randomUUID(),
                "apiKey-" + UUID.randomUUID()))
                .isInstanceOf(ProcessingException.class);

        assertThat(authRequestCount()).isEqualTo(1);
    }

    /**
     * The production failure this PR targets: React accepts the connection but never responds.
     * Without the per-call timeout the request would hang for the shared jerseyClient timeout (30s)
     * before returning a 500.
     * <p>
     * The stub delay (2s) sits between the per-call timeout (200ms) and the shared client timeout
     * (30s), which is what makes this a real regression test: if the {@code READ_TIMEOUT} override
     * stopped applying, the call would <em>succeed</em> after 2s instead of failing, so the
     * exception assertion alone catches it. The elapsed-time bound is a deliberately loose backstop
     * against the 30s path only -- it is not measuring the 200ms timeout, so ordinary CI jitter
     * cannot trip it.
     */
    @Test
    void authTimeout__whenReactStallsBeyondRequestTimeout__thenFailsFastAndRetries() {
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(okJson("{}").withFixedDelay(2_000)));

        var service = authServiceWith(Duration.ofMillis(200), 1);

        var startedAt = System.nanoTime();
        assertThatThrownBy(() -> authenticateWith(service, "workspace-" + UUID.randomUUID(),
                "apiKey-" + UUID.randomUUID()))
                .isInstanceOf(ProcessingException.class)
                .hasCauseInstanceOf(SocketTimeoutException.class);
        var elapsed = java.time.Duration.ofNanos(System.nanoTime() - startedAt);

        assertThat(authRequestCount()).isEqualTo(2);
        // Two attempts on the 30s shared timeout would be ~60s; anything under 15s proves the
        // per-call override is the timeout that fired, with ~37x headroom over the expected ~0.4s.
        assertThat(elapsed).isLessThan(java.time.Duration.ofSeconds(15));
    }

    /**
     * Bounds retry amplification on the unauthenticated path. {@code authenticate} falls back to
     * {@code getWorkspaceId} for public endpoints, and both call sites retry, so the concern is
     * that one inbound request could multiply into (attempts x 2) calls against a React service
     * that is already failing.
     * <p>
     * It cannot: the fallback is behind {@code catch (ClientErrorException)}, and a transport
     * failure raises {@code ProcessingException}, which is not a {@code ClientErrorException}. So
     * on the exact failure mode where amplification would matter, the fallback is unreachable and
     * the request stays bounded by {@code requestMaxRetries + 1}. This test fails if that catch is
     * ever widened to {@code RuntimeException} or {@code Exception}.
     */
    @Test
    void authRetry__whenTransportFailsOnPublicEndpoint__thenFallbackNotAttempted() {
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withFault(Fault.EMPTY_RESPONSE)));
        // Would serve the public fallback if it were ever reached.
        WIRE_MOCK.server().stubFor(get(urlPathEqualTo("/opik/workspaces"))
                .willReturn(okJson("{\"id\":\"" + UUID.randomUUID() + "\"}")));

        var service = authServiceWith(TEST_AUTH_TIMEOUT, 2);

        assertThatThrownBy(() -> authenticateWith(service, "workspace-" + UUID.randomUUID(),
                "apiKey-" + UUID.randomUUID()))
                .isInstanceOf(ProcessingException.class);

        // 3 = 1 initial + 2 retries on the single auth call. Not 6, which is what a second
        // retrying call site would cost.
        assertThat(authRequestCount()).isEqualTo(3);
        assertThat(WIRE_MOCK.server().getAllServeEvents()).hasSize(3);
    }

    /**
     * 503/504 are retried, because {@link RetriableHttpClient} maps them to
     * {@code RetryUtils.RetryableHttpException} before the response reaches {@code verifyResponse}.
     * That is the shared client's contract rather than this service's choice, and it is the useful
     * behaviour here: React emits 503s while draining during a rolling restart.
     */
    @Test
    void authRetry__whenReactReturnsServiceUnavailable__thenRetried() {
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withStatus(HttpStatus.SC_SERVICE_UNAVAILABLE)));

        var service = authServiceWith(TEST_AUTH_TIMEOUT, 2);

        assertThatThrownBy(() -> authenticateWith(service, "workspace-" + UUID.randomUUID(),
                "apiKey-" + UUID.randomUUID()))
                .isInstanceOf(RetryUtils.RetryableHttpException.class);

        assertThat(authRequestCount()).isEqualTo(3);
    }

    /**
     * The complementary invariant, and the one that matters for correctness: a deterministic
     * failure must not be retried. A 401 is mapped to {@code ClientErrorException}, which is not in
     * the retriable set, so it surfaces on the first attempt instead of burning the retry budget
     * and multiplying load on React.
     */
    @Test
    void authRetry__whenReactReturnsUnauthorized__thenNotRetried() {
        WIRE_MOCK.server().stubFor(post("/opik/auth")
                .willReturn(aResponse().withStatus(HttpStatus.SC_UNAUTHORIZED)));

        var service = authServiceWith(TEST_AUTH_TIMEOUT, 2);

        assertThatThrownBy(() -> authenticateWith(service, "workspace-" + UUID.randomUUID(),
                "apiKey-" + UUID.randomUUID()))
                .isInstanceOf(ClientErrorException.class);

        assertThat(authRequestCount()).isEqualTo(1);
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

    /**
     * Every path that produces credentials must resolve permissions when redaction is on. Dropping it from one
     * of them would redact administrators on that path alone - the failure the session-cookie route already
     * produced once - while the other paths kept working and the suite stayed green.
     * <p>
     * The answer is read from the workspace permissions API, one endpoint per credential type, rather than off
     * the authentication response: what a caller may see is data about the caller, not a by-product of whether
     * it could be authenticated.
     */
    @ParameterizedTest
    @MethodSource
    void permissionsAreResolvedOnEveryPath_whenRedactionIsEnabled(String emPath, String permissionsPath) {
        authenticateVia(emPath, serviceResolvingPermissions(true));

        assertThat(WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(permissionsPath))))
                .as("%s should resolve permissions through %s", emPath, permissionsPath)
                .hasSize(1);

        // And the granted permission reaches the context, so the assertion covers the decision and not just
        // the call: an endpoint answered but parsed wrong would still leave the caller unprivileged.
        assertThat(requestContext.getPermissions())
                .containsExactly(WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue());
    }

    static Stream<Arguments> permissionsAreResolvedOnEveryPath_whenRedactionIsEnabled() {
        return Stream.of(
                Arguments.of("/opik/auth", "/opik/workspace-permissions"),
                Arguments.of("/opik/auth-session", "/opik/workspace-permissions-session"),
                Arguments.of("/opik/auth-by-username", "/opik/workspace-permissions-by-username"));
    }

    /**
     * With redaction off the platform must see exactly the traffic it saw before this feature existed: the
     * same authentication payload, and no permission lookup at all. That is what makes the toggle safe to
     * ship, and what lets an older platform run against this build unchanged.
     */
    @ParameterizedTest
    @MethodSource("permissionsAreResolvedOnEveryPath_whenRedactionIsEnabled")
    void noPermissionTrafficAtAll_whenRedactionIsDisabled(String emPath, String permissionsPath)
            throws JsonProcessingException {
        authenticateVia(emPath, serviceResolvingPermissions(false));

        assertThat(WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(permissionsPath))))
                .as("%s must not resolve permissions while the feature is off", emPath)
                .isEmpty();

        // Asserted on the parsed fields, not the raw text: the requirement is that nothing was added to the
        // authentication request, and a substring check would pass for a payload carrying a new field as false.
        assertThat(OBJECT_MAPPER.readTree(requestBodySentTo(emPath)).fieldNames())
                .toIterable()
                .containsOnly("workspaceName", "path");
    }

    /**
     * A platform that predates the session and OAuth permission endpoints. The caller is left unprivileged,
     * which redacts, rather than the request failing: rolling the platform back must not take the API down.
     */
    @Test
    void anOlderPlatformLeavesTheCallerUnprivileged() {
        // Higher priority than the granted stub authenticateVia registers, which would otherwise win by
        // being registered later.
        WIRE_MOCK.server().stubFor(post(urlPathEqualTo("/opik/workspace-permissions-session"))
                .atPriority(1)
                .willReturn(aResponse().withStatus(404)));

        authenticateVia("/opik/auth-session", serviceResolvingPermissions(true));

        assertThat(requestContext.getPermissions()).isEmpty();
    }

    private RemoteAuthService serviceResolvingPermissions(boolean resolvePermissions) {
        return new RemoteAuthService(TestHttpClientUtils.client(),
                new RetriableHttpClient(TestHttpClientUtils.client()),
                new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url("")),
                () -> requestContext,
                new NoopCacheService(),
                TEST_AUTH_TIMEOUT, TEST_AUTH_MAX_RETRIES, TEST_AUTH_MIN_BACKOFF, TEST_AUTH_MAX_BACKOFF,
                new RemoteWorkspacePermissionsService(TestHttpClientUtils.client(),
                        new AuthenticationConfig.UrlConfig(WIRE_MOCK.server().url(""))),
                resolvePermissions);
    }

    private void authenticateVia(String emPath, RemoteAuthService service) {
        var authResponse = podamFactory.manufacturePojo(RemoteAuthService.AuthResponse.class);
        WIRE_MOCK.server().stubFor(post(emPath)
                .willReturn(okJson(writeJson(authResponse))));

        // Granted, so a path that resolves permissions correctly ends up privileged and one that skips the
        // lookup ends up masked - the two outcomes the tests above tell apart.
        var granted = WorkspaceUserPermissions.builder()
                .userName("user")
                .workspaceName("workspace")
                .permissions(List.of(new WorkspaceUserPermissions.Permission(
                        WorkspaceUserPermission.ORIGINAL_DATA_VIEW.getValue(), "true")))
                .build();
        Stream.of("/opik/workspace-permissions", "/opik/workspace-permissions-session",
                "/opik/workspace-permissions-by-username")
                .forEach(path -> WIRE_MOCK.server().stubFor(post(urlPathEqualTo(path))
                        .willReturn(okJson(writeJson(granted)))));

        var contextInfo = ContextInfoHolder.builder()
                .uriInfo(createMockUriInfo("/priv/something"))
                .method("GET")
                .build();
        var workspaceName = "workspace-" + UUID.randomUUID();

        switch (emPath) {
            case "/opik/auth" -> service.authenticate(
                    getHeadersMock(workspaceName, "apiKey-" + UUID.randomUUID()), null, contextInfo);
            case "/opik/auth-session" -> service.authenticate(
                    getHeadersMock(workspaceName, ""), sessionCookie("session-" + UUID.randomUUID()),
                    contextInfo);
            case "/opik/auth-by-username" -> service.authorizeOAuth(ValidatedToken.builder()
                    .userName("oauth-user-" + UUID.randomUUID())
                    .workspaceName(workspaceName)
                    .build(), contextInfo);
            default -> throw new IllegalArgumentException("Unhandled auth path: " + emPath);
        }
    }

    private static String writeJson(Object value) {
        try {
            return OBJECT_MAPPER.writeValueAsString(value);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String requestBodySentTo(String emPath) {
        var requests = WIRE_MOCK.server().findAll(postRequestedFor(urlPathEqualTo(emPath)));
        assertThat(requests).as("a request should have reached %s", emPath).hasSize(1);
        return requests.getFirst().getBodyAsString();
    }
}
