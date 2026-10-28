package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.ProjectsDeleted;
import com.comet.opik.domain.topics.TopicDAO;
import com.comet.opik.domain.topics.TopicRunDAO;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Deletes the MySQL side of the deleted projects' topics, within the event's workspace: the topic lists, prototypes, states and runs. Names,
 * definitions and prototypes come from the projects' traces, so they go with the project. The trace_topics rows follow
 * through the TracesDeleted cascade of each trace delete.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class TopicsProjectDeletedListener {

    private final @NonNull TransactionTemplate template;

    @Subscribe
    public void onProjectsDeleted(@NonNull ProjectsDeleted event) {
        try {
            template.inTransaction(WRITE, handle -> {
                var topicDAO = handle.attach(TopicDAO.class);
                int topics = topicDAO.deleteTopicsByProjects(event.workspaceId(), event.projectIds());
                int prototypes = topicDAO.deletePrototypesByProjects(event.workspaceId(), event.projectIds());
                int states = topicDAO.deleteStatesByProjects(event.workspaceId(), event.projectIds());
                int runs = handle.attach(TopicRunDAO.class).deleteByProjects(event.workspaceId(), event.projectIds());
                if (topics + prototypes + states + runs > 0) {
                    log.info("Deleted the topics of deleted projects, workspace '{}', project count '{}', topics "
                            + "'{}', prototypes '{}', states '{}', runs '{}'", event.workspaceId(),
                            event.projectIds().size(), topics, prototypes, states, runs);
                }
                return null;
            });
        } catch (RuntimeException exception) {
            log.error("Failed to delete the topics of deleted projects, workspace '{}', project count '{}'",
                    event.workspaceId(), event.projectIds().size(), exception);
        }
    }
}
