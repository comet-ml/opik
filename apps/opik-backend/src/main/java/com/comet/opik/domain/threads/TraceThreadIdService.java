package com.comet.opik.domain.threads;

import com.comet.opik.domain.EntityConstraintHandler;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.UUID;
import javassist.NotFoundException;

@ImplementedBy(TraceThreadIdServiceImpl.class)
interface TraceThreadIdService {

    Mono<TraceThreadIdModel> getOrCreateTraceThreadId(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String threadId);

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceThreadIdServiceImpl implements TraceThreadIdService {

    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    @Cacheable(name = "GET_THREAD_ID", key = "$workspaceId +'-'+ $projectId +'-'+ $threadId", returnType = UUID.class)
    public Mono<TraceThreadIdModel> getOrCreateTraceThreadId(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String threadId) {
        Preconditions.checkArgument(!StringUtils.isBlank(workspaceId), "Workspace ID cannot be blank");
        Preconditions.checkArgument(!StringUtils.isBlank(threadId), "Thread ID cannot be blank");

        return Mono.fromCallable(() -> projectService.get(projectId, workspaceId))
                .flatMap(project -> getTraceThreadId(threadId, project.id())
                        .switchIfEmpty(createThread(threadId, projectId)))
                .switchIfEmpty(Mono.error(new NotFoundException("Project not found: " + projectId)));
    }

    private Mono<TraceThreadIdModel> getTraceThreadId(String threadId, UUID projectId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(TransactionTemplateAsync.READ_ONLY,
                handle -> handle.attach(TraceThreadIdDAO.class).findByProjectIdAndThreadId(projectId, threadId)));
    }

    private Mono<TraceThreadIdModel> createThread(String threadId, UUID projectId) {
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {

            var threadModel = TraceThreadIdModel.builder()
                    .id(idGenerator.generateId())
                    .projectId(projectId)
                    .threadId(threadId)
                    .createdBy(context.get(RequestContext.USER_NAME))
                    .createdAt(Instant.now())
                    .build();

            log.info("Creating trace thread with id '{}' and thread id '{}'", threadModel.id(), threadModel.threadId());

            return transactionTemplate.inTransaction(TransactionTemplateAsync.WRITE, handle -> {
                var traceThreadDAO = handle.attach(TraceThreadIdDAO.class);
                return EntityConstraintHandler.handle(() -> save(traceThreadDAO, threadModel))
                        .onErrorDo(() -> {
                            log.error(
                                    "Failed to create thread trying to retrieve it with project id '{}' and thread id '{}'",
                                    projectId, threadModel.threadId());
                            return traceThreadDAO.findByProjectIdAndThreadId(projectId, threadId);
                        });
            });

        }));
    }

    private TraceThreadIdModel save(TraceThreadIdDAO traceThreadDAO, TraceThreadIdModel threadModel) {
        traceThreadDAO.save(threadModel);
        log.info("Created trace thread with id '{}' and thread id '{}'", threadModel.id(), threadModel.threadId());
        return threadModel;
    }

}
