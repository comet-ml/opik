package com.comet.opik.domain;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.events.CommentsCreated;
import com.comet.opik.api.events.CommentsDeleted;
import com.comet.opik.api.events.CommentsUpdated;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Mono;

import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(CommentServiceImpl.class)
public interface CommentService {

    Mono<UUID> create(UUID entityId, Comment comment, CommentDAO.EntityType entityType);

    Mono<Comment> get(UUID entityId, UUID commentId);

    Mono<Void> update(UUID commentId, Comment comment);

    Mono<Void> delete(BatchDelete batchDelete);

    Mono<Long> deleteByEntityIds(CommentDAO.EntityType entityType, Set<UUID> entityIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CommentServiceImpl implements CommentService {

    private final @NonNull CommentDAO commentDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull SpanDAO spanDAO;
    private final @NonNull TraceThreadDAO traceThreadDAO;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull EventBus eventBus;

    @Override
    public Mono<UUID> create(@NonNull UUID entityId, @NonNull Comment comment, CommentDAO.EntityType entityType) {
        UUID id = idGenerator.generateId();
        var monoProjectId = switch (entityType) {
            case TRACE -> traceDAO.getProjectIdFromTrace(entityId);
            case SPAN -> spanDAO.getProjectIdFromSpan(entityId);
            case THREAD -> traceThreadDAO.getProjectIdFromThread(entityId);
        };

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return monoProjectId
                    .switchIfEmpty(Mono.error(failWithNotFound(entityType.getType(), entityId)))
                    .flatMap(projectId -> commentDAO.addComment(id, entityId, entityType, projectId, comment))
                    .doOnSuccess(__ -> eventBus.post(
                            new CommentsCreated(Set.of(entityId), toEntityType(entityType), workspaceId, userName)))
                    .map(__ -> id);
        });
    }

    @Override
    public Mono<Comment> get(@NonNull UUID entityId, @NonNull UUID commentId) {
        return commentDAO.findById(entityId, commentId)
                .switchIfEmpty(Mono.error(failWithNotFound("Comment", commentId)));
    }

    @Override
    public Mono<Void> update(@NonNull UUID commentId, @NonNull Comment comment) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return commentDAO.getEntityRefsByCommentIds(Set.of(commentId))
                    .collectList()
                    .flatMap(refs -> commentDAO.findById(null, commentId)
                            .switchIfEmpty(Mono.error(failWithNotFound("Comment", commentId)))
                            .then(Mono.defer(() -> commentDAO.updateComment(commentId, comment)))
                            .doOnSuccess(__ -> {
                                if (CollectionUtils.isNotEmpty(refs)) {
                                    var ref = refs.getFirst();
                                    eventBus.post(new CommentsUpdated(
                                            Set.of(ref.entityId()), toEntityType(ref.entityType()),
                                            workspaceId, userName));
                                }
                            }));
        });
    }

    @Override
    public Mono<Void> delete(@NonNull BatchDelete batchDelete) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return commentDAO.getEntityRefsByCommentIds(batchDelete.ids())
                    .collectList()
                    .flatMap(refs -> {
                        Set<UUID> foundIds = refs.stream()
                                .map(CommentEntityRef::commentId)
                                .collect(Collectors.toSet());
                        Set<UUID> idsToDelete = CollectionUtils.isNotEmpty(foundIds) ? foundIds : batchDelete.ids();

                        return commentDAO.deleteByIds(idsToDelete)
                                .doOnSuccess(__ -> {
                                    if (CollectionUtils.isNotEmpty(refs)) {
                                        refs.stream()
                                                .collect(Collectors.groupingBy(
                                                        CommentEntityRef::entityType,
                                                        Collectors.mapping(CommentEntityRef::entityId,
                                                                Collectors.toSet())))
                                                .forEach((entityType, entityIds) -> eventBus.post(
                                                        new CommentsDeleted(entityIds, toEntityType(entityType),
                                                                workspaceId, userName)));
                                    }
                                });
                    })
                    .then();
        });
    }

    @Override
    public Mono<Long> deleteByEntityIds(CommentDAO.EntityType entityType, Set<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Mono.just(0L);
        }
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return commentDAO.deleteByEntityIds(entityType, entityIds)
                    .doOnSuccess(__ -> eventBus.post(
                            new CommentsDeleted(entityIds, toEntityType(entityType), workspaceId, userName)));
        });
    }

    private EntityType toEntityType(CommentDAO.EntityType commentEntityType) {
        return switch (commentEntityType) {
            case TRACE -> EntityType.TRACE;
            case SPAN -> EntityType.SPAN;
            case THREAD -> EntityType.THREAD;
        };
    }
}
