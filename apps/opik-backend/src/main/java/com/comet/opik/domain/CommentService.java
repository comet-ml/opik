package com.comet.opik.domain;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@ImplementedBy(CommentServiceImpl.class)
public interface CommentService {

    Mono<UUID> create(UUID traceId, Comment comment);

    Mono<Comment> get(UUID traceId, UUID commentId);

    Mono<Void> update(UUID commentId, Comment comment);

    Mono<Void> delete(BatchDelete batchDelete);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class CommentServiceImpl implements CommentService {

    private final @NonNull CommentDAO commentDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public Mono<UUID> create(@NonNull UUID traceId, @NonNull Comment comment) {
        UUID id = idGenerator.generateId();
        return traceDAO.getProjectIdFromTrace(traceId)
                .switchIfEmpty(Mono.error(failWithNotFound("Trace", traceId)))
                .flatMap(projectId -> commentDAO.addComment(id, traceId, projectId,
                        comment))
                .map(__ -> id);
    }

    @Override
    public Mono<Comment> get(@NonNull UUID traceId, @NonNull UUID commentId) {
        return commentDAO.findById(traceId, commentId)
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
        return commentDAO.deleteByIds(batchDelete.ids());
    }
}
