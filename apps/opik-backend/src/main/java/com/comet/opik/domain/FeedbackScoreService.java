package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.domain.threads.TraceThreadService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.common.base.Preconditions;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static com.comet.opik.utils.ErrorUtils.failWithNotFound;
import static java.util.stream.Collectors.groupingBy;

@ImplementedBy(FeedbackScoreServiceImpl.class)
public interface FeedbackScoreService {

    Mono<Void> scoreTrace(UUID traceId, FeedbackScore score);
    Mono<Void> scoreSpan(UUID spanId, FeedbackScore score);

    Mono<Void> scoreBatchOfSpans(List<FeedbackScoreBatchItem> scores);
    Mono<Void> scoreBatchOfTraces(List<FeedbackScoreBatchItem> scores);

    Mono<Void> deleteSpanScore(UUID id, String tag);
    Mono<Void> deleteTraceScore(UUID id, String tag);

    Mono<FeedbackScoreNames> getTraceFeedbackScoreNames(UUID projectId);

    Mono<FeedbackScoreNames> getSpanFeedbackScoreNames(UUID projectId, SpanType type);

    Mono<FeedbackScoreNames> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds);

    Mono<FeedbackScoreNames> getProjectsFeedbackScoreNames(Set<UUID> projectIds);

    Mono<Void> scoreBatchOfThreads(List<FeedbackScoreBatchItem> scores);

    Mono<Void> deleteThreadScores(String projectName, String threadId, Set<String> names);
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

    @Builder(toBuilder = true)
    record ProjectDto(Project project, List<FeedbackScoreBatchItem> scores) {
    }

    @Override
    public Mono<Void> scoreTrace(@NonNull UUID traceId, @NonNull FeedbackScore score) {
        return traceDAO.getProjectIdFromTrace(traceId)
                .switchIfEmpty(Mono.error(failWithNotFound("Trace", traceId)))
                .flatMap(projectId -> dao.scoreEntity(EntityType.TRACE, traceId, score, projectId))
                .then();
    }

    @Override
    public Mono<Void> scoreSpan(@NonNull UUID spanId, @NonNull FeedbackScore score) {

        return spanDAO.getProjectIdFromSpan(spanId)
                .switchIfEmpty(Mono.error(failWithNotFound("Span", spanId)))
                .flatMap(projectId -> dao.scoreEntity(EntityType.SPAN, spanId, score, projectId))
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
        Map<String, List<FeedbackScoreBatchItem>> scoresPerProject = scores
                .stream()
                .map(score -> {
                    IdGenerator.validateVersion(score.id(), entityType.getType()); // validate span/trace id

                    return score.toBuilder()
                            .projectName(WorkspaceUtils.getProjectName(score.projectName()))
                            .build();
                })
                .collect(groupingBy(FeedbackScoreBatchItem::projectName));

        return projectService.retrieveByNamesOrCreate(scoresPerProject.keySet())
                .map(ProjectService::groupByName)
                .map(projectMap -> mergeProjectsAndScores(projectMap, scoresPerProject))
                .flatMap(projects -> saveScoreBatch(entityType, projects)) // score all scores
                .then();
    }

    private Mono<Long> saveScoreBatch(EntityType entityType, List<ProjectDto> projects) {
        return Flux.fromIterable(projects)
                .flatMap(projectDto -> dao.scoreBatchOf(entityType, projectDto.scores()))
                .reduce(0L, Long::sum);
    }

    private List<ProjectDto> mergeProjectsAndScores(Map<String, Project> projectMap,
            Map<String, List<FeedbackScoreBatchItem>> scoresPerProject) {
        return scoresPerProject.keySet()
                .stream()
                .map(projectName -> {
                    Project project = projectMap.get(projectName);
                    return new ProjectDto(
                            project,
                            scoresPerProject.get(projectName)
                                    .stream()
                                    .map(item -> item.toBuilder().projectId(project.id()).build()) // set projectId
                                    .toList());
                })
                .toList();
    }

    @Override
    public Mono<Void> deleteSpanScore(UUID id, String name) {
        return dao.deleteScoreFrom(EntityType.SPAN, id, name);
    }

    @Override
    public Mono<Void> deleteTraceScore(UUID id, String name) {
        return dao.deleteScoreFrom(EntityType.TRACE, id, name);
    }

    @Override
    public Mono<FeedbackScoreNames> getTraceFeedbackScoreNames(@NonNull UUID projectId) {
        // Will throw an error in case we try to get private project with public visibility
        projectService.get(projectId);
        return dao.getTraceFeedbackScoreNames(projectId)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type) {
        // Will throw an error in case we try to get private project with public visibility
        projectService.get(projectId);
        return dao.getSpanFeedbackScoreNames(projectId, type)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getExperimentsFeedbackScoreNames(Set<UUID> experimentIds) {
        return dao.getExperimentsFeedbackScoreNames(experimentIds)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getProjectsFeedbackScoreNames(Set<UUID> projectIds) {
        return dao.getProjectsFeedbackScoreNames(projectIds)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<Void> scoreBatchOfThreads(@NonNull List<FeedbackScoreBatchItem> scores) {
        return processThreadsScoreBatch(scores);
    }

    @Override
    public Mono<Void> deleteThreadScores(@NonNull String projectName, @NonNull String threadId,
            @NonNull Set<String> names) {
        Preconditions.checkArgument(!StringUtils.isBlank(projectName), "Project name cannot be blank");
        Preconditions.checkArgument(!StringUtils.isBlank(threadId), "Thread ID cannot be blank");

        if (names.isEmpty()) {
            log.info("No names provided for deletion of scores for threadId '{}' in projectName '{}'", threadId,
                    projectName);
            return Mono.empty();
        }

        return getProject(projectName)
                .flatMap(projectId -> traceThreadService.getThreadModelId(projectId, threadId)
                        .flatMap(threadModelId -> dao.deleteByEntityIdAndNames(EntityType.THREAD, threadModelId, names))
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

    private Mono<Void> processThreadsScoreBatch(List<FeedbackScoreBatchItem> scores) {

        if (scores.isEmpty()) {
            log.info("No scores provided for batch processing of threads");
            return Mono.empty();
        }

        // group scores by project name to resolve project itemIds
        Map<String, List<FeedbackScoreBatchItem>> scoresPerProject = scores
                .stream()
                .map(score -> score.toBuilder()
                        .projectName(WorkspaceUtils.getProjectName(score.projectName()))
                        .build())
                .collect(groupingBy(FeedbackScoreBatchItem::projectName));

        return projectService.retrieveByNamesOrCreate(scoresPerProject.keySet())
                .map(ProjectService::groupByName)
                .map(projectMap -> mergeProjectsAndScores(projectMap, scoresPerProject))
                .flatMap(this::saveThreadScoreBatch) // save all scores
                .doOnSuccess(count -> log.info("Saved '{}' thread scores in batch", count))
                .then();
    }

    private Mono<Long> saveThreadScoreBatch(List<ProjectDto> projects) {
        return Flux.fromIterable(projects)
                .flatMap(projectDto -> {
                    // Collect unique thread IDs from the scores
                    Set<String> threadIds = projectDto.scores()
                            .stream()
                            .map(FeedbackScoreBatchItem::threadId)
                            .collect(Collectors.toSet());

                    return Flux.fromIterable(threadIds)
                            // resolve thread model IDs for each thread ID
                            .flatMap(threadId -> getOrCreateThread(projectDto, threadId))
                            .collectMap(Map.Entry::getKey, Map.Entry::getValue)
                            .map(threadIdMap -> bindThreadModelId(projectDto, threadIdMap))
                            .filter(projectDtoWithThreads -> !projectDtoWithThreads.scores().isEmpty())
                            // score the batch of threads with resolved thread model IDs
                            .flatMap(score -> dao.scoreBatchOfThreads(score.scores()));
                })
                .reduce(0L, Long::sum);
    }

    private Mono<Map.Entry<String, UUID>> getOrCreateThread(ProjectDto projectDto, String threadId) {
        return traceThreadService.getOrCreateThreadId(projectDto.project().id(), threadId)
                .map(threadModelId -> Map.entry(threadId, threadModelId));
    }

    private ProjectDto bindThreadModelId(ProjectDto projectDto, Map<String, UUID> threadIdMap) {
        return projectDto.toBuilder()
                .project(projectDto.project())
                .scores(projectDto.scores()
                        .stream()
                        .map(score -> score.toBuilder()
                                .id(threadIdMap.get(score.threadId())) // set thread model id
                                .build())
                        .toList())
                .build();
    }
}
