package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.experimental.UtilityClass;

import java.util.List;

import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@UtilityClass
public class AuthTestUtils {

    private static final String AUTH_RESPONSE = """
            {
                "user": "%s",
                "workspaceId": "%s",
                "workspaceName": "%s",
                "quotas": %s
            }
            """;

    public static String newWorkspaceAuthResponse(String user, String workspaceId) {
        return newWorkspaceAuthResponse(user, workspaceId, "", null);
    }

    public static String newWorkspaceAuthResponse(
            String user, String workspaceId, String workspaceName, List<Quota> quotas) {
        return AUTH_RESPONSE.formatted(user, workspaceId, workspaceName,
                quotas == null ? null : JsonUtils.writeValueAsString(quotas));
    }

    public static void mockTargetWorkspace(WireMockServer server, String apiKey, String workspaceName,
            String workspaceId, String user) {
        mockTargetWorkspace(server, apiKey, workspaceName, workspaceId, user, null);
    }

    public static void mockTargetWorkspace(
            WireMockServer server, String apiKey, String workspaceName, String workspaceId, String user,
            List<Quota> quotas) {
        server.stubFor(
                post(urlPathEqualTo("/opik/auth"))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .withRequestBody(matchingJsonPath("$.path", matching("/v1/private/.*")))
                        .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(user, workspaceId, workspaceName,
                                quotas))));
    }

    public static void mockGetWorkspaceIdByName(
            WireMockServer server, String workspaceName, String workspaceId) {
        server.stubFor(
                get(urlPathEqualTo("/workspaces/workspace-id"))
                        .withQueryParam("name", equalTo(workspaceName))
                        .willReturn(aResponse()
                                .withStatus(200)
                                .withBody(workspaceId)));
    }

    public static void mockSessionCookieTargetWorkspace(WireMockServer server, String sessionToken,
            String workspaceName, String workspaceId, String user) {
        mockSessionCookieTargetWorkspace(server, sessionToken, workspaceName, workspaceId, user, null);
    }

    public static void mockSessionCookieTargetWorkspace(
            WireMockServer server, String sessionToken, String workspaceName, String workspaceId, String user,
            List<Quota> quotas) {
        server.stubFor(
                post(urlPathEqualTo("/opik/auth-session"))
                        .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .withRequestBody(matchingJsonPath("$.path", matching("/v1/private/.*")))
                        .willReturn(okJson(AuthTestUtils.newWorkspaceAuthResponse(user, workspaceId, "",
                                quotas))));
    }
}
