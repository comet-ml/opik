package com.comet.opik.domain.workspaces;

import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.utils.ValidationUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.UUID;

@ImplementedBy(WorkspaceMetadataServiceImpl.class)
public interface WorkspaceMetadataService {
    /**
     *  Workspace ID could have been resolved from context, but required as a parameter to make caching work properly
     */
    Mono<ScopeMetadata> getWorkspaceMetadata(String workspaceId);

    /**
     *  Workspace ID could have been resolved from context, but required as a parameter to make caching work properly
     */
    Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId, String projectName);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetadataServiceImpl implements WorkspaceMetadataService {

    private final @NonNull WorkspaceMetadataDAO workspaceMetadataDAO;
    private final @NonNull ProjectService projectService;

    @Override
    @Cacheable(name = "workspace_metadata", key = "'-'+ $workspaceId", returnType = ScopeMetadata.class)
    public Mono<ScopeMetadata> getWorkspaceMetadata(@NonNull String workspaceId) {
        return workspaceMetadataDAO.getWorkspaceMetadata(workspaceId);
    }

    @Override
    public Mono<ScopeMetadata> getProjectMetadata(@NonNull String workspaceId, UUID projectId, String projectName) {
        ValidationUtils.validateProjectNameAndProjectId(projectName, projectId);
        return projectService.resolveProjectIdAndVerifyVisibility(projectId, projectName)
                .flatMap(resolvedProjectId -> getProjectMetadata(workspaceId, resolvedProjectId));
    }

    @Cacheable(name = "project_metadata", key = "'-'+ $workspaceId + '-' + $projectId", returnType = ScopeMetadata.class)
    private Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId) {
        return workspaceMetadataDAO.getProjectMetadata(workspaceId, projectId);
    }
}
