package com.comet.opik.domain.threads;

import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.domain.EntityConstraintHandler;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import io.dropwizard.jersey.errors.ErrorMessage;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@ImplementedBy(TraceThreadIdServiceImpl.class)
interface TraceThreadIdService {

    Mono<TraceThreadIdModel> getOrCreateTraceThreadId(String workspaceId, UUID projectId, String threadId,
            Instant timestamp);

    Mono<List<TraceThreadIdModel>> getOrCreateTraceThreadIds(String workspaceId, UUID projectId,
            Map<String, Instant> threadIdToTimestamp);

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
        Preconditions.checkArgument(StringUtils.isNotBlank(workspaceId), "Workspace ID cannot be blank");
        Preconditions.checkArgument(StringUtils.isNotBlank(threadId), "Thread ID cannot be blank");

        return Mono.fromCallable(() -> projectService.get(projectId, workspaceId))
                .flatMap(project -> getTraceThreadId(threadId, project.id())
                        .switchIfEmpty(createThread(threadId, projectId, timestamp)))
                .subscribeOn(Schedulers.boundedElastic())
                .switchIfEmpty(Mono.error(new NotFoundException("Project not found: " + projectId)));
    }

    /**
     * Bulk get-or-create of trace-thread-model ids for a project.
     * <p>
     * Reads the existing {@code (project_id, thread_id)} rows, then inserts only the missing ones. A
     * conflict can arise when a concurrent caller creates the same {@code (project_id, thread_id)}
     * between our read and our insert — the unique constraint then rejects our insert. In that case
     * {@code withRetry} re-runs the whole read-then-insert in a fresh transaction (so a fresh snapshot
     * under REPEATABLE READ): the retry sees the now-existing row, drops it from the missing set, and
     * converges. The unique key guarantees a single canonical id per thread; the retry only covers the
     * read→write race.
     */
    @Override
    public Mono<List<TraceThreadIdModel>> getOrCreateTraceThreadIds(String workspaceId,
            @NonNull UUID projectId, Map<String, Instant> threadIdToTimestamp) {
        Preconditions.checkArgument(StringUtils.isNotBlank(workspaceId), "Workspace ID cannot be blank");

        if (MapUtils.isEmpty(threadIdToTimestamp)) {
            return Mono.just(List.of());
        }

        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
            List<String> threadIds = List.copyOf(threadIdToTimestamp.keySet());
            String userName = context.get(RequestContext.USER_NAME);

            // Each attempt runs in its own transaction: a constraint-violation retry must re-read a
            // fresh snapshot, and under REPEATABLE READ re-reading within the same transaction would
            // reuse the original snapshot and never converge.
            return EntityConstraintHandler.<List<TraceThreadIdModel>>handle(
                    () -> transactionTemplate.inTransaction(TransactionTemplateAsync.WRITE, handle -> {
                        var dao = handle.attach(TraceThreadIdDAO.class);

                        List<TraceThreadIdModel> existing = dao.findByProjectIdAndThreadIds(projectId, threadIds);
                        Set<String> existingThreadIds = existing.stream()
                                .map(TraceThreadIdModel::threadId)
                                .collect(Collectors.toSet());

                        List<String> missingThreadIds = threadIds.stream()
                                .filter(threadId -> !existingThreadIds.contains(threadId))
                                .toList();

                        if (missingThreadIds.isEmpty()) {
                            // Common case: every thread already has an id — no write needed.
                            return existing;
                        }

                        // We already know exactly which threads are missing, so we own their ids.
                        List<TraceThreadIdModel> created = missingThreadIds.stream()
                                .map(threadId -> TraceThreadIdModel.builder()
                                        .id(generateThreadModelId(threadIdToTimestamp.get(threadId)))
                                        .projectId(projectId)
                                        .threadId(threadId)
                                        .createdBy(userName)
                                        .createdAt(Instant.now())
                                        .build())
                                .toList();

                        // Plain INSERT: a concurrent create trips the (project_id, thread_id) constraint
                        // and triggers a retry, which re-reads the now-smaller missing set.
                        dao.save(created);

                        List<TraceThreadIdModel> result = new ArrayList<>(existing);
                        result.addAll(created);
                        return result;
                    }))
                    .withRetry(3, () -> new EntityAlreadyExistsException(new ErrorMessage(
                            "Failed to resolve trace thread ids for project: '%s', workspace: '%s'"
                                    .formatted(projectId, workspaceId))));
        }).subscribeOn(Schedulers.boundedElastic()));
    }

    @Cacheable(name = "GET_TRACE_THREAD_ID", key = "$workspaceId +'-'+ $projectId +'-'+ $threadId", returnType = UUID.class)
    public Mono<UUID> getThreadModelId(@NonNull String workspaceId, @NonNull UUID projectId,
            @NonNull String threadId) {
        Preconditions.checkArgument(StringUtils.isNotBlank(workspaceId), "Workspace ID cannot be blank");
        Preconditions.checkArgument(StringUtils.isNotBlank(threadId), "Thread ID cannot be blank");

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
                handle -> handle.attach(TraceThreadIdDAO.class).findByProjectIdAndThreadId(projectId, threadId)
                        .orElse(null)));
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
                            return traceThreadDAO.findByProjectIdAndThreadId(projectId, threadId).orElse(null);
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
