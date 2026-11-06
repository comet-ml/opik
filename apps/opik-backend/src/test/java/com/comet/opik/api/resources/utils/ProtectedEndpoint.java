package com.comet.opik.api.resources.utils;

import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.auth.WorkspaceUserPermission;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.params.provider.Arguments;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * An endpoint guarded by {@code @RequiredPermissions}, described by how to call it and which permissions it
 * requires. The request itself may be invalid or target missing ids: the auth filter runs before the resource,
 * so the permission checks hold whatever the resource would answer.
 */
public record ProtectedEndpoint(
        String method, String path, Entity<?> entity, List<WorkspaceUserPermission> permissions,
        boolean publicFallback) {

    private static final String USER = "test-user-" + UUID.randomUUID();

    public ProtectedEndpoint(String method, String path, Entity<?> entity,
            List<WorkspaceUserPermission> permissions) {
        this(method, path, entity, permissions, false);
    }

    /**
     * Marks an endpoint that RemoteAuthService also serves to unauthenticated callers of public workspaces.
     * A denied caller is then retried as a public visitor, so against a workspace that is not public the
     * workspace lookup fails with 400 instead of the auth denial surfacing as 403.
     */
    public ProtectedEndpoint withPublicFallback() {
        return new ProtectedEndpoint(method, path, entity, permissions, true);
    }

    public static ProtectedEndpoint get(String path, WorkspaceUserPermission... permissions) {
        return new ProtectedEndpoint("GET", path, null, List.of(permissions));
    }

    public static ProtectedEndpoint post(String path, WorkspaceUserPermission... permissions) {
        return withJsonBody("POST", path, permissions);
    }

    public static ProtectedEndpoint put(String path, WorkspaceUserPermission... permissions) {
        return withJsonBody("PUT", path, permissions);
    }

    public static ProtectedEndpoint patch(String path, WorkspaceUserPermission... permissions) {
        return withJsonBody("PATCH", path, permissions);
    }

    public static ProtectedEndpoint delete(String path, WorkspaceUserPermission... permissions) {
        return new ProtectedEndpoint("DELETE", path, null, List.of(permissions));
    }

    public static ProtectedEndpoint postMultipart(String path, WorkspaceUserPermission... permissions) {
        return new ProtectedEndpoint("POST", path, Entity.entity("", MediaType.MULTIPART_FORM_DATA_TYPE),
                List.of(permissions));
    }

    private static ProtectedEndpoint withJsonBody(String method, String path,
            WorkspaceUserPermission... permissions) {
        return new ProtectedEndpoint(method, path, Entity.json(Map.of()), List.of(permissions));
    }

    /** One argument pair per (endpoint, required permission), for tests that deny a single permission. */
    public static Stream<Arguments> permissionDenials(Stream<ProtectedEndpoint> endpoints) {
        return endpoints.flatMap(endpoint -> endpoint.permissions().stream()
                .map(permission -> Arguments.of(endpoint, permission)));
    }

    public void assertSendsRequiredPermissions(WireMockServer wireMock, ClientSupport client, String baseUri) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        AuthTestUtils.mockTargetWorkspace(wireMock, apiKey, workspaceName, UUID.randomUUID().toString(), USER);

        wireMock.resetRequests();
        send(client, baseUri, apiKey, workspaceName).close();

        permissions.forEach(permission -> wireMock.verify(
                postRequestedFor(urlPathEqualTo("/opik/auth"))
                        .withRequestBody(matchingJsonPath(
                                "$.requiredPermissions[?(@ == '%s')]".formatted(permission.getValue())))));
    }

    public void assertForbiddenWhenDenied(WireMockServer wireMock, ClientSupport client, String baseUri,
            WorkspaceUserPermission deniedPermission) {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        AuthTestUtils.mockTargetWorkspaceDenyPermission(wireMock, apiKey, workspaceName,
                deniedPermission.getValue());
        wireMock.stubFor(WireMock.get(urlPathEqualTo("/workspaces/workspace-id"))
                .withQueryParam("name", equalTo(workspaceName))
                .willReturn(aResponse().withStatus(HttpStatus.SC_BAD_REQUEST)));

        try (var response = send(client, baseUri, apiKey, workspaceName)) {
            assertThat(response.getStatus())
                    .isEqualTo(publicFallback ? HttpStatus.SC_BAD_REQUEST : HttpStatus.SC_FORBIDDEN);
        }
    }

    private Response send(ClientSupport client, String baseUri, String apiKey, String workspaceName) {
        var request = client.target(baseUri + path)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName);
        return entity == null ? request.method(method) : request.method(method, entity);
    }

    @Override
    public String toString() {
        return method + " " + path;
    }
}
