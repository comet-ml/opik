package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceUserPermissions;
import lombok.NonNull;

public interface WorkspacePermissionsService {

    WorkspaceUserPermissions getPermissions(@NonNull String apiKey, @NonNull String workspaceName);

    /**
     * The same lookup for a caller identified by session cookie rather than by api key.
     * <p>
     * Read-time redaction has to decide for browser and OAuth callers too, and this service answered only for
     * api keys. Keeping both on the permissions API means the answer arrives as data, rather than being
     * inferred from an authentication failure or bolted onto the authentication response.
     */
    WorkspaceUserPermissions getPermissionsBySession(@NonNull String sessionToken, @NonNull String workspaceName);

    /** The same lookup for an OAuth caller, identified by the username the token resolved to. */
    WorkspaceUserPermissions getPermissionsByUsername(@NonNull String userName, @NonNull String workspaceName);
}
