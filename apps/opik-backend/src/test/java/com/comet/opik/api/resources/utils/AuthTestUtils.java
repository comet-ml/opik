package com.comet.opik.api.resources.utils;

import com.github.tomakehurst.wiremock.WireMockServer;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.experimental.UtilityClass;

import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@UtilityClass
public class AuthTestUtils {

    public static final String AUTH_RESPONSE = "{\"user\": \"%s\", \"workspaceId\": \"%s\" , \"workspaceName\": \"%s\"}";

    public static String newWorkspaceAuthResponse(String user, String workspaceId) {
        return newWorkspaceAuthResponse(user, workspaceId, "");
    }

    public static String newWorkspaceAuthResponse(String user, String workspaceId, String workspaceName) {
        return AUTH_RESPONSE.formatted(user, workspaceId, workspaceName);
    }

    public static void mockTargetWorkspace(WireMockServer server, String apiKey, String workspaceName,
            String workspaceId, String user) {
        server.stubFor(
                post(urlPathEqualTo("/opik/auth"))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .withRequestBody(matchingJsonPath("$.path", matching("/v1/private/.*")))
                        .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(user, workspaceId, workspaceName))));
    }

    public static void mockSessionCookieTargetWorkspace(WireMockServer server, String sessionToken,
            String workspaceName, String workspaceId, String user) {
        server.stubFor(
                post(urlPathEqualTo("/opik/auth-session"))
                        .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .withRequestBody(matchingJsonPath("$.path", matching("/v1/private/.*")))
                        .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(user, workspaceId))));
    }

}
