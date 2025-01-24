package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanSearchCriteria;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;

@Singleton
@RequiredArgsConstructor(onConstructor = @__(@Inject))
@Slf4j
public class SpanService {

    public static final String PARENT_SPAN_IS_MISMATCH = "parent_span_id does not match the existing span";
    public static final String TRACE_ID_MISMATCH = "trace_id does not match the existing span";
    public static final String SPAN_KEY = "Span";
    public static final String PROJECT_AND_WORKSPACE_NAME_MISMATCH = "Project name and workspace name do not match the existing span";

    private final @NonNull SpanDAO spanDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;
    private final @NonNull CommentService commentService;

    @WithSpan
    public Mono<Span.SpanPage> find(int page, int size, @NonNull SpanSearchCriteria searchCriteria) {
        log.info("Finding span by '{}'", searchCriteria);

        if (searchCriteria.projectId() != null) {
            return spanDAO.find(page, size, searchCriteria);
        }

        return findProject(searchCriteria)
                .flatMap(project -> project.stream().findFirst().map(Mono::just).orElseGet(Mono::empty))
                .flatMap(project -> spanDAO.find(
                        page, size, searchCriteria.toBuilder().projectId(project.id()).build()))
                .switchIfEmpty(Mono.just(Span.SpanPage.empty(page)));
    }

