package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.google.inject.servlet.RequestScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.Data;

import java.util.List;

@RequestScoped
@Data
public class RequestContext {

    public static final String WORKSPACE_HEADER = "Comet-Workspace";
    public static final String WORKSPACE_QUERY_PARAM = "workspace_name";
    public static final String USER_NAME = "userName";
    public static final String WORKSPACE_NAME = "workspaceName";
    public static final String SESSION_COOKIE = "sessionToken";
    public static final String WORKSPACE_ID = "workspaceId";
    public static final String API_KEY = "apiKey";
    public static final String LIMIT = "Opik-%s-Limit";
    public static final String REMAINING_LIMIT = "Opik-%s-Remaining-Limit";
    public static final String LIMIT_REMAINING_TTL = "Opik-%s-Remaining-Limit-TTL-Millis";
    public static final String VISIBILITY = "visibility";
    public static final String RATE_LIMIT_RESET = "RateLimit-Reset";

    public static final String PROJECT_NAME = "projectName";

    private String userName;
    private String workspaceId;
    private String workspaceName;
    private String apiKey;
    private MultivaluedMap<String, String> headers;
    private List<Quota> quotas;
    private Visibility visibility;
}
