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

import java.util.List;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;

public interface AuthService {

    void authenticate(HttpHeaders headers, Cookie sessionToken, ContextInfoHolder contextInfo);
    void authenticateSession(Cookie sessionToken);

    // MCP OAuth Authorization Server: resolve the logged-in user's workspaces for the consent picker, and
    // validate a chosen workspace against the session (returning the resolved user). Cloud delegates to
    // comet-backend; OSS returns the hardcoded admin/default.
    List<WorkspaceInfo> listEligibleWorkspaces(Cookie sessionToken);
    UserWorkspace authorizeWorkspace(Cookie sessionToken, String workspaceName);
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

    @Override
    public List<WorkspaceInfo> listEligibleWorkspaces(Cookie sessionToken) {
        return List.of(new WorkspaceInfo(ProjectService.DEFAULT_WORKSPACE_ID, ProjectService.DEFAULT_WORKSPACE_NAME));
    }

    @Override
    public UserWorkspace authorizeWorkspace(Cookie sessionToken, String workspaceName) {
        return new UserWorkspace(ProjectService.DEFAULT_USER, ProjectService.DEFAULT_WORKSPACE_ID,
                ProjectService.DEFAULT_WORKSPACE_NAME);
    }
}
