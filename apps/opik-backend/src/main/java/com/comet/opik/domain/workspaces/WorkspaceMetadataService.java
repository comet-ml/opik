package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.cache.Cacheable;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

import java.util.UUID;

@ImplementedBy(WorkspaceMetadataServiceImpl.class)
public interface WorkspaceMetadataService {
    Mono<ScopeMetadata> getWorkspaceMetadata(String workspaceId);

    Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId);

    Mono<ScopeMetadata> getProjectMetadataByProjectIdentifier(String workspaceId, UUID projectId, String projectName);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetadataServiceImpl implements WorkspaceMetadataService {

    private final @NonNull WorkspaceMetadataDAO workspaceMetadataDAO;
    private final @NonNull com.comet.opik.domain.ProjectService projectService;

    @Override
    @Cacheable(name = "workspace_metadata", key = "'-'+ $workspaceId", returnType = ScopeMetadata.class)
    public Mono<ScopeMetadata> getWorkspaceMetadata(@NonNull String workspaceId) {
        return workspaceMetadataDAO.getWorkspaceMetadata(workspaceId);
    }

    @Override
    @Cacheable(name = "workspace_metadata", key = "'-'+ $workspaceId + '-' + $projectId", returnType = ScopeMetadata.class)
    public Mono<ScopeMetadata> getProjectMetadata(@NonNull String workspaceId, @NonNull UUID projectId) {
        return workspaceMetadataDAO.getProjectMetadata(workspaceId, projectId);
    }

    @Override
    public Mono<ScopeMetadata> getProjectMetadataByProjectIdentifier(@NonNull String workspaceId, UUID projectId,
            String projectName) {
        return Mono.defer(() -> {
            if (projectId != null) {
                return getProjectMetadata(workspaceId, projectId);
            }
            return projectService.resolveProjectIdAndVerifyVisibility(projectId, projectName)
                    .flatMap(resolvedProjectId -> getProjectMetadata(workspaceId, resolvedProjectId));
        });
    }
}
