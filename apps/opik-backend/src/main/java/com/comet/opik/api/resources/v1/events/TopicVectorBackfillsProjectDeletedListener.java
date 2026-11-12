package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.topics.TopicVectorBackfillService;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

/**
 * Deletes the {@code topic_vector_backfills} rows of deleted projects, within the event's workspace. Their vectors
 * follow through the TracesDeleted cascade of each trace delete. Runs with topics off too, so no row outlives its
 * project.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicVectorBackfillsProjectDeletedListener {

    private final @NonNull TopicVectorBackfillService backfillService;

    @Subscribe
    public void onProjectsDeleted(@NonNull ProjectsDeleted event) {
        try {
            int deleted = backfillService.deleteByProjects(event.workspaceId(), event.projectIds());
            if (deleted > 0) {
                log.info("Deleted the topic vectors backfill rows of deleted projects, workspace '{}', rows '{}'",
                        event.workspaceId(), deleted);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to delete the topic vectors backfill rows of deleted projects, workspace '{}', "
                    + "project count '{}'", event.workspaceId(), event.projectIds().size(), exception);
        }
    }
}
