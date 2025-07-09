package com.comet.opik.infrastructure.auth;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.utils.WorkspaceUtils;
import jakarta.inject.Provider;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Cookie;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;

public interface AuthService {

    void authenticate(HttpHeaders headers, Cookie sessionToken, ContextInfoHolder contextInfo);
    void authenticateSession(Cookie sessionToken);
}

@RequiredArgsConstructor
class AuthServiceImpl implements AuthService {

    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public void authenticate(HttpHeaders headers, Cookie sessionToken, ContextInfoHolder contextInfo) {

        var currentWorkspaceName = WorkspaceUtils.getWorkspaceName(headers.getHeaderString(WORKSPACE_HEADER));

        if (ProjectService.DEFAULT_WORKSPACE_NAME.equals(currentWorkspaceName)) {
            requestContext.get().setUserName(ProjectService.DEFAULT_USER);
            requestContext.get().setWorkspaceId(ProjectService.DEFAULT_WORKSPACE_ID);
            requestContext.get().setWorkspaceName(ProjectService.DEFAULT_WORKSPACE_NAME);
            requestContext.get().setApiKey("default");
            return;
        }

        throw new ClientErrorException("Workspace not found", Response.Status.NOT_FOUND);
    }

    @Override
    public void authenticateSession(Cookie sessionToken) {
        // no authentication for local installations
    }
}
