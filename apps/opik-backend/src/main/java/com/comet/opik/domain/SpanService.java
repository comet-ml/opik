package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanSearchCriteria;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import io.opentelemetry.instrumentation.annotations.WithSpan;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.attachment.EntityType.SPAN;
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
    private final @NonNull AttachmentService attachmentService;

    @WithSpan
    public Mono<Span.SpanPage> find(int page, int size, @NonNull SpanSearchCriteria searchCriteria) {
        log.info("Finding span by '{}'", searchCriteria);
        searchCriteria = findProjectAndVerifyVisibility(searchCriteria);

        return spanDAO.find(page, size, searchCriteria);
    }

    private SpanSearchCriteria findProjectAndVerifyVisibility(SpanSearchCriteria searchCriteria) {
        return searchCriteria.toBuilder()
                .projectId(projectService.resolveProjectIdAndVerifyVisibility(searchCriteria.projectId(),
                        searchCriteria.projectName()))
                .build();
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id) {
        log.info("Getting span by id '{}'", id);
        return Mono.deferContextual(ctx -> spanDAO.getById(id)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Span", id))))
                .flatMap(span -> {
                    Project project = projectService.get(span.projectId(), ctx.get(RequestContext.WORKSPACE_ID));
                    return Mono.just(span.toBuilder()
                            .projectName(project.name())
                            .build());
                }));
    }

    @WithSpan
    public Mono<UUID> create(@NonNull Span span) {
        var id = span.id() == null ? idGenerator.generateId() : span.id();
        var projectName = WorkspaceUtils.getProjectName(span.projectName());
        return IdGenerator
                .validateVersionAsync(id, SPAN_KEY)
                .then(projectService.getOrCreate(projectName))
                .flatMap(project -> lockService.executeWithLock(
                        new LockService.Lock(id, SPAN_KEY),
                        Mono.defer(() -> insertSpan(span, project, id))));
    }

    private Mono<UUID> insertSpan(Span span, Project project, UUID id) {
        return spanDAO.getPartialById(id)
                .flatMap(partialExistingSpan -> insertSpan(span, project, id, partialExistingSpan))
                .switchIfEmpty(Mono.defer(() -> create(span, project, id)))
                .onErrorResume(this::handleSpanDBError);
    }

    private Mono<UUID> insertSpan(Span span, Project project, UUID id, Span partialExistingSpan) {
        return Mono.defer(() -> {
            // Check if a partial span exists caused by a patch request, if so, proceed to insert.
            if (Instant.EPOCH.equals(partialExistingSpan.startTime())
                    && partialExistingSpan.type() == null) {
                return create(span, project, id);
            }
            // Otherwise, a non-partial span already exists, so we ignore the insertion and just return the id.
            return Mono.just(id);
        });
    }

    private Mono<UUID> create(Span span, Project project, UUID id) {
        span = span.toBuilder().id(id).projectId(project.id()).build();
        log.info("Inserting span with id '{}', projectId '{}', traceId '{}', parentSpanId '{}'",
                span.id(), span.projectId(), span.traceId(), span.parentSpanId());
        return spanDAO.insert(span).thenReturn(span.id());
    }

    @WithSpan
    public Mono<Void> update(@NonNull UUID id, @NonNull SpanUpdate spanUpdate) {
        log.info("Updating span with id '{}'", id);

        String projectName = WorkspaceUtils.getProjectName(spanUpdate.projectName());

        return IdGenerator
                .validateVersionAsync(id, SPAN_KEY)
                .then(Mono.defer(() -> getProjectById(spanUpdate)
                        .switchIfEmpty(Mono.defer(() -> projectService.getOrCreate(projectName)))
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
                .flatMap(projectService::getOrCreate)
                .collectList()
                .map(projects -> bindSpanToProjectAndId(batch, projects));

        return resolveProjects
                .flatMap(spanDAO::batchInsert);
    }

    private List<Span> bindSpanToProjectAndId(SpanBatch batch, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(
                        Project::name,
                        Function.identity(),
                        BinaryOperatorUtils.last(),
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));

        return batch.spans()
                .stream()
                .map(span -> {
                    String projectName = WorkspaceUtils.getProjectName(span.projectName());
                    Project project = projectPerName.get(projectName);

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
        criteria = findProjectAndVerifyVisibility(criteria);
        return spanDAO.getStats(criteria)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    @WithSpan
    public Flux<Span> search(int limit, @NonNull SpanSearchCriteria criteria) {
        criteria = findProjectAndVerifyVisibility(criteria);

        return spanDAO.search(limit, criteria);
    }

    public Mono<Void> deleteByTraceIds(Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Mono.empty();
        }

        return spanDAO.getSpanIdsForTraces(traceIds)
                .flatMap(
                        spanIds -> commentService.deleteByEntityIds(CommentDAO.EntityType.SPAN, spanIds)
                                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(SPAN, spanIds))))
                .then(Mono.defer(() -> spanDAO.deleteByTraceIds(traceIds)))
                .then();
    }

    @WithSpan
    public Mono<SpansCountResponse> countSpansPerWorkspace() {
        return spanDAO.countSpansPerWorkspace()
                .collectList()
                .flatMap(items -> Mono.just(
                        SpansCountResponse.builder()
                                .workspacesSpansCount(items)
                                .build()))
                .switchIfEmpty(Mono.just(SpansCountResponse.empty()));
    }
}
