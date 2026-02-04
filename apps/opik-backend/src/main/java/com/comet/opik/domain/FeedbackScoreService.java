package com.comet.opik.domain;

import com.comet.opik.api.DeleteFeedbackScore;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.Visibility;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.validation.constraints.NotNull;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import static com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import static com.comet.opik.utils.ErrorUtils.failWithNotFound;
import static java.util.stream.Collectors.groupingBy;

@ImplementedBy(FeedbackScoreServiceImpl.class)
public interface FeedbackScoreService {

    Mono<Void> scoreTrace(UUID traceId, FeedbackScore score);
    Mono<Void> scoreSpan(UUID spanId, FeedbackScore score);

    Mono<Void> scoreBatchOfSpans(List<FeedbackScoreBatchItem> scores);
    Mono<Void> scoreBatchOfTraces(List<FeedbackScoreBatchItem> scores);

    Mono<Void> deleteSpanScore(UUID id, DeleteFeedbackScore score);
    Mono<Void> deleteTraceScore(UUID id, DeleteFeedbackScore score);

    Mono<FeedbackScoreNames> getTraceFeedbackScoreNames(UUID projectId);

    Mono<FeedbackScoreNames> getSpanFeedbackScoreNames(UUID projectId, SpanType type);

    Mono<FeedbackScoreNames> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds);

    Mono<FeedbackScoreNames> getProjectsFeedbackScoreNames(Set<UUID> projectIds);

    Mono<Void> scoreBatchOfThreads(List<FeedbackScoreBatchItemThread> scores);

    Mono<Void> deleteThreadScores(String projectName, String threadId, Set<String> names, String author);

    Mono<FeedbackScoreNames> getTraceThreadsFeedbackScoreNames(UUID projectId);

    Mono<Void> deleteAllThreadScores(Set<UUID> threadModelIds, UUID projectId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class FeedbackScoreServiceImpl implements FeedbackScoreService {

    private final @NonNull FeedbackScoreDAO dao;
    private final @NonNull SpanDAO spanDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull ProjectService projectService;
    private final @NonNull TraceThreadService traceThreadService;
    private final @NonNull Provider<RequestContext> requestContext;

    @Builder(toBuilder = true)
    record ProjectDto<T extends FeedbackScoreItem>(Project project, List<T> scores) {
    }

    @Override
    public Mono<Void> scoreTrace(@NonNull UUID traceId, @NonNull FeedbackScore score) {
        return traceDAO.getProjectIdFromTrace(traceId)
                .switchIfEmpty(Mono.error(failWithNotFound("Trace", traceId)))
                .flatMap(projectId -> getAuthor()
                        .flatMap(author -> dao.scoreEntity(EntityType.TRACE, traceId, score, projectId,
                                author.orElse(null))))
                .then();
    }

    @Override
    public Mono<Void> scoreSpan(@NonNull UUID spanId, @NonNull FeedbackScore score) {

        return spanDAO.getProjectIdFromSpan(spanId)
                .switchIfEmpty(Mono.error(failWithNotFound("Span", spanId)))
                .flatMap(projectId -> getAuthor()
                        .flatMap(author -> dao.scoreEntity(EntityType.SPAN, spanId, score, projectId,
                                author.orElse(null))))
                .then();
    }

    @Override
    public Mono<Void> scoreBatchOfSpans(@NonNull List<FeedbackScoreBatchItem> scores) {
        return processScoreBatch(EntityType.SPAN, scores);
    }

    @Override
    public Mono<Void> scoreBatchOfTraces(@NonNull List<FeedbackScoreBatchItem> scores) {
        return processScoreBatch(EntityType.TRACE, scores);
    }

    private Mono<Void> processScoreBatch(EntityType entityType, List<FeedbackScoreBatchItem> scores) {

        if (scores.isEmpty()) {
            return Mono.empty();
        }

        // group scores by project name to resolve project itemIds
        Map<String, List<FeedbackScoreItem>> scoresPerProject = scores
                .stream()
                .map(score -> {
                    IdGenerator.validateVersion(score.id(), entityType.getType()); // validate span/trace id

                    return score.toBuilder()
                            .projectName(WorkspaceUtils.getProjectName(score.projectName()))
                            .build();
                })
                .collect(groupingBy(FeedbackScoreItem::projectName));

        return projectService.retrieveByNamesOrCreate(scoresPerProject.keySet())
                .map(ProjectService::groupByName)
                .map(projectMap -> mergeProjectsAndScores(projectMap, scoresPerProject))
                .flatMap(projects -> saveScoreBatch(entityType, projects)) // score all scores
                .then();
    }

    private <T extends FeedbackScoreItem> Mono<Long> saveScoreBatch(
            EntityType entityType, List<ProjectDto<T>> projects) {
        return getAuthor()
                .flatMap(author -> Flux.fromIterable(projects)
                        .flatMap(projectDto -> dao.scoreBatchOf(entityType, projectDto.scores(), author.orElse(null)))
                        .reduce(0L, Long::sum));
    }

    private <T extends FeedbackScoreItem> List<ProjectDto<T>> mergeProjectsAndScores(
            Map<String, Project> projectMap,
            Map<String, List<T>> scoresPerProject) {
        return scoresPerProject.keySet()
                .stream()
                .map(projectName -> {
                    Project project = projectMap.get(projectName);
                    return new ProjectDto<>(
                            project,
                            scoresPerProject.get(projectName)
                                    .stream()
                                    .map(item -> switch (item) {
                                        case FeedbackScoreBatchItem tracingItem -> tracingItem.toBuilder()
                                                .projectId(project.id()) // set projectId
                                                .build();
                                        case FeedbackScoreBatchItemThread threadItem -> threadItem.toBuilder()
                                                .projectId(project.id()) // set projectId
                                                .build();
                                    }) // set projectId
                                    .map(item -> (T) item)
                                    .toList());
                })
                .toList();
    }

    @Override
    public Mono<Void> deleteSpanScore(UUID id, DeleteFeedbackScore score) {
        return dao.deleteScoreFrom(EntityType.SPAN, id, score);
    }

    @Override
    public Mono<Void> deleteTraceScore(UUID id, DeleteFeedbackScore score) {
        return dao.deleteScoreFrom(EntityType.TRACE, id, score);
    }

    @Override
    public Mono<FeedbackScoreNames> getTraceFeedbackScoreNames(UUID projectId) {
        if (projectId == null) {
            // Allow only for private access
            boolean isPublic = Optional.ofNullable(requestContext.get().getVisibility())
                    .map(v -> v == Visibility.PUBLIC)
                    .orElse(false);

            if (isPublic) {
                return Mono.error(new ClientErrorException("Project ID is required for public access",
                        Response.Status.BAD_REQUEST));
            }
        } else {
            // Will throw an error in case we try to get private project with public visibility
            projectService.get(projectId);
        }

        return dao.getTraceFeedbackScoreNames(projectId)
                .map(names -> names.stream()
                        .map(name -> FeedbackScoreNames.ScoreName.builder().name(name).build())
                        .toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type) {
        // Will throw an error in case we try to get private project with public visibility
        projectService.get(projectId);
        return dao.getSpanFeedbackScoreNames(projectId, type)
                .map(names -> names.stream()
                        .map(name -> FeedbackScoreNames.ScoreName.builder().name(name).build())
                        .toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds) {
        return dao.getExperimentsFeedbackScoreNames(experimentIds)
                .map(scores -> scores.stream()
                        .map(score -> FeedbackScoreNames.ScoreName.builder()
                                .name(score.name())
                                .type(score.type())
                                .build())
                        .toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getProjectsFeedbackScoreNames(Set<UUID> projectIds) {
        return dao.getProjectsFeedbackScoreNames(projectIds)
                .map(names -> names.stream()
                        .map(name -> FeedbackScoreNames.ScoreName.builder().name(name).build())
                        .toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<Void> scoreBatchOfThreads(@NonNull List<FeedbackScoreBatchItemThread> scores) {
        return processThreadsScoreBatch(scores);
    }

    @Override
    public Mono<Void> deleteThreadScores(@NonNull String projectName, @NonNull String threadId,
            @NonNull Set<String> names, String author) {
        Preconditions.checkArgument(!StringUtils.isBlank(projectName), "Project name cannot be blank");
        Preconditions.checkArgument(!StringUtils.isBlank(threadId), "Thread ID cannot be blank");

        if (names.isEmpty()) {
            log.info("No names provided for deletion of scores for threadId '{}' in projectName '{}'", threadId,
                    projectName);
            return Mono.empty();
        }

        return getProject(projectName)
                .flatMap(projectId -> traceThreadService.getThreadModelId(projectId, threadId)
                        .flatMap(threadModelId -> dao.deleteByEntityIdAndNames(EntityType.THREAD, threadModelId, names,
                                author))
                        .switchIfEmpty(Mono.defer(() -> {
                            log.info("ThreadId '{}' not found in project '{}'. No scores deleted.", threadId,
                                    projectId);
                            return Mono.empty();
                        }))
                        .doOnNext(count -> log.info("Deleted '{}' scores for threadId '{}' in projectId '{}'", count,
                                threadId,
                                projectId)))
                .then();
    }

    @Override
    public Mono<FeedbackScoreNames> getTraceThreadsFeedbackScoreNames(UUID projectId) {
        return dao.getProjectsTraceThreadsFeedbackScoreNames(projectId == null ? List.of() : List.of(projectId))
                .map(names -> names.stream()
                        .map(name -> FeedbackScoreNames.ScoreName.builder().name(name).build())
                        .toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<Void> deleteAllThreadScores(@NotNull Set<UUID> threadModelIds, @NotNull UUID projectId) {
        if (threadModelIds.isEmpty()) {
            log.info("No thread model IDs provided for deletion of all scores in projectId '{}'", projectId);
            return Mono.empty();
        }

        return dao.deleteAllThreadScores(threadModelIds, projectId)
                .doOnNext(count -> {
                    if (count > 0) {
                        log.info("Deleted '{}' scores (all sources) for threads in projectId '{}'", count, projectId);
                    } else {
                        log.info("No scores found to delete for projectId '{}'", projectId);
                    }
                })
                .then();
    }

    private Mono<Optional<String>> getAuthor() {
        return Mono.deferContextual(context -> {
            try {
                String userName = context.get(RequestContext.USER_NAME);
                return Mono.just(Optional.of(userName));
            } catch (NoSuchElementException e) {
                log.info("Could not retrieve author from context", e);
                return Mono.just(Optional.empty());
            }
        });
    }

    private Mono<UUID> getProject(String projectName) {
        return Mono.deferContextual(context -> Mono.fromCallable(() -> {
            String workspaceId = context.get(RequestContext.WORKSPACE_ID);

            return projectService.findByNames(workspaceId, List.of(projectName)).stream().findFirst();
        }).flatMap(project -> {
            if (project.isEmpty()) {
                log.info("Project '{}' not found in workspace '{}'", projectName,
                        context.get(RequestContext.WORKSPACE_ID));
                return Mono.empty();
            }

            return Mono.just(project.get().id());
        }));
    }

    private Mono<Void> processThreadsScoreBatch(List<FeedbackScoreBatchItemThread> scores) {

        if (scores.isEmpty()) {
            log.info("No scores provided for batch processing of threads");
            return Mono.empty();
        }

        // group scores by project name to resolve project itemIds
        Map<String, List<FeedbackScoreBatchItemThread>> scoresPerProject = scores
                .stream()
                .map(score -> score.toBuilder()
                        .projectName(WorkspaceUtils.getProjectName(score.projectName()))
                        .build())
                .collect(groupingBy(FeedbackScoreItem::projectName));

        return projectService.retrieveByNamesOrCreate(scoresPerProject.keySet())
                .map(ProjectService::groupByName)
                .map(projectMap -> mergeProjectsAndScores(projectMap, scoresPerProject))
                .flatMap(this::saveThreadScoreBatch) // save all scores
                .doOnSuccess(count -> log.info("Saved '{}' thread scores in batch", count))
                .then();
    }

    private Mono<Long> saveThreadScoreBatch(List<ProjectDto<FeedbackScoreBatchItemThread>> projects) {
        return getAuthor()
                .flatMap(author -> saveThreadScoreBatch(projects, author.orElse(null)));
    }

    private Mono<Long> saveThreadScoreBatch(List<ProjectDto<FeedbackScoreBatchItemThread>> projects,
            @Nullable String author) {
        return Flux.fromIterable(projects)
                .flatMap(projectDto -> {
                    // Collect unique thread IDs from the scores
                    Set<String> threadIds = projectDto.scores()
                            .stream()
                            .map(FeedbackScoreItem::threadId)
                            .collect(Collectors.toSet());

                    return Flux.fromIterable(threadIds)
                            // resolve thread model IDs for each thread ID
                            .flatMap(threadId -> getOrCreateThread(projectDto, threadId))
                            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                            .map(threadIdMap -> bindThreadModelId(projectDto, threadIdMap))
                            .filter(projectDtoWithThreads -> !projectDtoWithThreads.scores().isEmpty())
                            // Thread status validation removed - feedback scores can now be added to threads
                            // regardless of their active/inactive status. The status concept is kept only
                            // for online scoring cooling period.
                            .flatMap(
                                    validatedProjectDto -> dao.scoreBatchOfThreads(validatedProjectDto.scores(),
                                            author));
                })
                .reduce(0L, Long::sum);
    }

    private Mono<Map.Entry<String, UUID>> getOrCreateThread(ProjectDto<FeedbackScoreBatchItemThread> projectDto,
            String threadId) {
        return traceThreadService.getOrCreateThreadId(projectDto.project().id(), threadId)
                .map(threadModelId -> Map.entry(threadId, threadModelId));
    }

    private ProjectDto<FeedbackScoreBatchItemThread> bindThreadModelId(
            ProjectDto<FeedbackScoreBatchItemThread> projectDto, Map<String, UUID> threadIdMap) {
        return projectDto.toBuilder()
                .project(projectDto.project())
                .scores(projectDto.scores()
                        .stream()
                        .map(score -> score.toBuilder()
                                .id(threadIdMap.get(score.threadId())) // set thread model id
                                .build())
                        .collect(Collectors.toList()))
                .build();
    }
}
