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
     * Handles the ProjectsDeleted event by asynchronously deleting the project's traces. Deleting a project removes
     * only the MySQL row; this listener removes the project's traces and spans from ClickHouse through
     * {@link TraceService#deleteByProjectId(UUID)}, so the deletion-events bridge capture and the TracesDeleted
     * cascade (spans, feedback scores, comments, attachments) run exactly as for user-initiated trace deletes.
     *
     * @param event the ProjectsDeleted event containing the project IDs that were deleted
     */
    @Subscribe
    public void onProjectsDeleted(@NonNull ProjectsDeleted event) {
        Set<UUID> projectIds = event.projectIds();
        String workspaceId = event.workspaceId();
        String userName = event.userName();

        log.info(
                "Received ProjectsDeleted event for workspace: '{}', project count: '{}'. Processing trace deletion",
                workspaceId, projectIds.size());

        processProjectDeletion(projectIds)
                .doOnError(error -> {
                    log.error(
                            "Failed to process ProjectsDeleted event for workspace: '{}', project count: '{}', error: '{}'",
                            workspaceId, projectIds.size(), error.getMessage());
                    log.error("Error processing project trace deletion", error);
                })
                .doOnSuccess(__ -> log.info(
                        "Successfully processed ProjectsDeleted event for workspace: '{}', project count: '{}'",
                        workspaceId, projectIds.size()))
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)
                        .put(RequestContext.USER_NAME, userName))
                .subscribe();
    }

    /**
     * Deletes the traces of each deleted project, one project at a time. A failure on one project is logged and
     * contained so the remaining projects are still processed.
     */
    private Mono<Void> processProjectDeletion(Set<UUID> projectIds) {
        log.info("Starting deletion of traces for projects, count '{}'", projectIds.size());

        return Flux.fromIterable(projectIds)
                .concatMap(projectId -> traceService.deleteByProjectId(projectId)
                        .doOnError(error -> log.error(
                                "Failed to delete traces for project id: '{}', error: '{}'", projectId,
                                error.getMessage()))
                        .onErrorResume(error -> Mono.empty()))
                .then();
    }
}
