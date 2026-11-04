package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.TraceService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

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
        log.info("Received ProjectsDeleted event, deleting the projects' traces, project count '{}', workspace '{}'",
                event.projectIds().size(), event.workspaceId());

        traceService.deleteByProjectIds(event.projectIds())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe();
    }
}
