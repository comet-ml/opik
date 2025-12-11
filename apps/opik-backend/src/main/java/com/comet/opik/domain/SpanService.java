package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Project;
import com.comet.opik.api.ProjectStats;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.api.events.SpansCreated;
import com.comet.opik.domain.attachment.AttachmentReinjectorService;
import com.comet.opik.domain.attachment.AttachmentService;
import com.comet.opik.domain.attachment.AttachmentStripperService;
import com.comet.opik.domain.attachment.AttachmentUtils;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.lock.LockService;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.common.eventbus.EventBus;
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
import java.util.ArrayList;
import java.util.Collection;
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
    private final @NonNull AttachmentStripperService attachmentStripperService;
    private final @NonNull AttachmentReinjectorService attachmentReinjectorService;
    private final @NonNull EventBus eventBus;
    private final @NonNull ServiceTogglesConfig serviceTogglesConfig;

    @WithSpan
    public Mono<Span.SpanPage> find(int page, int size, @NonNull SpanSearchCriteria searchCriteria) {
        log.info("Finding span by '{}'", searchCriteria);

        return findProjectAndVerifyVisibility(searchCriteria)
                .flatMap(resolvedCriteria -> spanDAO.find(page, size, resolvedCriteria)
                        .flatMap(spanPage -> {
                            // If stripAttachments=false, reinject attachments into all spans
                            if (!resolvedCriteria.stripAttachments()) {
                                return Flux.fromIterable(spanPage.content())
                                        .concatMap(span -> attachmentReinjectorService.reinjectAttachments(span,
                                                !resolvedCriteria.stripAttachments()))
                                        .collectList()
                                        .map(reinjectedSpans -> spanPage.toBuilder()
                                                .content(reinjectedSpans)
                                                .build());
                            }
                            return Mono.just(spanPage);
                        }));
    }

    private Mono<SpanSearchCriteria> findProjectAndVerifyVisibility(SpanSearchCriteria searchCriteria) {
        return projectService
                .resolveProjectIdAndVerifyVisibility(searchCriteria.projectId(), searchCriteria.projectName())
                .map(projectId -> searchCriteria.toBuilder().projectId(projectId).build());
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id) {
        return getById(id, false);
    }

    @WithSpan
    public Mono<Span> getById(@NonNull UUID id, boolean stripAttachments) {
        return Mono.deferContextual(ctx -> spanDAO.getById(id)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithNotFound("Span", id))))
                .flatMap(span -> {
                    Project project = projectService.get(span.projectId(), ctx.get(RequestContext.WORKSPACE_ID));
                    return Mono.just(span.toBuilder()
                            .projectName(project.name())
                            .build());
                }))
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, !stripAttachments));
    }

    @WithSpan
    public Flux<Span> getByTraceIds(@NonNull Set<UUID> traceIds) {
        if (traceIds.isEmpty()) {
            return Flux.empty();
        }

        log.info("Getting spans for '{}' traces", traceIds.size());

        return spanDAO.getByTraceIds(traceIds)
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, true));
    }

    @WithSpan
    public Flux<Span> getByIds(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return Flux.empty();
        }

        log.info("Getting '{}' spans by IDs", ids.size());

        return spanDAO.getByIds(ids)
                .flatMap(span -> attachmentReinjectorService.reinjectAttachments(span, true));
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
            if (Instant.EPOCH.equals(partialExistingSpan.startTime())) {
                return create(span, project, id);
            }
            // Otherwise, a non-partial span already exists, so we ignore the insertion and just return the id.
            return Mono.just(id);
        });
    }

    private Mono<UUID> create(Span span, Project project, UUID id) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // Strip attachments from the span with the generated ID and project ID
            Span spanWithId = span.toBuilder().id(id).projectId(project.id()).build();
            return attachmentStripperService.stripAttachments(spanWithId, workspaceId, userName, projectName)
                    .flatMap(processedSpan -> {
                        log.info("Inserting span with id '{}' , projectId '{}' , traceId '{}' , parentSpanId '{}'",
                                processedSpan.id(), processedSpan.projectId(), processedSpan.traceId(),
                                processedSpan.parentSpanId());
                        return spanDAO.insert(processedSpan)
                                .doOnSuccess(__ -> {
                                    if (serviceTogglesConfig.isSpanLlmAsJudgeEnabled()) {
                                        var savedSpan = processedSpan.toBuilder()
                                                .projectId(project.id())
                                                .projectName(projectName)
                                                .build();
                                        eventBus.post(new SpansCreated(List.of(savedSpan), workspaceId, userName));
                                    }
                                })
                                .thenReturn(processedSpan.id());
                    });
        });
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
                                Mono.defer(() -> spanDAO.getOnlySpanDataById(id, project.id())
                                        .flatMap(span -> updateOrFail(spanUpdate, id, span, project))
                                        .switchIfEmpty(
                                                Mono.defer(() -> insertUpdate(project, spanUpdate, id)))
                                        .onErrorResume(this::handleSpanDBError)
                                        .then()))));
    }

    @WithSpan
    public Mono<Void> batchUpdate(@NonNull SpanBatchUpdate batchUpdate) {
        log.info("Batch updating '{}' spans", batchUpdate.ids().size());

        boolean mergeTags = Boolean.TRUE.equals(batchUpdate.mergeTags());
        return spanDAO.bulkUpdate(batchUpdate.ids(), batchUpdate.update(), mergeTags)
                .doOnSuccess(__ -> log.info("Completed batch update for '{}' spans", batchUpdate.ids().size()));
    }

    private Mono<Long> insertUpdate(Project project, SpanUpdate spanUpdate, UUID id) {
        return IdGenerator
                .validateVersionAsync(id, SPAN_KEY)
                .then(Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String userName = ctx.get(RequestContext.USER_NAME);
                    String projectName = project.name();

                    // Strip attachments OUTSIDE the database transaction
                    return attachmentStripperService.stripAttachments(
                            spanUpdate, id, workspaceId, userName, projectName)
                            .flatMap(processedUpdate -> spanDAO.partialInsert(id, project.id(), processedUpdate));
                }));
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

        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);
            String projectName = project.name();

            // Step 1: Get existing attachments OUTSIDE the database transaction
            return attachmentService.getAttachmentInfoByEntity(id, SPAN, existingSpan.projectId())
                    .flatMap(existingAttachments ->
            // Step 2: Strip attachments OUTSIDE the database transaction
            attachmentStripperService.stripAttachments(
                    spanUpdate, id, workspaceId, userName, projectName)
                    .flatMap(processedUpdate ->
            // Step 3: Update the span in database transaction
            spanDAO.update(id, processedUpdate, existingSpan)
                    .flatMap(updateResult -> {
                        // Step 4: Delete only auto-stripped attachments from the old data
                        // User-uploaded attachments are preserved unless explicitly removed by user
                        List<AttachmentInfo> autoStrippedAttachments = AttachmentUtils
                                .filterAutoStrippedAttachments(existingAttachments);

                        if (!autoStrippedAttachments.isEmpty()) {
                            return attachmentService.deleteSpecificAttachments(autoStrippedAttachments,
                                    id, SPAN, existingSpan.projectId())
                                    .thenReturn(updateResult);
                        }
                        return Mono.just(updateResult);
                    })));
        });
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

        List<Span> dedupedSpans = dedupSpans(batch.spans());

        List<String> projectNames = dedupedSpans
                .stream()
                .map(Span::projectName)
                .map(WorkspaceUtils::getProjectName)
                .distinct()
                .toList();

        log.info("Creating batch of spans for projects '{}'", projectNames);

        // Delete only auto-stripped attachments for all spans in the batch before processing
        // This prevents duplicate auto-stripped attachments when the SDK sends the same span data multiple times
        // while preserving user-uploaded attachments
        Set<UUID> spanIds = dedupedSpans.stream()
                .map(Span::id)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());

        return attachmentService.deleteAutoStrippedAttachments(SPAN, spanIds)
                .then(Mono.deferContextual(ctx -> {
                    String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
                    String userName = ctx.get(RequestContext.USER_NAME);

                    Mono<List<Span>> resolveProjects = Flux.fromIterable(projectNames)
                            .flatMap(projectService::getOrCreate)
                            .collectList()
                            .map(projects -> bindSpanToProjectAndId(dedupedSpans, projects));

                    return resolveProjects
                            .flatMap(this::stripAttachmentsFromSpanBatch)
                            .flatMap(spans -> spanDAO.batchInsert(spans)
                                    .doOnSuccess(__ -> {
                                        if (serviceTogglesConfig.isSpanLlmAsJudgeEnabled()) {
                                            eventBus.post(new SpansCreated(spans, workspaceId, userName));
                                        }
                                    }));
                }));
    }

    private Mono<List<Span>> stripAttachmentsFromSpanBatch(List<Span> spans) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return Flux.fromIterable(spans)
                    .flatMap(span -> {
                        String projectName = WorkspaceUtils.getProjectName(span.projectName());
                        return attachmentStripperService.stripAttachments(span, workspaceId, userName,
                                projectName);
                    })
                    .collectList();
        });
    }

    private List<Span> dedupSpans(List<Span> initialSpans) {

        Map<Boolean, List<Span>> shouldBeDeduped = initialSpans.stream()
                .collect(Collectors.partitioningBy(span -> span.id() != null && span.lastUpdatedAt() != null));

        List<Span> result = new ArrayList<>(shouldBeDeduped.get(false));

        Collection<Span> dedupedSpans = shouldBeDeduped.get(true)
                .stream()
                .collect(Collectors.toMap(
                        Span::id,
                        Function.identity(),
                        (span1, span2) -> span1.lastUpdatedAt().isAfter(span2.lastUpdatedAt()) ? span1 : span2))
                .values();

        result.addAll(dedupedSpans);

        return result;
    }

    private List<Span> bindSpanToProjectAndId(List<Span> spans, List<Project> projects) {
        Map<String, Project> projectPerName = projects.stream()
                .collect(Collectors.toMap(
                        Project::name,
                        Function.identity(),
                        BinaryOperatorUtils.last(),
                        () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));

        return spans
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
        return findProjectAndVerifyVisibility(criteria)
                .flatMap(spanDAO::getStats)
                .switchIfEmpty(Mono.just(ProjectStats.empty()));
    }

    @WithSpan
    public Flux<Span> search(int limit, @NonNull SpanSearchCriteria criteria) {
        return findProjectAndVerifyVisibility(criteria)
                .flatMapMany(resolvedCriteria -> spanDAO.search(limit, resolvedCriteria)
                        .concatMap(span ->
                        // If stripAttachments=false, reinject attachments
                        attachmentReinjectorService.reinjectAttachments(span,
                                !resolvedCriteria.stripAttachments())));
    }

    @WithSpan
    public Mono<Void> deleteByTraceIds(Set<UUID> traceIds, UUID projectId) {
        if (traceIds.isEmpty()) {
            return Mono.empty();
        }

        return spanDAO.getSpanIdsForTraces(traceIds)
                .flatMap(
                        spanIds -> commentService.deleteByEntityIds(CommentDAO.EntityType.SPAN, spanIds)
                                .then(Mono.defer(() -> attachmentService.deleteByEntityIds(SPAN, spanIds))))
                .then(Mono.defer(() -> spanDAO.deleteByTraceIds(traceIds, projectId)))
                .then();
    }

    @WithSpan
    public Mono<SpansCountResponse> countSpansPerWorkspace() {
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::countSpansPerWorkspace)
                .collectList()
                .flatMap(items -> Mono.just(
                        SpansCountResponse.builder()
                                .workspacesSpansCount(items)
                                .build()))
                .switchIfEmpty(Mono.just(SpansCountResponse.empty()));
    }

    @WithSpan
    public Mono<BiInformationResponse> getSpanBIInformation() {
        log.info("Getting span BI events daily data");
        return projectService.getDemoProjectIdsWithTimestamps()
                .switchIfEmpty(Mono.just(Map.of()))
                .flatMapMany(spanDAO::getSpanBIInformation)
                .collectList()
                .map(items -> BiInformationResponse.builder()
                        .biInformation(items)
                        .build())
                .switchIfEmpty(Mono.just(BiInformationResponse.empty()));
    }
}
