package com.comet.opik.infrastructure.auth;

import com.google.inject.servlet.RequestScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.Data;

@RequestScoped
@Data
public class RequestContext {

    public static final String WORKSPACE_HEADER = "Comet-Workspace";
    public static final String USER_NAME = "userName";
    public static final String WORKSPACE_NAME = "workspaceName";
    public static final String SESSION_COOKIE = "sessionToken";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String API_KEY = "apiKey";
    public static final String USER_LIMIT = "Opik-User-Limit";
    public static final String USER_REMAINING_LIMIT = "Opik-User-Remaining-Limit";
    public static final String USER_LIMIT_REMAINING_TTL = "Opik-User-Remaining-Limit-TTL-Millis";
    public static final String PROJECT_NAME = "projectName";

    private String userName;
    private String workspaceName;
    private String workspaceId;
    private String apiKey;
    private MultivaluedMap<String, String> headers;
}
