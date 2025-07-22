package com.comet.opik.domain;

import com.comet.opik.api.WorkspaceConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

@ImplementedBy(WorkspaceConfigurationServiceImpl.class)
public interface WorkspaceConfigurationService {

    Mono<Void> upsertConfiguration(WorkspaceConfiguration configuration);

    Mono<WorkspaceConfiguration> getConfiguration();

    Mono<Void> deleteConfiguration();
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class WorkspaceConfigurationServiceImpl implements WorkspaceConfigurationService {

    private final @NonNull WorkspaceConfigurationDAO workspaceConfigurationDAO;

    @Override
    public Mono<Void> upsertConfiguration(@NonNull WorkspaceConfiguration configuration) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return workspaceConfigurationDAO.upsertConfiguration(workspaceId, configuration)
                    .then();
        });
    }

    @Override
    public Mono<WorkspaceConfiguration> getConfiguration() {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return workspaceConfigurationDAO.getConfiguration(workspaceId)
                    .doOnSuccess(config -> log.info("Found workspace configuration for workspace '{}'", workspaceId));
        });
    }

    @Override
    public Mono<Void> deleteConfiguration() {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return workspaceConfigurationDAO.deleteConfiguration(workspaceId)
                    .doOnSuccess(deletedCount -> {
                        if (deletedCount > 0) {
                            log.info("Deleted workspace configuration for workspace '{}'", workspaceId);
                        } else {
                            log.info("No workspace configuration found to delete for workspace '{}'", workspaceId);
                        }
                    })
                    .then();
        });
    }
}
