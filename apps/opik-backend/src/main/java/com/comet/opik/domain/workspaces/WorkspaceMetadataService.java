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
    Mono<ScopeMetadata> getProjectMetadata(String workspaceId, UUID projectId, String projectName);

    /**
     * Get experiment related metadata for to determine if dynamic sorting is allowed.
     * An optional dataset ID can be provided to scope the count
     *
     * @param workspaceId the workspace ID
     * @param datasetId the dataset ID to scope the count (if null, workspace-level count is used)
     * @return the experiment scope metadata
     */
    Mono<ExperimentScopeMetadata> getExperimentMetadata(String workspaceId, UUID datasetId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetadataServiceImpl implements WorkspaceMetadataService {

    private final @NonNull WorkspaceMetadataDAO workspaceMetadataDAO;
    private final @NonNull ProjectService projectService;

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

    @Override
    @Cacheable(name = "experiment_metadata", key = "'-'+ $workspaceId + '-' + $datasetId", returnType = ExperimentScopeMetadata.class)
    public Mono<ExperimentScopeMetadata> getExperimentMetadata(@NonNull String workspaceId, UUID datasetId) {
        return workspaceMetadataDAO.getExperimentMetadata(workspaceId, datasetId);
    }
}
