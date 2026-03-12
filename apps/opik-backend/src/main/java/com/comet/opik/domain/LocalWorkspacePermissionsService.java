package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceUserPermissions;
import lombok.NonNull;

import java.util.List;

public class LocalWorkspacePermissionsService implements WorkspacePermissionsService {

    @Override
    public WorkspaceUserPermissions getPermissions(@NonNull String apiKey, @NonNull String workspaceName) {
        return WorkspaceUserPermissions.builder()
                .userName(ProjectService.DEFAULT_USER)
                .workspaceName(workspaceName)
                .permissions(List.of())
                .build();
    }
}
