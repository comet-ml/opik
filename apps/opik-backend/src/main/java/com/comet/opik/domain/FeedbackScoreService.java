package com.comet.opik.domain;

import com.clickhouse.client.ClickHouseException;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.error.IdentifierMismatchException;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplate;
import com.comet.opik.infrastructure.redis.LockService;
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

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;

import static com.comet.opik.domain.FeedbackScoreDAO.EntityType;
import static com.comet.opik.infrastructure.db.TransactionTemplate.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplate.WRITE;
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
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class FeedbackScoreServiceImpl implements FeedbackScoreService {

    private static final String SPAN_SCORE_KEY = "span-score-%s";
    private static final String TRACE_SCORE_KEY = "trace-score-%s";

    private final @NonNull FeedbackScoreDAO dao;
    private final @NonNull ru.vyarus.guicey.jdbi3.tx.TransactionTemplate syncTemplate;
    private final @NonNull TransactionTemplate asyncTemplate;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull LockService lockService;

    record ProjectDto(Project project, List<FeedbackScoreBatchItem> scores) {
    }

    @Override
    public Mono<Void> scoreTrace(@NonNull UUID traceId, @NonNull FeedbackScore score) {
        return lockService.executeWithLock(
                new LockService.Lock(traceId, TRACE_SCORE_KEY.formatted(score.name())),
                Mono.defer(() -> asyncTemplate
                        .nonTransaction(connection -> dao.scoreEntity(EntityType.TRACE, traceId, score, connection))))
                .flatMap(this::extractResult)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithTraceNotFound(traceId))))
                .then();
    }

    @Override
    public Mono<Void> scoreSpan(@NonNull UUID spanId, @NonNull FeedbackScore score) {
        return lockService.executeWithLock(
                new LockService.Lock(spanId, SPAN_SCORE_KEY.formatted(score.name())),
                Mono.defer(() -> asyncTemplate
                        .nonTransaction(connection -> dao.scoreEntity(EntityType.SPAN, spanId, score, connection))))
                .flatMap(this::extractResult)
                .switchIfEmpty(Mono.defer(() -> Mono.error(failWithSpanNotFound(spanId))))
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

        return handleProjectRetrieval(scoresPerProject)
                .map(this::groupByName)
                .map(projectMap -> mergeProjectsAndScores(projectMap, scoresPerProject))
                .flatMap(projects -> processScoreBatch(entityType, projects, scores.size())) // score all scores
                .onErrorResume(e -> tryHandlingException(entityType, e))
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

    private Mono<Long> tryHandlingException(EntityType entityType, Throwable e) {
        return switch (e) {
            case ClickHouseException clickHouseException -> {
                //TODO: Find a better way to handle this.
                // This is a workaround to handle the case when project_id from score and project_name from project does not match.
                if (clickHouseException.getMessage().contains("TOO_LARGE_STRING_SIZE") &&
                        clickHouseException.getMessage().contains("_CAST(project_id, FixedString(36))")) {
                    yield failWithConflict("project_name from score and project_id from %s does not match"
                            .formatted(entityType.getType()));
                }
                yield Mono.error(e);
            }
            default -> Mono.error(e);
        };
    }

    private Mono<Long> failWithConflict(String message) {
        return Mono.error(new IdentifierMismatchException(new ErrorMessage(List.of(message))));
    }

    private Mono<Long> processScoreBatch(EntityType entityType, List<ProjectDto> projects, int actualBatchSize) {
        return Flux.fromIterable(projects)
                .flatMap(projectDto -> {
                    var lock = new LockService.Lock(projectDto.project().id(), "%s-scores-batch".formatted(entityType));

                    Mono<Long> batchProcess = Mono.defer(() -> asyncTemplate.nonTransaction(
                            connection -> dao.scoreBatchOf(entityType, projectDto.scores(), connection)));

                    return lockService.executeWithLock(lock, batchProcess);
                })
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
        return lockService.executeWithLock(
                new LockService.Lock(id, SPAN_SCORE_KEY.formatted(name)),
                Mono.defer(() -> asyncTemplate
                        .nonTransaction(connection -> dao.deleteScoreFrom(EntityType.SPAN, id, name, connection))));
    }

    @Override
    public Mono<Void> deleteTraceScore(UUID id, String name) {
        return lockService.executeWithLock(
                new LockService.Lock(id, TRACE_SCORE_KEY.formatted(name)),
                Mono.defer(() -> asyncTemplate
                        .nonTransaction(connection -> dao.deleteScoreFrom(EntityType.TRACE, id, name, connection))));
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
