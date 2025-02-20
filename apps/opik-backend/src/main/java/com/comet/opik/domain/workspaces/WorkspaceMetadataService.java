package com.comet.opik.domain.workspaces;

import com.comet.opik.infrastructure.cache.Cacheable;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import reactor.core.publisher.Mono;

@ImplementedBy(WorkspaceMetadataServiceImpl.class)
public interface WorkspaceMetadataService {
    Mono<WorkspaceMetadata> getWorkspaceMetadata(String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class WorkspaceMetadataServiceImpl implements WorkspaceMetadataService {

    private final @NonNull WorkspaceMetadataDAO workspaceMetadataDAO;

    @Override
    @Cacheable(name = "workspace_metadata", key = "'-'+ $workspaceId", returnType = WorkspaceMetadata.class)
    public Mono<WorkspaceMetadata> getWorkspaceMetadata(@NonNull String workspaceId) {
        return workspaceMetadataDAO.getWorkspaceMetadata(workspaceId);
    }
}
