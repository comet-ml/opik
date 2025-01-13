package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.TraceSearchCriteria;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.events.TracesCreated;
import com.comet.opik.api.events.TracesUpdated;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.AsyncUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
import com.google.inject.ImplementedBy;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.domain.FeedbackScoreDAO.EntityType;

@ImplementedBy(TraceServiceImpl.class)
public interface TraceService {

    String PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH = "Project name and workspace name do not match the existing trace";

    Mono<UUID> create(Trace trace);

    Mono<Long> create(TraceBatch batch);

    Mono<Void> update(TraceUpdate trace, UUID id);

    Mono<Trace> get(UUID id);

    Mono<Void> delete(UUID id);

    Mono<Void> delete(Set<UUID> ids);

    Mono<Trace.TracePage> find(int page, int size, TraceSearchCriteria criteria);

    Mono<Boolean> validateTraceWorkspace(String workspaceId, Set<UUID> traceIds);

    Mono<TraceCountResponse> countTracesPerWorkspace();

    Mono<BiInformationResponse> getTraceBIInformation();

    Mono<ProjectStats> getStats(TraceSearchCriteria searchCriteria);

    Mono<Long> getDailyCreatedCount();

    Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class TraceServiceImpl implements TraceService {

    public static final String TRACE_KEY = "Trace";

    private final @NonNull TraceDAO dao;
    private final @NonNull SpanDAO spanDAO;
    private final @NonNull FeedbackScoreDAO feedbackScoreDAO;
    private final @NonNull TransactionTemplateAsync template;
    private final @NonNull ProjectService projectService;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;
    private final @NonNull EventBus eventBus;

    @Override
    @WithSpan
    public Mono<UUID> create(@NonNull Trace trace) {

        String projectName = WorkspaceUtils.getProjectName(trace.projectName());
        UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();

        return Mono.deferContextual(ctx -> IdGenerator
                .validateVersionAsync(id, TRACE_KEY)
                .then(Mono.defer(() -> getOrCreateProject(projectName)))
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, TRACE_KEY),
                        Mono.defer(() -> insertTrace(trace, project, id)))
                        .doOnSuccess(__ -> {
                            // forwards the trace with its actual projectId
                            var savedTrace = trace.toBuilder().projectId(project.id()).build();
                            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                            String userName = ctx.get(RequestContext.USER_NAME);

                            eventBus.post(new TracesCreated(List.of(savedTrace), workspaceId, userName));
                        })));
    }

    @WithSpan
    public Mono<Long> create(TraceBatch batch) {

        Preconditions.checkArgument(!batch.traces().isEmpty(), "Batch traces cannot be empty");

        List<String> projectNames = batch.traces()
                .stream()
                .map(Trace::projectName)
                .map(WorkspaceUtils::getProjectName)
                .distinct()
                .toList();

        return Mono.deferContextual(ctx -> {
            Mono<List<Trace>> resolveProjects = Flux.fromIterable(projectNames)
                    .flatMap(this::getOrCreateProject)
                    .collectList()
                    .map(projects -> bindTraceToProjectAndId(batch, projects))
                    .subscribeOn(Schedulers.boundedElastic());

            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return resolveProjects
                    .flatMap(traces -> template.nonTransaction(connection -> dao.batchInsert(traces, connection))
                            .doOnSuccess(__ -> eventBus.post(new TracesCreated(traces, workspaceId, userName))));
        });
    }

    private List<Trace> bindTraceToProjectAndId(TraceBatch batch, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(Project::name, Function.identity()));

        return batch.traces()
                .stream()
                .map(trace -> {
                    String projectName = WorkspaceUtils.getProjectName(trace.projectName());
                    Project project = projectPerName.get(projectName);

                    UUID id = trace.id() == null ? idGenerator.generateId() : trace.id();
                    IdGenerator.validateVersion(id, TRACE_KEY);

                    return trace.toBuilder().id(id).projectId(project.id()).build();
                })
                .toList();
    }

    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id) {
        //TODO: refactor to implement proper conflict resolution
        return template.nonTransaction(connection -> dao.findById(id, connection))
                .flatMap(existingTrace -> insertTrace(newTrace, project, id, existingTrace))
                .switchIfEmpty(Mono.defer(() -> create(newTrace, project, id)))
                .onErrorResume(this::handleDBError);
    }

    private <T> Mono<T> handleDBError(Throwable ex) {
        if (ex instanceof ClickHouseException
                && ex.getMessage().contains("TOO_LARGE_STRING_SIZE")
                && ex.getMessage().contains("String too long for type FixedString")
                && (ex.getMessage().contains("project_id") || ex.getMessage().contains("workspace_id"))) {

            return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
        }

        return Mono.error(ex);
    }

    private Mono<Project> getProjectById(TraceUpdate traceUpdate) {
        return AsyncUtils.makeMonoContextAware((userName, workspaceId) -> {

            if (traceUpdate.projectId() != null) {
                return Mono.fromCallable(() -> projectService.get(traceUpdate.projectId(), workspaceId));
            }

            return Mono.empty();
        });
    }

    private Mono<Project> getOrCreateProject(String projectName) {
        return AsyncUtils.makeMonoContextAware((userName, workspaceId) -> Mono
                .fromCallable(() -> projectService.getOrCreate(workspaceId, projectName, userName))
                .onErrorResume(e -> handleProjectCreationError(e, projectName, workspaceId))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Mono<UUID> insertTrace(Trace newTrace, Project project, UUID id, Trace existingTrace) {
        return Mono.defer(() -> {
            //TODO: refactor to implement proper conflict resolution
            // check if a partial trace exists caused by a patch request
            if (existingTrace.name().isBlank()
                    && existingTrace.startTime().equals(Instant.EPOCH)
                    && existingTrace.projectId().equals(project.id())) {

                return create(newTrace, project, id);
            }

            if (!project.id().equals(existingTrace.projectId())) {
                return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
            }

            // otherwise, reject the trace creation
            return Mono
                    .error(new EntityAlreadyExistsException(new ErrorMessage(List.of("Trace already exists"))));
        });
    }

    private Mono<UUID> create(Trace trace, Project project, UUID id) {
        return template.nonTransaction(connection -> {
            var newTrace = trace.toBuilder().id(id).projectId(project.id()).build();
            return dao.insert(newTrace, connection);
        });
    }

    private Mono<Project> handleProjectCreationError(Throwable exception, String projectName, String workspaceId) {
        return switch (exception) {
            case EntityAlreadyExistsException __ -> Mono.fromCallable(
                    () -> projectService.findByNames(workspaceId, List.of(projectName)).stream().findFirst()
                            .orElseThrow())
                    .subscribeOn(Schedulers.boundedElastic());
            default -> Mono.error(exception);
        };
    }

    @Override
    @WithSpan
    public Mono<Void> update(@NonNull TraceUpdate traceUpdate, @NonNull UUID id) {

        var projectName = WorkspaceUtils.getProjectName(traceUpdate.projectName());

        return Mono.deferContextual(ctx -> getProjectById(traceUpdate)
                .switchIfEmpty(Mono.defer(() -> getOrCreateProject(projectName)))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, TRACE_KEY),
                        Mono.defer(() -> template.nonTransaction(connection -> dao.findById(id, connection))
                                .flatMap(trace -> updateOrFail(traceUpdate, id, trace, project).thenReturn(id))
                                .switchIfEmpty(Mono.defer(() -> insertUpdate(project, traceUpdate, id))
                                        .thenReturn(id))
                                .onErrorResume(this::handleDBError)
                                .doOnSuccess(__ -> eventBus.post(new TracesUpdated(
                                        Set.of(project.id()),
                                        ctx.get(RequestContext.WORKSPACE_ID),
                                        ctx.get(RequestContext.USER_NAME)))))))
                .then());
    }

    private Mono<Void> insertUpdate(Project project, TraceUpdate traceUpdate, UUID id) {
        return IdGenerator
                .validateVersionAsync(id, TRACE_KEY)
                .then(Mono.defer(() -> template.nonTransaction(
                        connection -> dao.partialInsert(project.id(), traceUpdate, id, connection))));
    }

    private Mono<Void> updateOrFail(TraceUpdate traceUpdate, UUID id, Trace trace, Project project) {
        if (project.id().equals(trace.projectId())) {
            return template.nonTransaction(connection -> dao.update(traceUpdate, id, connection));
        }

        return failWithConflict(PROJECT_NAME_AND_WORKSPACE_NAME_MISMATCH);
    }

    private Mono<Project> getProjectByName(String projectName) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> projectService.findByNames(workspaceId, List.of(projectName)))
                    .flatMap(projects -> projects.stream().findFirst().map(Mono::just).orElseGet(Mono::empty))
                    .subscribeOn(Schedulers.boundedElastic());
        });
    }

    private <T> Mono<T> failWithConflict(String error) {
        log.info(error);
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(error))));
    }

    private NotFoundException failWithNotFound(String error) {
        log.info(error);
        return new NotFoundException(Response.status(404).entity(new ErrorMessage(List.of(error))).build());
    }

    @Override
    @WithSpan
    public Mono<Trace> get(@NonNull UUID id) {
        return template.nonTransaction(connection -> dao.findById(id, connection))
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Trace not found"))));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(@NonNull UUID id) {
        log.info("Deleting trace by id '{}'", id);
        return lockService.executeWithLock(
                new LockService.Lock(id, TRACE_KEY),
                Mono.defer(() -> feedbackScoreDAO.deleteByEntityId(EntityType.TRACE, id))
                        .then(Mono.defer(
                                () -> template.nonTransaction(connection -> spanDAO.deleteByTraceId(id, connection))))
                        .then(Mono.defer(() -> template.nonTransaction(connection -> dao.delete(id, connection)))));
    }

    @Override
    @WithSpan
    public Mono<Void> delete(Set<UUID> ids) {
        Preconditions.checkArgument(CollectionUtils.isNotEmpty(ids), "Argument 'ids' must not be empty");
        log.info("Deleting traces, count '{}'", ids.size());
        return template
                .nonTransaction(connection -> feedbackScoreDAO.deleteByEntityIds(EntityType.TRACE, ids))
                .then(Mono
                        .defer(() -> template.nonTransaction(connection -> spanDAO.deleteByTraceIds(ids, connection))))
                .then(Mono.defer(() -> template.nonTransaction(connection -> dao.delete(ids, connection))));
    }

    @Override
    @WithSpan
    public Mono<Trace.TracePage> find(int page, int size, @NonNull TraceSearchCriteria criteria) {

        if (criteria.projectId() != null) {
            return template.nonTransaction(connection -> dao.find(size, page, criteria, connection));
        }

        return getProjectByName(criteria.projectName())
                .flatMap(project -> template.nonTransaction(connection -> dao.find(
                        size, page, criteria.toBuilder().projectId(project.id()).build(), connection)))
                .switchIfEmpty(Mono.just(Trace.TracePage.empty(page)));
    }

    @Override
    @WithSpan
    public Mono<Boolean> validateTraceWorkspace(@NonNull String workspaceId, @NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Mono.just(true);
        }

        return template.nonTransaction(connection -> dao.getTraceWorkspace(traceIds, connection)
                .map(traceWorkspace -> traceWorkspace.stream()
                        .allMatch(trace -> workspaceId.equals(trace.workspaceId()))));
    }

    @Override
    @WithSpan
    public Mono<TraceCountResponse> countTracesPerWorkspace() {
        return template.stream(dao::countTracesPerWorkspace)
                .collectList()
                .flatMap(items -> Mono.just(
                        TraceCountResponse.builder()
                                .workspacesTracesCount(items)
                                .build()))
                .switchIfEmpty(Mono.just(TraceCountResponse.empty()));
    }

    @Override
    @WithSpan
    public Mono<BiInformationResponse> getTraceBIInformation() {
        log.info("Getting trace BI events daily data");
        return template.stream(dao::getTraceBIInformation)
                .collectList()
                .flatMap(items -> Mono.just(
                        BiInformationResponse.builder()
                                .biInformation(items)
                                .build()))
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));
    }

    @Override
    @WithSpan
    public Mono<ProjectStats> getStats(@NonNull TraceSearchCriteria criteria) {

        if (criteria.projectId() != null) {
            return dao.getStats(criteria)
                    .switchIfEmpty(Mono.just(ProjectStats.empty()));
        }

        return getProjectByName(criteria.projectName())
                .flatMap(project -> dao.getStats(criteria.toBuilder().projectId(project.id()).build()))
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    @Override
    @WithSpan
    public Mono<Long> getDailyCreatedCount() {
        return dao.getDailyTraces();
    }

    @Override
    public Mono<Map<UUID, Instant>> getLastUpdatedTraceAt(Set<UUID> projectIds, String workspaceId) {
        return template
                .nonTransaction(connection -> dao.getLastUpdatedTraceAt(projectIds, workspaceId, connection));
    }
}
