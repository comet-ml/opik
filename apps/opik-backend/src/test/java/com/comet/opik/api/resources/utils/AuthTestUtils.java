package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.usagelimit.Quota;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static com.comet.opik.infrastructure.auth.RequestContext.SESSION_COOKIE;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.forbidden;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;

@UtilityClass
public class AuthTestUtils {

    public static String newWorkspaceAuthResponse(String user, String workspaceId) {
        return newWorkspaceAuthResponse(user, workspaceId, "", null);
    }

    public static String newWorkspaceAuthResponse(
            String user, String workspaceId, String workspaceName, List<Quota> quotas) {
        var response = new LinkedHashMap<String, Object>();
        response.put("user", user);
        response.put("workspaceId", workspaceId);
        response.put("workspaceName", workspaceName);
        response.put("quotas", quotas);
        return JsonUtils.writeValueAsString(response);
    }

    public static void mockTargetWorkspace(WireMockServer server, String apiKey, String workspaceName,
            String workspaceId, String user) {
        mockTargetWorkspace(server, apiKey, workspaceName, workspaceId, user, null);
    }

    /**
     * Stubs the auth call so it returns the caller's workspace permissions, the way the platform does. Pass
     * the granted permission names; anything omitted is simply absent, which is how a withheld permission
     * reaches the backend.
     */
    public static void mockTargetWorkspaceWithPermissions(WireMockServer server, String apiKey,
            String workspaceName, String workspaceId, String user, List<String> grantedPermissions) {
        var response = new LinkedHashMap<String, Object>();
        response.put("user", user);
        response.put("workspaceId", workspaceId);
        response.put("workspaceName", workspaceName);
        response.put("quotas", null);
        response.put("permissions", grantedPermissions.stream()
                .map(name -> Map.of("permissionName", name, "permissionValue", "true"))
                .toList());

        server.stubFor(
                post(urlPathEqualTo("/opik/auth"))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .willReturn(okJson(JsonUtils.writeValueAsString(response))));
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

    public static void mockTargetWorkspaceDenyPermission(
            WireMockServer server, String apiKey, String workspaceName, String requiredPermission) {
        server.stubFor(
                post(urlPathEqualTo("/opik/auth"))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(apiKey))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .withRequestBody(
                                matchingJsonPath("$.requiredPermissions[0]", equalTo(requiredPermission)))
                        .willReturn(forbidden()));
    }

    /**
     * The session-cookie counterpart of {@link #mockTargetWorkspaceWithPermissions}. Browser and OAuth callers
     * authenticate through {@code /opik/auth-session}, which returns the same permission set, so anything that
     * reads permissions has to be exercised on this path too and not only with an api key.
     */
    public static void mockSessionCookieTargetWorkspaceWithPermissions(WireMockServer server, String sessionToken,
            String workspaceName, String workspaceId, String user, List<String> grantedPermissions) {
        var response = new LinkedHashMap<String, Object>();
        response.put("user", user);
        response.put("workspaceId", workspaceId);
        response.put("workspaceName", workspaceName);
        response.put("quotas", null);
        response.put("permissions", grantedPermissions.stream()
                .map(name -> Map.of("permissionName", name, "permissionValue", "true"))
                .toList());

        server.stubFor(
                post(urlPathEqualTo("/opik/auth-session"))
                        .withCookie(SESSION_COOKIE, equalTo(sessionToken))
                        .withRequestBody(matchingJsonPath("$.workspaceName", equalTo(workspaceName)))
                        .willReturn(okJson(JsonUtils.writeValueAsString(response))));
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
