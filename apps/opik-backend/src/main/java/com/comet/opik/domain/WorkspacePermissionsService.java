package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceUserPermissions;
import lombok.NonNull;

public interface WorkspacePermissionsService {

    WorkspaceUserPermissions getPermissions(@NonNull String apiKey, @NonNull String workspaceName);
}
