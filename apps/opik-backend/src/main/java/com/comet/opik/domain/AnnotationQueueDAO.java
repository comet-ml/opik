package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;

@ImplementedBy(AnnotationQueueDAOImpl.class)
public interface AnnotationQueueDAO {

    Mono<Void> create(AnnotationQueue annotationQueue);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueDAOImpl implements AnnotationQueueDAO {

    private static final String INSERT_STATEMENT = """
            INSERT INTO annotation_queues (
                id,
                workspace_id,
                project_id,
                name,
                description,
                instructions,
                scope,
                comments_enabled,
                feedback_definitions,
                created_by,
                last_updated_by
            ) VALUES (
                :id,
                :workspace_id,
                :project_id,
                :name,
                :description,
                :instructions,
                :scope,
                :comments_enabled,
                :feedback_definitions,
                :user_name,
                :user_name
            )
            """;

    private final @NonNull ConnectionFactory connectionFactory;
    private final @NonNull @Named("Database Analytics Database Name") String databaseName;

    @Override
    public Mono<Void> create(@NonNull AnnotationQueue annotationQueue) {
        log.info("Creating annotation queue with id '{}', name '{}', project '{}'",
                annotationQueue.id(), annotationQueue.name(), annotationQueue.projectId());

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> create(annotationQueue, connection))
                .then()
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Created annotation queue with id '{}'", annotationQueue.id());
                    }
                });
    }

    private Mono<? extends Result> create(AnnotationQueue annotationQueue, Connection connection) {
        Statement statement = connection.createStatement(INSERT_STATEMENT)
                .bind("id", annotationQueue.id())
                .bind("project_id", annotationQueue.projectId())
                .bind("name", annotationQueue.name())
                .bind("description", annotationQueue.description() != null ? annotationQueue.description() : "")
                .bind("instructions", annotationQueue.instructions() != null ? annotationQueue.instructions() : "")
                .bind("scope", annotationQueue.scope().getValue())
                .bind("comments_enabled",
                        annotationQueue.commentsEnabled() != null ? annotationQueue.commentsEnabled() : true);

        // Handle array binding for feedback_definitions
        if (annotationQueue.feedbackDefinitions() != null && !annotationQueue.feedbackDefinitions().isEmpty()) {
            statement.bind("feedback_definitions", annotationQueue.feedbackDefinitions().toArray(UUID[]::new));
        } else {
            statement.bind("feedback_definitions", new UUID[0]);
        }

        return makeMonoContextAware(bindUserNameAndWorkspaceContext(statement));
    }
}