    private Mono<List<Project>> findProject(SpanSearchCriteria searchCriteria) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono
                    .fromCallable(() -> projectService.findByNames(workspaceId, List.of(searchCriteria.projectName())))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id) {
        log.info("Getting span by id '{}'", id);
        return spanDAO.getById(id).switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Span", id))));
    }

    @WithSpan
    public Mono<UUID> create(@NonNull Span span) {
        var id = span.id() == null ? idGenerator.generateId() : span.id();
        var projectName = WorkspaceUtils.getProjectName(span.projectName());

        return IdGenerator
                .validateVersionAsync(id, SPAN_KEY)
                .then(getOrCreateProject(projectName))
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, SPAN_KEY),
                        Mono.defer(() -> insertSpan(span, project, id))));
    }

    private Mono<Project> getOrCreateProject(String projectName) {
        return makeMonoContextAware((userName, workspaceId) -> {
            return Mono.fromCallable(() -> projectService.getOrCreate(workspaceId, projectName, userName))
                    .onErrorResume(e -> handleProjectCreationError(e, projectName, workspaceId))
                    .subscribeOn(Schedulers.boundedElastic());
        });

    }

    private Mono<UUID> insertSpan(Span span, Project project, UUID id) {
        //TODO: refactor to implement proper conflict resolution
        return spanDAO.getById(id)
                .flatMap(existingSpan -> insertSpan(span, project, id, existingSpan))
                .switchIfEmpty(Mono.defer(() -> create(span, project, id)))
                .onErrorResume(this::handleSpanDBError);
    }

    private Mono<UUID> insertSpan(Span span, Project project, UUID id, Span existingSpan) {
        return Mono.defer(() -> {
            // check if a partial span exists caused by a patch request
            if (existingSpan.name().isBlank()
                    && existingSpan.startTime().equals(Instant.EPOCH)
                    && existingSpan.type() == null
                    && existingSpan.projectId().equals(project.id())) {
                return create(span, project, id);
            }

            if (!project.id().equals(existingSpan.projectId())) {
                return failWithConflict(PROJECT_AND_WORKSPACE_NAME_MISMATCH);
            }

            if (!Objects.equals(span.parentSpanId(), existingSpan.parentSpanId())) {
                return failWithConflict(PARENT_SPAN_IS_MISMATCH);
            }

            if (!span.traceId().equals(existingSpan.traceId())) {
                return failWithConflict(TRACE_ID_MISMATCH);
            }

            // otherwise, reject the span creation
            return Mono
                    .error(new EntityAlreadyExistsException(new ErrorMessage(List.of("Span already exists"))));
        });
    }

    private Mono<UUID> create(Span span, Project project, UUID id) {
        var newSpan = span.toBuilder().id(id).projectId(project.id()).build();
        log.info("Inserting span with id '{}', traceId '{}', parentSpanId '{}'",
                span.id(), span.traceId(), span.parentSpanId());

        return spanDAO.insert(newSpan).thenReturn(newSpan.id());
    }

    private Mono<Project> handleProjectCreationError(Throwable exception, String projectName, String workspaceId) {
        return switch (exception) {
            case EntityAlreadyExistsException __ -> findProjectByName(projectName, workspaceId);
            default -> Mono.error(exception);
        };
    }

    private Mono<Project> findProjectByName(String projectName, String workspaceId) {
        return Mono.fromCallable(() -> projectService.findByNames(workspaceId, List.of(projectName))
                .stream().findFirst().orElseThrow())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @WithSpan
    public Mono<Void> update(@NonNull UUID id, @NonNull SpanUpdate spanUpdate) {
        log.info("Updating span with id '{}'", id);

        String projectName = WorkspaceUtils.getProjectName(spanUpdate.projectName());

        return IdGenerator
                .validateVersionAsync(id, SPAN_KEY)
                .then(Mono.defer(() -> getProjectById(spanUpdate)
                        .switchIfEmpty(Mono.defer(() -> getOrCreateProject(projectName)))
                        .subscribeOn(Schedulers.boundedElastic()))
                        //TODO: refactor to implement proper conflict resolution
                        .flatMap(project -> lockService.executeWithLock(
                                new LockService.Lock(id, SPAN_KEY),
                                Mono.defer(() -> spanDAO.getById(id)
                                        .flatMap(span -> updateOrFail(spanUpdate, id, span, project))
                                        .switchIfEmpty(
                                                Mono.defer(() -> spanDAO.partialInsert(id, project.id(), spanUpdate)))
                                        .onErrorResume(this::handleSpanDBError)
                                        .then()))));
    }

    private Mono<Project> getProjectById(SpanUpdate spanUpdate) {
        return makeMonoContextAware((userName, workspaceId) -> {

            if (spanUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(spanUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

    private <T> Mono<T> handleSpanDBError(Throwable ex) {
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("String too long for type FixedString")
                && (ex.getMessage().contains("project_id") || ex.getMessage().contains("workspace_id"))) {
            return failWithConflict(PROJECT_AND_WORKSPACE_NAME_MISMATCH);
        }

        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && (ex.getMessage().contains("CAST(leftPad(") && ex.getMessage().contains(".parent_span_id, 40_UInt8")
                        && ex.getMessage().contains("FixedString(19)"))) {

            return failWithConflict(PARENT_SPAN_IS_MISMATCH);
        }

        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("_CAST(trace_id, FixedString(36))")) {

            return failWithConflict(TRACE_ID_MISMATCH);
        }

        return Mono.error(ex);
    }

    private Mono<Long> updateOrFail(SpanUpdate spanUpdate, UUID id, Span existingSpan, Project project) {
        if (!project.id().equals(existingSpan.projectId())) {
            return failWithConflict(PROJECT_AND_WORKSPACE_NAME_MISMATCH);
        }

        if (!Objects.equals(existingSpan.parentSpanId(), spanUpdate.parentSpanId())) {
            return failWithConflict(PARENT_SPAN_IS_MISMATCH);
        }

        if (!existingSpan.traceId().equals(spanUpdate.traceId())) {
            return failWithConflict(TRACE_ID_MISMATCH);
        }

        return spanDAO.update(id, spanUpdate, existingSpan);
    }

    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    public Mono<Boolean> validateSpanWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> spanIds) {
        if (spanIds.isEmpty()) {
            return Mono.just(true);
        }

        return spanDAO.getSpanWorkspace(spanIds)
                .map(spanWorkspace -> spanWorkspace.stream().allMatch(span -> workspaceId.equals(span.workspaceId())));
    }

    @WithSpan
    public Mono<Long> create(@NonNull SpanBatch batch) {

        Preconditions.checkArgument(!batch.spans().isEmpty(), "Batch spans must not be empty");

        List<String> projectNames = batch.spans()
                .stream()
                .map(Span::projectName)
                .map(WorkspaceUtils::getProjectName)
                .distinct()
                .toList();

        log.info("Creating batch of spans for projects '{}'", projectNames);

        Mono<List<Span>> resolveProjects = Flux.fromIterable(projectNames)
                .flatMap(this::getOrCreateProject)
                .collectList()
                .map(projects -> bindSpanToProjectAndId(batch, projects));

        return resolveProjects
                .flatMap(spanDAO::batchInsert);
    }

    private List<Span> bindSpanToProjectAndId(SpanBatch batch, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(project -> project.name().toLowerCase(), Function.identity()));

        return batch.spans()
                .stream()
                .map(span -> {
                    String projectName = WorkspaceUtils.getProjectName(span.projectName());
                    Project project = projectPerName.get(projectName.toLowerCase());

                    if (project == null) {
                        log.warn("Project not found for span project '{}' and default '{}'", span.projectName(),
                                projectName);
                        throw new IllegalStateException("Project not found: %s".formatted(span.projectName()));
                    }

                    UUID id = span.id() == null ? idGenerator.generateId() : span.id();
                    IdGenerator.validateVersion(id, SPAN_KEY);

                    return span.toBuilder().id(id).projectId(project.id()).build();
                })
                .toList();
    }

    public Mono<ProjectStats> getStats(@NonNull SpanSearchCriteria criteria) {
        if (criteria.projectId() != null) {
            return spanDAO.getStats(criteria)
                    .switchIfEmpty(Mono.just(ProjectStats.empty()));
        }

        return makeMonoContextAware(
                (userName, workspaceId) -> findProjectByName(criteria.projectName(), workspaceId).onErrorResume(
                        e -> switch (e) {
                            case NoSuchElementException __ -> Mono.error(new NotFoundException("Project not found"));
                            default -> Mono.error(e);
                        }))
                .flatMap(project -> spanDAO.getStats(criteria.toBuilder().projectId(project.id()).build()))
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    public Mono<Void> deleteByTraceIds(Set<UUID> traceIds) {
        return spanDAO.getSpanIdsForTraces(traceIds)
                .flatMap(
                        spanIds -> commentService.deleteByEntityIds(CommentDAO.EntityType.SPAN, new HashSet<>(spanIds)))
                .then(Mono.defer(() -> spanDAO.deleteByTraceIds(traceIds)));
    }
}
