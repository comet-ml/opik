package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.google.inject.ImplementedBy;
import io.r2dbc.spi.Connection;
import io.r2dbc.spi.ConnectionFactory;
import io.r2dbc.spi.Result;
import io.r2dbc.spi.Statement;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.stringtemplate.v4.ST;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.domain.AsyncContextUtils.bindUserNameAndWorkspaceContext;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.TemplateUtils.getQueryItemPlaceHolder;

@ImplementedBy(AnnotationQueueDAOImpl.class)
public interface AnnotationQueueDAO {

    Mono<Void> createBatch(List<AnnotationQueue> annotationQueues);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AnnotationQueueDAOImpl implements AnnotationQueueDAO {

    private static final String BATCH_INSERT = """
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
            ) VALUES
                <items:{item |
                    (
                        :id<item.index>,
                        :workspace_id,
                        :project_id<item.index>,
                        :name<item.index>,
                        :description<item.index>,
                        :instructions<item.index>,
                        :scope<item.index>,
                        :comments_enabled<item.index>,
                        :feedback_definitions<item.index>,
                        :user_name,
                        :user_name
                    )
                    <if(item.hasNext)>,<endif>
                }>
            ;
            """;

    private final @NonNull ConnectionFactory connectionFactory;

    @Override
    public Mono<Void> createBatch(@NonNull List<AnnotationQueue> annotationQueues) {
        log.info("Creating annotation queue batch with '{}' items", annotationQueues.size());

        return Mono.from(connectionFactory.create())
                .flatMap(connection -> createBatch(annotationQueues, connection))
                .then()
                .doFinally(signalType -> {
                    if (signalType == SignalType.ON_COMPLETE) {
                        log.info("Created annotation queue batch with '{}' items", annotationQueues.size());
                    }
                });
    }

    private Mono<? extends Result> createBatch(List<AnnotationQueue> annotationQueues, Connection connection) {
        var queryItems = getQueryItemPlaceHolder(annotationQueues.size());
        var template = new ST(BATCH_INSERT).add("items", queryItems);

        Statement statement = connection.createStatement(template.render());

        int i = 0;
        for (AnnotationQueue annotationQueue : annotationQueues) {
            statement.bind("id" + i, annotationQueue.id())
                    .bind("project_id" + i, annotationQueue.projectId())
                    .bind("name" + i, annotationQueue.name())
                    .bind("description" + i, annotationQueue.description() != null ? annotationQueue.description() : "")
                    .bind("instructions" + i,
                            annotationQueue.instructions() != null ? annotationQueue.instructions() : "")
                    .bind("scope" + i, annotationQueue.scope().getValue())
                    .bind("comments_enabled" + i, annotationQueue.commentsEnabled())
                    .bind("feedback_definitions" + i,
                            annotationQueue.feedbackDefinitions() != null
                                    ? annotationQueue.feedbackDefinitions().toArray(UUID[]::new)
                                    : new UUID[]{});
            i++;
        }

        return makeMonoContextAware(
                bindUserNameAndWorkspaceContext(statement));
    }
}
