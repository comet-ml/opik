package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

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
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class FeedbackScoreServiceImpl implements FeedbackScoreService {

    private final @NonNull FeedbackScoreDAO dao;
    private final @NonNull SpanDAO spanDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull ProjectService projectService;

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
}
