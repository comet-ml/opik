package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.topics.TopicDataCleanupService;
import com.comet.opik.domain.topics.TopicVectorBackfillService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

/**
 * Deletes the Topics state of deleted projects, within the event's workspace: their {@code topic_projects} rows, run
 * included, and their topics, with each topic's prototype vectors on its row. A run in flight then finds no row and
 * writes nothing. Their vectors and trace topics follow through the TracesDeleted cascade of each trace delete. Runs
 * with topics off too. A {@code topic_projects} row this misses, from a failed delete or a tick that wrote it again
 * before the project's traces were gone, is removed by the tick's next discovery of the workspace's projects
 * ({@link TopicVectorBackfillService#discovered}); topics it misses, by the daily sweep ({@code TopicsSweepJob}).
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicProjectsDeletedListener {

    private final @NonNull TopicVectorBackfillService backfillService;
    private final @NonNull TopicDataCleanupService cleanupService;

    @Subscribe
    public void onProjectsDeleted(@NonNull ProjectsDeleted event) {
        try {
            int deleted = backfillService.deleteByProjects(event.workspaceId(), event.projectIds());
            if (deleted > 0) {
                log.info("Deleted the topic_projects rows of deleted projects, workspace '{}', rows '{}'",
                        event.workspaceId(), deleted);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to delete the topic_projects rows of deleted projects, workspace '{}', "
                    + "project count '{}'", event.workspaceId(), event.projectIds().size(), exception);
        }
        cleanupService.deleteTopicsOfProjects(event.projectIds())
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, event.workspaceId())
                        .put(RequestContext.USER_NAME, event.userName()))
                .subscribe(
                        topics -> {
                            if (topics > 0) {
                                log.info("Deleted the topics of deleted projects, workspace '{}', topics '{}'",
                                        event.workspaceId(), topics);
                            }
                        },
                        error -> log.error("Failed to delete the topics of deleted projects, workspace '{}', "
                                + "project count '{}'", event.workspaceId(), event.projectIds().size(), error));
    }
}
