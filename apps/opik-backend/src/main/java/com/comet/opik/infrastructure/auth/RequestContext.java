package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.OpikVersion;
import com.comet.opik.api.Visibility;
import com.comet.opik.infrastructure.usagelimit.Quota;
import com.google.inject.servlet.RequestScoped;
import jakarta.ws.rs.core.MultivaluedMap;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@RequestScoped
@Data
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
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

    public static final String WORKSPACE_FALLBACK_HEADER = "X-Opik-Deprecation";
    public static final String WORKSPACE_FALLBACK_MESSAGE_TEMPLATE = "%s '%s' was found via workspace-wide search. In a future version, you will need to specify the project explicitly.";

    public static final String PROJECT_NAME = "projectName";
    // used by Optimization Studio to pass the Opik API key to the optimizer job, while keeping auth as is
    public static final String OPIK_API_KEY = "opikApiKey";
    public static final String SYSTEM_USER = "system";

    private String userName;
    private String workspaceId;
    private String workspaceName;
    private String apiKey;
    private MultivaluedMap<String, String> headers;
    private List<Quota> quotas;
    private Visibility visibility;
    private String workspaceFallbackMessage;
    private OpikVersion opikVersion;

    public void setWorkspaceFallbackFor(String entityType, String entityName) {
        this.workspaceFallbackMessage = WORKSPACE_FALLBACK_MESSAGE_TEMPLATE.formatted(entityType, entityName);
    }
}
