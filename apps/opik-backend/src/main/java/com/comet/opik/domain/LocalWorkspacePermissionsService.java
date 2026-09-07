package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceUserPermissions;
import lombok.NonNull;

import java.util.List;

public class LocalWorkspacePermissionsService implements WorkspacePermissionsService {

    @Override
    public WorkspaceUserPermissions getPermissions(@NonNull String apiKey, @NonNull String workspaceName) {
        return noPermissions(workspaceName);
    }

    @Override
    public WorkspaceUserPermissions getPermissionsBySession(@NonNull String sessionToken,
            @NonNull String workspaceName) {
        return noPermissions(workspaceName);
    }

    @Override
    public WorkspaceUserPermissions getPermissionsByUsername(@NonNull String userName,
            @NonNull String workspaceName) {
        return noPermissions(workspaceName);
    }

    private static WorkspaceUserPermissions noPermissions(String workspaceName) {
        return WorkspaceUserPermissions.builder()
                .userName(ProjectService.DEFAULT_USER)
                .workspaceName(workspaceName)
                .permissions(List.of())
                .build();
    }
}
