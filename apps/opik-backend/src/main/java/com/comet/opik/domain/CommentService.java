package com.comet.opik.domain;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.domain.threads.TraceThreadDAO;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;

import java.util.Set;
import java.util.UUID;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(CommentServiceImpl.class)
public interface CommentService {

    Mono<UUID> create(UUID entityId, Comment comment, CommentDAO.EntityType entityType);

    Mono<Comment> get(UUID entityId, UUID commentId);

    Mono<Void> update(UUID commentId, Comment comment);

    Mono<Void> delete(BatchDelete batchDelete);

    Mono<Long> deleteByEntityIds(CommentDAO.EntityType entityType, Set<UUID> entityIds);

    Mono<Long> createBatchForTraces(Set<UUID> traceIds, String text);

    Mono<Long> createBatchForSpans(Set<UUID> spanIds, String text);
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
    private final @NonNull TransactionTemplateAsync transactionTemplateAsync;

    @Override
    public Mono<UUID> create(@NonNull UUID entityId, @NonNull Comment comment, CommentDAO.EntityType entityType) {
        UUID id = idGenerator.generateId();
        var monoProjectId = switch (entityType) {
            case TRACE -> traceDAO.getProjectIdFromTrace(entityId);
            case SPAN -> spanDAO.getProjectIdFromSpan(entityId);
            case THREAD -> traceThreadDAO.getProjectIdFromThread(entityId);
        };

        return monoProjectId
                .switchIfEmpty(Mono.error(failWithNotFound(entityType.getType(), entityId)))
                .flatMap(projectId -> commentDAO.addComment(id, entityId, entityType, projectId,
                        comment))
                .map(__ -> id);
    }

    @Override
    public Mono<Comment> get(@NonNull UUID entityId, @NonNull UUID commentId) {
        return commentDAO.findById(entityId, commentId)
                .switchIfEmpty(Mono.error(failWithNotFound("Comment", commentId)));
    }

    @Override
    public Mono<Void> update(@NonNull UUID commentId, @NonNull Comment comment) {
        return commentDAO.findById(null, commentId)
                .switchIfEmpty(Mono.error(failWithNotFound("Comment", commentId)))
                .then(Mono.defer(() -> commentDAO.updateComment(commentId, comment)));
    }

    @Override
    public Mono<Void> delete(@NonNull BatchDelete batchDelete) {
        return commentDAO.deleteByIds(batchDelete.ids()).then();
    }

    @Override
    public Mono<Long> deleteByEntityIds(CommentDAO.EntityType entityType, Set<UUID> entityIds) {
        if (entityIds.isEmpty()) {
            return Mono.just(0L);
        }
        return commentDAO.deleteByEntityIds(entityType, entityIds);
    }

    @Override
    public Mono<Long> createBatchForTraces(@NonNull Set<UUID> traceIds, @NonNull String text) {
        if (traceIds.isEmpty()) {
            return Mono.just(0L);
        }

        return transactionTemplateAsync
                .nonTransaction(connection -> traceDAO.getTraceWorkspace(traceIds, connection))
                .switchIfEmpty(Mono.just(java.util.List.of()))
                .flatMap(list -> {
                    java.util.Set<String> workspaces = list.stream().map(WorkspaceAndResourceId::workspaceId).collect(java.util.stream.Collectors.toSet());
                    if (workspaces.size() > 1) {
                        return Mono.error(new IllegalArgumentException("All entities must belong to the same workspace"));
                    }
                    java.util.Set<UUID> ids = list.stream().map(WorkspaceAndResourceId::resourceId).collect(java.util.stream.Collectors.toSet());
                    if (ids.isEmpty()) {
                        return Mono.just(0L);
                    }
                    return traceDAO.getProjectIdFromTrace(ids.iterator().next())
                            .switchIfEmpty(Mono.error(failWithNotFound("Trace", ids.iterator().next())))
                            .flatMap(projectId -> commentDAO.addCommentsBatch(CommentDAO.EntityType.TRACE, ids, projectId,
                                    Comment.builder().text(text).build()));
                });
    }

    @Override
    public Mono<Long> createBatchForSpans(@NonNull Set<UUID> spanIds, @NonNull String text) {
        if (spanIds.isEmpty()) {
            return Mono.just(0L);
        }

        return spanDAO.getSpanWorkspace(spanIds)
                .switchIfEmpty(Mono.just(java.util.List.of()))
                .flatMap(list -> {
                    java.util.Set<String> workspaces = list.stream().map(WorkspaceAndResourceId::workspaceId).collect(java.util.stream.Collectors.toSet());
                    if (workspaces.size() > 1) {
                        return Mono.error(new IllegalArgumentException("All entities must belong to the same workspace"));
                    }
                    java.util.Set<UUID> ids = list.stream().map(WorkspaceAndResourceId::resourceId).collect(java.util.stream.Collectors.toSet());
                    if (ids.isEmpty()) {
                        return Mono.just(0L);
                    }
                    return spanDAO.getProjectIdFromSpan(ids.iterator().next())
                            .switchIfEmpty(Mono.error(failWithNotFound("Span", ids.iterator().next())))
                            .flatMap(projectId -> commentDAO.addCommentsBatch(CommentDAO.EntityType.SPAN, ids, projectId,
                                    Comment.builder().text(text).build()));
                });
    }
}
