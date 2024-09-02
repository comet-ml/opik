package com.comet.opik.infrastructure.auth;

import com.google.inject.servlet.RequestScoped;

@RequestScoped
public class RequestContext {

    public static final String WORKSPACE_HEADER = "Comet-Workspace";
    public static final String USER_NAME = "userName";
    public static final String WORKSPACE_NAME = "workspaceName";
    public static final String SESSION_COOKIE = "sessionToken";
    public static final String WORKSPACE_ID = "workspaceId";

    private String userName;
    private String workspaceName;
    private String workspaceId;

    public final String getUserName() {
        return userName;
    }

    public final String getWorkspaceName() {
        return workspaceName;
    }

    public final String getWorkspaceId() {
        return workspaceId;
    }

    void setUserName(String workspaceName) {
        this.userName = workspaceName;
    }

    void setWorkspaceName(String workspaceName) {
        this.workspaceName = workspaceName;
    }

    public void setWorkspaceId(String workspaceId) {
        this.workspaceId = workspaceId;
    }
}
