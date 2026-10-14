package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.Set;
import java.util.UUID;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class ProjectDeletedListener {

    private final @NonNull TraceService traceService;

    /**
     * Handles the ProjectsDeleted event by asynchronously deleting the projects' traces. Spans, feedback scores,
     * comments and attachments follow through the TracesDeleted cascade of each trace delete.
     *
     * @param event the ProjectsDeleted event containing the project IDs that were deleted
     */
    @Subscribe
    public void onProjectsDeleted(@NonNull ProjectsDeleted event) {
        Set<UUID> projectIds = event.projectIds();
        String workspaceId = event.workspaceId();
        String userName = event.userName();

        log.info("Received ProjectsDeleted event, deleting the projects' traces, project count '{}', workspace '{}'",
                projectIds.size(), workspaceId);

        Flux.fromIterable(projectIds)
                .concatMap(projectId -> traceService.deleteByProjectId(projectId)
                        .onErrorResume(error -> {
                            log.error("Failed to delete the project's traces, project id '{}', workspace '{}'",
                                    projectId, workspaceId, error);
                            return Mono.empty();
                        }))
                .then()
                .doOnSuccess(__ -> log.info(
                        "Finished processing ProjectsDeleted event, project count '{}', workspace '{}'",
                        projectIds.size(), workspaceId))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .subscribe();
    }
}
