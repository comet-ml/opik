package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;

@ImplementedBy(WorkspaceConfigurationServiceImpl.class)
public interface WorkspaceConfigurationService {

    WorkspaceConfiguration upsertConfiguration(WorkspaceConfiguration configuration);

    Optional<WorkspaceConfiguration> getConfiguration();

    void deleteConfiguration();
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class WorkspaceConfigurationServiceImpl implements WorkspaceConfigurationService {

    private final @NonNull WorkspaceConfigurationDAO workspaceConfigurationDAO;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public WorkspaceConfiguration upsertConfiguration(@NonNull WorkspaceConfiguration configuration) {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Upserting workspace configuration for workspace '{}'", workspaceId);

        return workspaceConfigurationDAO.upsertConfiguration(workspaceId, configuration);
    }

    @Override
    public Optional<WorkspaceConfiguration> getConfiguration() {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Getting workspace configuration for workspace '{}'", workspaceId);

        return workspaceConfigurationDAO.getConfiguration(workspaceId);
    }

    @Override
    public void deleteConfiguration() {
        String workspaceId = requestContext.get().getWorkspaceId();
        log.info("Deleting workspace configuration for workspace '{}'", workspaceId);

        // Verify configuration exists
        getConfiguration().orElseThrow(() -> {
            log.warn("Workspace configuration not found for workspace '{}'", workspaceId);
            return new NotFoundException("Workspace configuration not found");
        });

        workspaceConfigurationDAO.deleteConfiguration(workspaceId);
    }
}