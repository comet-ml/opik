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
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(TraceThreadIdServiceImpl.class)
interface TraceThreadIdService {

    Mono<TraceThreadIdModel> getOrCreateTraceThreadId(String workspaceId, UUID projectId, String threadId,
            Instant timestamp);

    Mono<UUID> getThreadModelId(String workspaceId, UUID projectId, String threadId);

    Mono<TraceThreadIdModel> getTraceThreadIdByThreadModelId(UUID threadModelId);

    Mono<Map<UUID, String>> getTraceThreadIdsByThreadModelIds(List<UUID> threadModelIds);

    Mono<List<TraceThreadIdModel>> getTraceThreadIdModelsByThreadModelIds(List<UUID> threadModelIds);

}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class TraceThreadIdServiceImpl implements TraceThreadIdService {

    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate transactionTemplate;

    @Override
    @Cacheable(name = "GET_OR_CREATE_TRACE_THREAD_ID", key = "$workspaceId +'-'+ $projectId +'-'+ $threadId", returnType = TraceThreadIdModel.class)
    public Mono<TraceThreadIdModel> getOrCreateTraceThreadId(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String threadId, Instant timestamp) {
        Preconditions.checkArgument(!StringUtils.isBlank(workspaceId), "Workspace ID cannot be blank");
        Preconditions.checkArgument(!StringUtils.isBlank(threadId), "Thread ID cannot be blank");

        return Mono.fromCallable(() -> projectService.get(projectId, workspaceId))
                .flatMap(project -> getTraceThreadId(threadId, project.id())
                        .switchIfEmpty(createThread(threadId, projectId, timestamp)))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(new NotFoundException("Project not found: " + projectId)));
    }

    @Cacheable(name = "GET_TRACE_THREAD_ID", key = "$workspaceId +'-'+ $projectId +'-'+ $threadId", returnType = UUID.class)
    public Mono<UUID> getThreadModelId(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String threadId) {
        Preconditions.checkArgument(!StringUtils.isBlank(workspaceId), "Workspace ID cannot be blank");
        Preconditions.checkArgument(!StringUtils.isBlank(threadId), "Thread ID cannot be blank");

        return Mono.fromCallable(() -> projectService.get(projectId, workspaceId))
                .flatMap(project -> getTraceThreadId(threadId, project.id())
                        .map(TraceThreadIdModel::id))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(NotFoundException.class, throwable -> {
                    log.warn("Thread ID not found for project '{}' and thread ID '{}'", projectId, threadId, throwable);
                    return Mono.empty();
                });

    }

    @Override
    @Cacheable(name = "GET_TRACE_THREAD_ID_BY_THREAD_MODEL_ID", key = "$threadModelId", returnType = TraceThreadIdModel.class)
    public Mono<TraceThreadIdModel> getTraceThreadIdByThreadModelId(@NonNull UUID threadModelId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(TransactionTemplateAsync.READ_ONLY,
                handle -> handle.attach(TraceThreadIdDAO.class).findByThreadModelId(threadModelId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<Map<UUID, String>> getTraceThreadIdsByThreadModelIds(@NonNull List<UUID> threadModelIds) {
        Preconditions.checkArgument(!threadModelIds.isEmpty(),
                "Thread model IDs cannot be null or empty");

        return Mono.fromCallable(() -> {
            var threadModels = transactionTemplate.inTransaction(TransactionTemplateAsync.READ_ONLY,
                    handle -> handle.attach(TraceThreadIdDAO.class).findByThreadModelIds(threadModelIds));

            log.info("Fetched '{}' thread models for '{}' thread model IDs", threadModels.size(),
                    threadModelIds.size());

            return threadModels.stream()
                    .collect(Collectors.toMap(
                            TraceThreadIdModel::id,
                            TraceThreadIdModel::threadId));
        }).subscribeOn(Schedulers.boundedElastic());
    }

    @Override
    public Mono<List<TraceThreadIdModel>> getTraceThreadIdModelsByThreadModelIds(@NonNull List<UUID> threadModelIds) {
        Preconditions.checkArgument(!threadModelIds.isEmpty(),
                "Thread model IDs cannot be null or empty");

        return Mono.fromCallable(() -> {
            var threadModels = transactionTemplate.inTransaction(TransactionTemplateAsync.READ_ONLY,
                    handle -> handle.attach(TraceThreadIdDAO.class).findByThreadModelIds(threadModelIds));

            log.info("Fetched '{}' thread ID models for '{}' thread model IDs", threadModels.size(),
                    threadModelIds.size());

            return threadModels;
        }).subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<TraceThreadIdModel> getTraceThreadId(String threadId, UUID projectId) {
        return Mono.fromCallable(() -> transactionTemplate.inTransaction(TransactionTemplateAsync.READ_ONLY,
                handle -> handle.attach(TraceThreadIdDAO.class).findByProjectIdAndThreadId(projectId, threadId)));
    }

    private Mono<TraceThreadIdModel> createThread(String threadId, UUID projectId, Instant timestamp) {
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {

            UUID threadModelId = generateThreadModelId(timestamp);

            var threadModel = TraceThreadIdModel.builder()
                    .id(threadModelId)
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

    private UUID generateThreadModelId(Instant timestamp) {
        // Use the provided timestamp to generate a UUIDv7 if available,
        // otherwise use the current time
        return timestamp != null ? idGenerator.generateId(timestamp) : idGenerator.generateId();
    }

}
