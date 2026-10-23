package com.comet.opik.domain;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.mockito.Mockito;
import org.redisson.api.RedissonReactiveClient;

import static com.comet.opik.domain.mcpoauth.OAuthConstants.OAUTH_USERNAME_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class RemoteWorkspaceFeaturesServiceTest {

    private static final String WORKSPACE_NAME = "team-a";
    private static final String ENABLED = "{\"workspaceName\":\"team-a\",\"features\":{\"guardrails\":true}}";

    private WireMockUtils.WireMockRuntime wireMock;
    private RemoteWorkspaceFeaturesService service;

    @BeforeAll
    void setUpAll() {
        wireMock = WireMockUtils.startWireMock();
        // No cache: every call reaches the platform, so each credential path is observable.
        service = new RemoteWorkspaceFeaturesService(TestHttpClientUtils.client(),
                new AuthenticationConfig.UrlConfig(wireMock.runtimeInfo().getHttpBaseUrl()),
                Mockito.mock(RedissonReactiveClient.class), 0);
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

        assertThat(service.isEnabled(caller("key-1", "key-1"), WorkspaceFeature.GUARDRAILS)).isTrue();
    }

    @Test
    void sessionCallerIsAskedForWithItsCookie() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features-session"))
                .withHeader(HttpHeaders.COOKIE, containing(RequestContext.SESSION_COOKIE + "=session-1"))
                .willReturn(okJson(ENABLED)));

        assertThat(service.isEnabled(caller("session-1", null), WorkspaceFeature.GUARDRAILS)).isTrue();
    }

    @Test
    void oauthCallerIsAskedForByUserName() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features-by-username"))
                .withHeader(OAUTH_USERNAME_HEADER, equalTo("user-1"))
                .willReturn(okJson(ENABLED)));

        assertThat(service.isEnabled(caller(null, "Bearer some-oauth-token"), WorkspaceFeature.GUARDRAILS))
                .isTrue();
    }

    @Test
    void platformErrorOrUnknownFeatureReadsAsNotEnabled() {
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("key-500"))
                .willReturn(aResponse().withStatus(500)));
        wireMock.server().stubFor(post(urlPathEqualTo("/opik/workspace-features"))
                .withHeader(HttpHeaders.AUTHORIZATION, equalTo("key-old"))
                .willReturn(okJson("{\"workspaceName\":\"team-a\",\"features\":{}}")));

        assertThat(service.isEnabled(caller("key-500", "key-500"), WorkspaceFeature.GUARDRAILS)).isFalse();
        assertThat(service.isEnabled(caller("key-old", "key-old"), WorkspaceFeature.GUARDRAILS)).isFalse();
    }

    @Test
    void deviceTokenCallerIsNotAsked() {
        var caller = caller("key-1", "key-1");
        caller.setCipxDeviceId("device-1");

        assertThat(service.isEnabled(caller, WorkspaceFeature.GUARDRAILS)).isFalse();
        wireMock.server().verify(0, postRequestedFor(urlPathEqualTo("/opik/workspace-features")));
    }

    private static RequestContext caller(String credential, String authorizationHeader) {
        var headers = new MultivaluedHashMap<String, String>();
        if (authorizationHeader != null) {
            headers.putSingle(HttpHeaders.AUTHORIZATION, authorizationHeader);
        }
        return RequestContext.builder()
                .userName("user-1")
                .workspaceId("workspace-id-1")
                .workspaceName(WORKSPACE_NAME)
                .apiKey(credential)
                .headers(headers)
                .build();
    }
}
