package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.topics.TopicDAO;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Deletes the deleted projects' topics within the event's workspace: names and definitions come from the projects'
 * traces, and each topic's prototype vectors are on its row. Their {@code topic_projects} rows go with
 * {@link TopicVectorBackfillsProjectDeletedListener}, and the trace_topics rows through the TracesDeleted cascade of
 * each trace delete. What a failed delete leaves, the first-build job deletes on its next run.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsProjectDeletedListener {

    private final @NonNull TransactionTemplate template;

    @Subscribe
    public void onProjectsDeleted(@NonNull ProjectsDeleted event) {
        try {
            int topics = template.inTransaction(WRITE, handle -> handle.attach(TopicDAO.class)
                    .deleteTopicsByProjects(event.workspaceId(), event.projectIds()));
            if (topics > 0) {
                log.info("Deleted the topics of deleted projects, workspace '{}', project count '{}', topics '{}'",
                        event.workspaceId(), event.projectIds().size(), topics);
            }
        } catch (RuntimeException exception) {
            log.error("Failed to delete the topics of deleted projects, workspace '{}', project count '{}'",
                    event.workspaceId(), event.projectIds().size(), exception);
        }
    }
}
