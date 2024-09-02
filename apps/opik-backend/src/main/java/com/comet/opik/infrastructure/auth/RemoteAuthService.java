package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.Optional;

import static com.comet.opik.infrastructure.AuthenticationConfig.UrlConfig;

@RequiredArgsConstructor
@Slf4j
class RemoteAuthService implements AuthService {

    private final @NonNull Client client;
    private final @NonNull UrlConfig apiKeyAuthUrl;
    private final @NonNull UrlConfig uiAuthUrl;
    private final @NonNull Provider<RequestContext> requestContext;

    record AuthRequest(String workspaceName) {
    }
    record AuthResponse(String user, String workspaceId) {
    }

    @Override
    public void authenticate(HttpHeaders headers, Cookie sessionToken) {

        var currentWorkspaceName = getCurrentWorkspaceName(headers);

        if (currentWorkspaceName.isBlank()
                || ProjectService.DEFAULT_WORKSPACE_NAME.equalsIgnoreCase(currentWorkspaceName)) {
            log.warn("Default workspace name is not allowed");
            throw new ClientErrorException(Response.Status.FORBIDDEN);
        }

        if (sessionToken != null) {
            authenticateUsingSessionToken(sessionToken, currentWorkspaceName);
            requestContext.get().setWorkspaceName(currentWorkspaceName);
            return;
        }

        authenticateUsingApiKey(headers, currentWorkspaceName);
        requestContext.get().setWorkspaceName(currentWorkspaceName);
    }

    private String getCurrentWorkspaceName(HttpHeaders headers) {
        return Optional.ofNullable(headers.getHeaderString(RequestContext.WORKSPACE_HEADER))
                .orElse("");
    }

    private void authenticateUsingSessionToken(Cookie sessionToken, String workspaceName) {
        try (var response = client.target(URI.create(uiAuthUrl.url()))
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .cookie(sessionToken)
                .post(Entity.json(new AuthRequest(workspaceName)))) {

            verifyResponse(response);
        }
    }

    private void authenticateUsingApiKey(HttpHeaders headers, String workspaceName) {
        try (var response = client.target(URI.create(apiKeyAuthUrl.url()))
                .request()
                .accept(MediaType.APPLICATION_JSON)
                .header(jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION,
                        Optional.ofNullable(headers.getHeaderString(jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION))
                                .orElse(""))
                .post(Entity.json(new AuthRequest(workspaceName)))) {

            verifyResponse(response);
        }
    }

    private void verifyResponse(Response response) {
        if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
            var authResponse = response.readEntity(AuthResponse.class);

            if (StringUtils.isEmpty(authResponse.user())) {
                log.warn("User not found");
                throw new ClientErrorException(Response.Status.UNAUTHORIZED);
            }

            requestContext.get().setUserName(authResponse.user());
            requestContext.get().setWorkspaceId(authResponse.workspaceId());
            return;

        } else if (response.getStatus() == Response.Status.UNAUTHORIZED.getStatusCode()) {
            throw new ClientErrorException("User not allowed to access workspace",
                    Response.Status.UNAUTHORIZED);
        } else if (response.getStatus() == Response.Status.FORBIDDEN.getStatusCode()) {
            throw new ClientErrorException("User has bot permission to the workspace", Response.Status.FORBIDDEN);
        } else if (response.getStatusInfo().getFamily() == Response.Status.Family.SERVER_ERROR) {
            log.error("Error while authenticating user");
            throw new ClientErrorException(Response.Status.INTERNAL_SERVER_ERROR);
        }

        log.error("Unexpected error while authenticating user, status code: {}", response.getStatus());
        throw new ClientErrorException(Response.Status.INTERNAL_SERVER_ERROR);
    }

}
