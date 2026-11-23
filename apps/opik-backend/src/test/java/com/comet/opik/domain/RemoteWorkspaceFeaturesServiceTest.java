package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.RetryUtils;
import jakarta.ws.rs.ServiceUnavailableException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.redisson.api.RBucketReactive;
import org.redisson.api.RedissonReactiveClient;
import org.redisson.client.RedisException;
import org.redisson.client.codec.Codec;
import org.redisson.client.codec.StringCodec;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteWorkspaceFeaturesServiceTest {

    private static final String WORKSPACE_NAME = "team-a";
    private static final String ENABLED = "{\"workspaceName\":\"team-a\",\"features\":{\"guardrails\":true}}";
    // Small versions of the auth hop's 3 s timeout and single retry.
    private static final Duration REQUEST_TIMEOUT = Duration.ofMillis(300);
    private static final int MAX_RETRIES = 1;

    private WireMockUtils.WireMockRuntime wireMock;
    private AuthenticationConfig.UrlConfig platformUrl;
    private RemoteWorkspaceFeaturesService service;

    @BeforeAll
    void setUpAll() {
        wireMock = WireMockUtils.startWireMock();
        platformUrl = new AuthenticationConfig.UrlConfig(wireMock.runtimeInfo().getHttpBaseUrl());
        // No cache: every call reaches the platform, so each credential path is observable.
        service = service(Mockito.mock(RedissonReactiveClient.class), Duration.ZERO);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
    }

    @Test
    void apiKeyCallerIsAskedForWithItsKey() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("key-1"))
                .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(WORKSPACE_NAME)))
                .withRequestBody(matchingJsonPath("$.features[0]", equalTo("guardrails")))
                .willReturn(okJson(ENABLED)));

        assertThat(service.isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS)).isTrue();
    }

    @Test
    void sessionCallerIsAskedForWithItsCookie() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features-session"))
                .withHeader(HttpHeaders.COOKIE, containing(RequestContext.SESSION_COOKIE + "=session-1"))
                .willReturn(okJson(ENABLED)));

        assertThat(service.isEnabled(caller(), request(null, "session-1"), WorkspaceFeature.GUARDRAILS)).isTrue();
    }

    @Test
    void sessionCookieIsPreferredOverAnApiKeyAsAuthenticationPrefersIt() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features-session"))
                .withHeader(HttpHeaders.COOKIE, containing(RequestContext.SESSION_COOKIE + "=session-1"))
                .willReturn(okJson(ENABLED)));

        assertThat(service.isEnabled(caller(), request("key-1", "session-1"), WorkspaceFeature.GUARDRAILS))
                .isTrue();
        wireMock.server().verify(0, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
    }

    @Test
    void unknownFeatureReadsAsNotEnabled() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .willReturn(okJson("{\"workspaceName\":\"team-a\",\"features\":{}}")));

        assertThat(service.isEnabled(caller(), request("key-old", null), WorkspaceFeature.GUARDRAILS)).isFalse();
    }

    @Test
    void platformFailureIsUnavailableNotDisabledAndIsNotCached() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .willReturn(aResponse().withStatus(500)));
        var redis = Mockito.mock(RedissonReactiveClient.class);
        var entry = cacheEntry(redis, "workspaceFeature-workspace-id-1-guardrails", Mono.empty());

        assertThatThrownBy(() -> service(redis, Duration.ofSeconds(5))
                .isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isInstanceOf(ServiceUnavailableException.class);
        verify(entry, never()).set(any(), any(Duration.class));
    }

    @Test
    void slowPlatformIsRetriedOnceWithinTheAuthTimeout() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .willReturn(okJson(ENABLED).withFixedDelay(5_000)));
        var start = Instant.now();

        assertThatThrownBy(() -> service.isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isInstanceOf(ServiceUnavailableException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofSeconds(2));
        wireMock.server().verify(1 + MAX_RETRIES, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
    }

    @ParameterizedTest
    @ValueSource(ints = {401, 403})
    void refusedCredentialReadsAsNotEnabledAndIsNotCached(int status) {
        // An MCP OAuth or device token, which the platform doesn't take as an API key.
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .willReturn(aResponse().withStatus(status)));
        var redis = Mockito.mock(RedissonReactiveClient.class);
        var entry = cacheEntry(redis, "workspaceFeature-workspace-id-1-guardrails", Mono.empty());

        assertThat(service(redis, Duration.ofSeconds(5))
                .isEnabled(caller(), request("Bearer oauth-token", null), WorkspaceFeature.GUARDRAILS)).isFalse();
        verify(entry, never()).set(any(), any(Duration.class));
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 404, 429})
    void otherClientErrorIsUnavailableNotDisabled(int status) {
        // A rate limit, a platform without this endpoint yet, or contract drift: the platform couldn't tell.
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .willReturn(aResponse().withStatus(status)));

        assertThatThrownBy(() -> service.isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isInstanceOf(ServiceUnavailableException.class);
    }

    @Test
    void requestWithoutACredentialIsNotAsked() {
        assertThat(service.isEnabled(caller(), request(null, null), WorkspaceFeature.GUARDRAILS)).isFalse();
        wireMock.server().verify(0, postRequestedFor(urlPathMatching("/opik/workspace-features.*")));
    }

    @Test
    void cachedAnswersAreUsedWithoutAskingThePlatform() {
        var redis = Mockito.mock(RedissonReactiveClient.class);
        cacheEntry(redis, "workspaceFeature-workspace-on-guardrails", Mono.just("true"));
        cacheEntry(redis, "workspaceFeature-workspace-off-guardrails", Mono.just("false"));
        var cached = cachedService(redis);

        assertThat(cached.isEnabled(caller("workspace-on"), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isTrue();
        assertThat(cached.isEnabled(caller("workspace-off"), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isFalse();
        wireMock.server().verify(0, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
    }

    @Test
    void cacheMissAsksThePlatformOnceAndStoresTheAnswerUnderTheWorkspaceAndFeature() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features")).willReturn(okJson(ENABLED)));
        var redis = Mockito.mock(RedissonReactiveClient.class);
        var entry = cacheEntry(redis, "workspaceFeature-workspace-id-1-guardrails", Mono.empty());

        assertThat(cachedService(redis).isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isTrue();
        wireMock.server().verify(1, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
        verify(entry).set("true", Duration.ofSeconds(5));
    }

    @Test
    void cachedValueThisServiceDidNotWriteIsAMiss() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features")).willReturn(okJson(ENABLED)));
        var redis = Mockito.mock(RedissonReactiveClient.class);
        var entry = cacheEntry(redis, "workspaceFeature-workspace-id-1-guardrails", Mono.just("yes"));

        assertThat(cachedService(redis).isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isTrue();
        wireMock.server().verify(1, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
        verify(entry).set("true", Duration.ofSeconds(5));
    }

    @Test
    void cacheReadFailureAsksThePlatformAndSkipsTheWrite() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features")).willReturn(okJson(ENABLED)));
        var redis = Mockito.mock(RedissonReactiveClient.class);
        var entry = cacheEntry(redis, "workspaceFeature-workspace-id-1-guardrails",
                Mono.error(new RedisException("cache down")));

        assertThat(cachedService(redis).isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isTrue();
        wireMock.server().verify(1, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
        verify(entry, never()).set(any(), any(Duration.class));
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void cacheWriteFailureStillReturnsTheAnswer() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features")).willReturn(okJson(ENABLED)));
        var redis = Mockito.mock(RedissonReactiveClient.class);
        RBucketReactive entry = Mockito.mock(RBucketReactive.class);
        when(redis.getBucket(anyString(), any(Codec.class))).thenReturn(entry);
        when(entry.get()).thenReturn(Mono.empty());
        when(entry.set(any(), any(Duration.class))).thenReturn(Mono.error(new RedisException("cache down")));

        assertThat(cachedService(redis).isEnabled(caller(), request("key-1", null), WorkspaceFeature.GUARDRAILS))
                .isTrue();
    }

    private RemoteWorkspaceFeaturesService cachedService(RedissonReactiveClient redis) {
        return service(redis, Duration.ofSeconds(5));
    }

    private RemoteWorkspaceFeaturesService service(RedissonReactiveClient redis, Duration cacheTtl) {
        return new RemoteWorkspaceFeaturesService(new RetriableHttpClient(TestHttpClientUtils.client()), platformUrl,
                redis, cacheTtl, REQUEST_TIMEOUT,
                RetryUtils.handleHttpErrors(MAX_RETRIES, Duration.ofMillis(10), Duration.ofMillis(10)));
    }

    @SuppressWarnings("unchecked")
    private static RBucketReactive<String> cacheEntry(RedissonReactiveClient redis, String key, Mono<String> value) {
        RBucketReactive<String> entry = Mockito.mock(RBucketReactive.class);
        when(entry.get()).thenReturn(value);
        when(entry.set(any(), any(Duration.class))).thenReturn(Mono.empty());
        when(redis.<String>getBucket(key, StringCodec.INSTANCE)).thenReturn(entry);
        return entry;
    }

    private static RequestContext caller() {
        return caller("workspace-id-1");
    }

    private static RequestContext caller(String workspaceId) {
        return RequestContext.builder()
                .userName("user-1")
                .workspaceId(workspaceId)
                .workspaceName(WORKSPACE_NAME)
                .build();
    }

    private static HttpHeaders request(String authorization, String sessionToken) {
        var request = Mockito.mock(HttpHeaders.class);
        when(request.getHeaderString(HttpHeaders.AUTHORIZATION)).thenReturn(authorization);
        when(request.getCookies()).thenReturn(sessionToken == null
                ? Map.of()
                : Map.of(RequestContext.SESSION_COOKIE,
                        new Cookie.Builder(RequestContext.SESSION_COOKIE).value(sessionToken).build()));
        return request;
    }
}
