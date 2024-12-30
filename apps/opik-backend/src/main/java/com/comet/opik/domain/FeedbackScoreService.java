package com.comet.opik.domain;

import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreNames;
import com.comet.opik.api.Project;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.WorkspaceUtils;
import com.google.inject.ImplementedBy;
import com.google.inject.Singleton;
import jakarta.inject.Inject;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.domain.FeedbackScoreDAO.EntityType;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static java.util.stream.Collectors.groupingBy;
import static java.util.stream.Collectors.toMap;

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

    Mono<FeedbackScoreNames> getExperimentsFeedbackScoreNames(List<UUID> experimentIds);

    Mono<FeedbackScoreNames> getProjectsFeedbackScoreNames(List<UUID> projectIds);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class FeedbackScoreServiceImpl implements FeedbackScoreService {

    private final @NonNull FeedbackScoreDAO dao;
    private final @NonNull TransactionTemplate syncTemplate;
    private final @NonNull SpanDAO spanDAO;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull IdGenerator idGenerator;

    record ProjectDto(Project project, List<FeedbackScoreBatchItem> scores) {
    }

    @Override
    public Mono<Void> scoreTrace(@NonNull UUID traceId, @NonNull FeedbackScore score) {
        return traceDAO.getProjectIdFromTraces(Set.of(traceId))
                .flatMap(traceProjectIdMap -> {

                    if (traceProjectIdMap.get(traceId) == null) {
                        return Mono.error(failWithTraceNotFound(traceId));
                    }

                    return dao.scoreEntity(EntityType.TRACE, traceId, score, traceProjectIdMap)
                            .flatMap(this::extractResult)
                            .then();
                });
    }

    @Override
    public Mono<Void> scoreSpan(@NonNull UUID spanId, @NonNull FeedbackScore score) {

        return spanDAO.getProjectIdFromSpans(Set.of(spanId))
                .flatMap(spanProjectIdMap -> {

                    if (spanProjectIdMap.get(spanId) == null) {
                        return Mono.error(failWithSpanNotFound(spanId));
                    }

                    return dao.scoreEntity(EntityType.SPAN, spanId, score, spanProjectIdMap)
                            .flatMap(this::extractResult)
                            .then();
                });
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

        return handleProjectRetrieval(scoresPerProject)
                .map(this::groupByName)
                .map(projectMap -> mergeProjectsAndScores(projectMap, scoresPerProject))
                .flatMap(projects -> processScoreBatch(entityType, projects, scores.size())) // score all scores
                .then();
    }

    private Mono<List<Project>> handleProjectRetrieval(Map<String, List<FeedbackScoreBatchItem>> scoresPerProject) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return checkIfNeededToCreateProjectsWithContext(workspaceId, userName, scoresPerProject) // create projects if needed
                    .then(Mono.fromCallable(() -> getAllProjectsByName(workspaceId, scoresPerProject))
                            .subscribeOn(Schedulers.boundedElastic())); // get all project itemIds
        });
    }

    private Mono<Void> checkIfNeededToCreateProjectsWithContext(String workspaceId,
            String userName,
            Map<String, List<FeedbackScoreBatchItem>> scoresPerProject) {

        return Mono.fromRunnable(() -> checkIfNeededToCreateProjects(scoresPerProject, userName, workspaceId))
                .publishOn(Schedulers.boundedElastic())
                .then();
    }

    private Mono<Long> processScoreBatch(EntityType entityType, List<ProjectDto> projects, int actualBatchSize) {
        return Flux.fromIterable(projects)
                .flatMap(projectDto -> dao.scoreBatchOf(entityType, projectDto.scores()))
                .reduce(0L, Long::sum)
                .flatMap(rowsUpdated -> rowsUpdated == actualBatchSize ? Mono.just(rowsUpdated) : Mono.empty())
                .switchIfEmpty(Mono.defer(() -> failWithNotFound("Error while processing scores batch")));
    }

    private List<ProjectDto> mergeProjectsAndScores(Map<String, Project> projectMap,
            Map<String, List<FeedbackScoreBatchItem>> scoresPerProject) {
        return scoresPerProject.keySet()
                .stream()
                .map(projectName -> new ProjectDto(
                        projectMap.get(projectName),
                        scoresPerProject.get(projectName)
                                .stream()
                                .map(item -> item.toBuilder().projectId(projectMap.get(projectName).id()).build()) // set projectId
                                .toList()))
                .toList();
    }

    private Map<String, Project> groupByName(List<Project> projects) {
        return projects.stream().collect(toMap(Project::name, Function.identity()));
    }

    private List<Project> getAllProjectsByName(String workspaceId,
            Map<String, List<FeedbackScoreBatchItem>> scoresPerProject) {
        return syncTemplate.inTransaction(READ_ONLY, handle -> {

            var projectDAO = handle.attach(ProjectDAO.class);

            return projectDAO.findByNames(workspaceId, scoresPerProject.keySet());
        });
    }

    private void checkIfNeededToCreateProjects(Map<String, List<FeedbackScoreBatchItem>> scoresPerProject,
            String userName, String workspaceId) {

        Map<String, Project> projectsPerName = groupByName(getAllProjectsByName(workspaceId, scoresPerProject));

        syncTemplate.inTransaction(WRITE, handle -> {

            var projectDAO = handle.attach(ProjectDAO.class);

            scoresPerProject
                    .keySet()
                    .stream()
                    .filter(projectName -> !projectsPerName.containsKey(projectName))
                    .forEach(projectName -> {
                        UUID projectId = idGenerator.generateId();
                        var newProject = Project.builder()
                                .name(projectName)
                                .id(projectId)
                                .createdBy(userName)
                                .lastUpdatedBy(userName)
                                .build();

                        try {
                            projectDAO.save(workspaceId, newProject);
                        } catch (UnableToExecuteStatementException e) {
                            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                                log.warn("Project {} already exists", projectName);
                            } else {
                                throw e;
                            }
                        }
                    });

            return null;
        });
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
        return dao.getTraceFeedbackScoreNames(projectId)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getSpanFeedbackScoreNames(@NonNull UUID projectId, SpanType type) {
        return dao.getSpanFeedbackScoreNames(projectId, type)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getExperimentsFeedbackScoreNames(List<UUID> experimentIds) {
        return dao.getExperimentsFeedbackScoreNames(experimentIds)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    @Override
    public Mono<FeedbackScoreNames> getProjectsFeedbackScoreNames(List<UUID> projectIds) {
        return dao.getProjectsFeedbackScoreNames(projectIds)
                .map(names -> names.stream().map(FeedbackScoreNames.ScoreName::new).toList())
                .map(FeedbackScoreNames::new);
    }

    private Mono<Long> failWithNotFound(String errorMessage) {
        log.info(errorMessage);
        return Mono.error(new NotFoundException(Response.status(404)
                .entity(new ErrorMessage(List.of(errorMessage))).build()));
    }

    private Mono<Long> extractResult(Long rowsUpdated) {
        return rowsUpdated.equals(0L) ? Mono.empty() : Mono.just(rowsUpdated);
    }

    private Throwable failWithTraceNotFound(UUID id) {
        String message = "Trace id: %s not found".formatted(id);
        log.info(message);
        return new NotFoundException(Response.status(404)
                .entity(new ErrorMessage(List.of(message))).build());
    }

    private NotFoundException failWithSpanNotFound(UUID id) {
        String message = "Not found span with id '%s'".formatted(id);
        log.info(message);
        return new NotFoundException(message);
    }

}
