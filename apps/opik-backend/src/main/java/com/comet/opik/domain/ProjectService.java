package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.Project.ProjectPage;
import com.comet.opik.api.ProjectCriteria;
import com.comet.opik.api.ProjectIdLastUpdated;
import com.comet.opik.api.ProjectStatsSummary;
import com.comet.opik.api.ProjectUpdate;
import com.comet.opik.api.Visibility;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingFactoryProjects;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.domain.stats.StatsMapper;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.utils.BinaryOperatorUtils;
import com.comet.opik.utils.ErrorUtils;
import com.comet.opik.utils.PaginationUtils;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.ProjectStats.ProjectStatItem;
import static com.comet.opik.api.ProjectStatsSummary.ProjectStatsSummaryItem;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static com.comet.opik.utils.AsyncUtils.makeMonoContextAware;
import static java.util.Collections.reverseOrder;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static java.util.stream.Collectors.toUnmodifiableSet;

@ImplementedBy(ProjectServiceImpl.class)
public interface ProjectService {

    String DEFAULT_PROJECT = "Default Project";
    String DEFAULT_WORKSPACE_NAME = "default";
    String DEFAULT_WORKSPACE_ID = "0190babc-62a0-71d2-832a-0feffa4676eb";
    String DEFAULT_USER = "admin";

    Project create(Project project);

    Project update(UUID id, ProjectUpdate project);

    Project get(UUID id);

    Project get(UUID id, String workspaceId);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    Page<Project> find(int page, int size, ProjectCriteria criteria, List<SortingField> sortingFields);

    List<Project> findByIds(String workspaceId, Set<UUID> ids);

    List<Project> findByNames(String workspaceId, List<String> names);

    Mono<Project> getOrCreate(String projectName);

    Project retrieveByName(String projectName);

    Mono<List<Project>> retrieveByNamesOrCreate(Set<String> projectNames);

    void recordLastUpdatedTrace(String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces);

    UUID resolveProjectIdAndVerifyVisibility(UUID projectId, String projectName);

    ProjectStatsSummary getStats(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull List<SortingField> sortingFields);

    static Map<String, Project> groupByName(List<Project> projects) {
        return projects.stream().collect(Collectors.toMap(
                Project::name,
                Function.identity(),
                BinaryOperatorUtils.last(),
                () -> new TreeMap<>(String.CASE_INSENSITIVE_ORDER)));
    }
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class ProjectServiceImpl implements ProjectService {

    record ProjectRecordSet(List<Project> content, long total) {
    }

    private static final String PROJECT_ALREADY_EXISTS = "Project already exists";
    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull TraceDAO traceDAO;
    private final @NonNull TransactionTemplateAsync transactionTemplateAsync;
    private final @NonNull SortingFactoryProjects sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    private NotFoundException createNotFoundError() {
        String message = "Project not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    public Project create(@NonNull Project project) {
        UUID projectId = idGenerator.generateId();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        return createProject(project, projectId, userName, workspaceId);
    }

    private Project createProject(Project project, UUID projectId, String userName, String workspaceId) {
        IdGenerator.validateVersion(projectId, "project");

        var newProject = project.toBuilder()
                .id(projectId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .build();

        try {
            template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(ProjectDAO.class);

                repository.save(workspaceId, newProject);

                return newProject;
            });

            return get(newProject.id(), workspaceId);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    private EntityAlreadyExistsException newConflict() {
        log.info(PROJECT_ALREADY_EXISTS);
        return new EntityAlreadyExistsException(new ErrorMessage(List.of(PROJECT_ALREADY_EXISTS)));
    }

    @Override
    public Project update(@NonNull UUID id, @NonNull ProjectUpdate projectUpdate) {
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        try {
            template.inTransaction(WRITE, handle -> {

                var repository = handle.attach(ProjectDAO.class);

                Project project = repository.fetch(id, workspaceId)
                        .orElseThrow(this::createNotFoundError);

                repository.update(project.id(),
                        workspaceId,
                        projectUpdate.name(),
                        projectUpdate.description(),
                        projectUpdate.visibility(),
                        userName);

                return null;
            });

            return get(id, workspaceId);
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw newConflict();
            } else {
                throw e;
            }
        }
    }

    @Override
    public Project get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return Optional.of(get(id, workspaceId))
                .flatMap(this::verifyVisibility)
                .orElseThrow(() -> ErrorUtils.failWithNotFound("Project", id));
    }

    @Override
    public Project get(@NonNull UUID id, @NonNull String workspaceId) {
        Project project = template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.fetch(id, workspaceId).orElseThrow(this::createNotFoundError);
        });

        Map<UUID, Instant> lastUpdatedTraceAt = transactionTemplateAsync
                .nonTransaction(connection -> traceDAO.getLastUpdatedTraceAt(Set.of(id), workspaceId, connection))
                .block();

        return project.toBuilder()
                .lastUpdatedTraceAt(lastUpdatedTraceAt.get(project.id()))
                .build();
    }

    @Override
    public ProjectStatsSummary getStats(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull List<SortingField> sortingFields) {

        String workspaceId = requestContext.get().getWorkspaceId();

        List<UUID> projectIds = find(page, size, criteria, sortingFields)
                .content()
                .stream()
                .map(Project::id)
                .toList();

        Map<UUID, Map<String, Object>> projectStats = getProjectStats(projectIds, workspaceId);

        return ProjectStatsSummary.builder()
                .content(
                        projectIds.stream()
                                .map(projectId -> getStats(projectId, projectStats.get(projectId)))
                                .toList())
                .build();
    }

    private ProjectStatsSummaryItem getStats(UUID projectId, Map<String, Object> projectStats) {
        return ProjectStatsSummaryItem.builder()
                .projectId(projectId)
                .feedbackScores(StatsMapper.getStatsFeedbackScores(projectStats))
                .duration(StatsMapper.getStatsDuration(projectStats))
                .totalEstimatedCost(StatsMapper.getStatsTotalEstimatedCost(projectStats))
                .usage(StatsMapper.getStatsUsage(projectStats))
                .traceCount(StatsMapper.getStatsTraceCount(projectStats))
                .guardrailsFailedCount(StatsMapper.getStatsGuardrailsFailedCount(projectStats))
                .build();
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {

            var repository = handle.attach(ProjectDAO.class);
            Optional<Project> project = repository.fetch(id, workspaceId);

            if (project.isEmpty()) {
                // Void return
                return null;
            }

            repository.delete(id, workspaceId);

            // Void return
            return null;
        });
    }

    @Override
    public void delete(Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            handle.attach(ProjectDAO.class).delete(ids, workspaceId);
            return null;
        });
    }

    @Override
    public Page<Project> find(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull List<SortingField> sortingFields) {

        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();

        if (!sortingFields.isEmpty() && sortingFields.getFirst().field().equals(SortableFields.LAST_UPDATED_TRACE_AT)) {
            return findWithLastTraceSorting(page, size, criteria, sortingFields.getFirst());
        }

        ProjectRecordSet projectRecordSet = template.inTransaction(READ_ONLY, handle -> {

            ProjectDAO repository = handle.attach(ProjectDAO.class);

            int offset = (page - 1) * size;

            return new ProjectRecordSet(
                    repository.find(size, offset, workspaceId, criteria.projectName(), visibility,
                            sortingQueryBuilder.toOrderBySql(sortingFields)),
                    repository.findCount(workspaceId, criteria.projectName(), visibility));
        });

        if (projectRecordSet.content().isEmpty()) {
            return ProjectPage.empty(page);
        }

        Map<UUID, Instant> projectLastUpdatedTraceAtMap = transactionTemplateAsync.nonTransaction(connection -> {
            Set<UUID> projectIds = projectRecordSet.content().stream().map(Project::id).collect(toSet());
            return traceDAO.getLastUpdatedTraceAt(projectIds, workspaceId, connection);
        }).block();

        List<Project> projects = projectRecordSet.content()
                .stream()
                .map(project -> {
                    Instant lastUpdatedTraceAt = projectLastUpdatedTraceAtMap.get(project.id());
                    return project.toBuilder()
                            .lastUpdatedTraceAt(lastUpdatedTraceAt)
                            .build();
                })
                .toList();

        return new ProjectPage(page, projects.size(), projectRecordSet.total(), projects,
                sortingFactory.getSortableFields());
    }

    private Map<UUID, Map<String, Object>> getProjectStats(List<UUID> projectIds, String workspaceId) {
        return traceDAO.getStatsByProjectIds(projectIds, workspaceId)
                .map(stats -> stats.entrySet().stream()
                        .map(entry -> {
                            Map<String, Object> statsMap = entry.getValue().stats()
                                    .stream()
                                    .collect(toMap(ProjectStatItem::getName, ProjectStatItem::getValue));

                            return Map.entry(entry.getKey(), statsMap);
                        })
                        .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                .block();
    }

    @Override
    public List<Project> findByIds(String workspaceId, Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return List.of();
        }

        return template.inTransaction(READ_ONLY, handle -> handle.attach(ProjectDAO.class).findByIds(ids, workspaceId));
    }

    private Page<Project> findWithLastTraceSorting(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull SortingField sortingField) {
        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();

        // get all project ids and last updated
        List<ProjectIdLastUpdated> allProjectIdsLastUpdated = template.inTransaction(READ_ONLY, handle -> {
            ProjectDAO repository = handle.attach(ProjectDAO.class);

            return repository.getAllProjectIdsLastUpdated(workspaceId, criteria.projectName(), visibility);
        });

        if (allProjectIdsLastUpdated.isEmpty()) {
            return ProjectPage.empty(page);
        }

        // get last trace for each project id
        Set<UUID> allProjectIds = allProjectIdsLastUpdated.stream().map(ProjectIdLastUpdated::id)
                .collect(toUnmodifiableSet());

        Map<UUID, Instant> projectLastUpdatedTraceAtMap = transactionTemplateAsync
                .nonTransaction(connection -> traceDAO.getLastUpdatedTraceAt(allProjectIds, workspaceId, connection))
                .block();

        if (projectLastUpdatedTraceAtMap == null) {
            return ProjectPage.empty(page);
        }

        // sort and paginate
        List<UUID> sorted = sortByLastTrace(allProjectIdsLastUpdated, projectLastUpdatedTraceAtMap, sortingField);
        List<UUID> finalIds = PaginationUtils.paginate(page, size, sorted);

        if (CollectionUtils.isEmpty(finalIds)) {
            // pagination might return an empty list
            return ProjectPage.empty(page);
        }

        // get all project properties for the final list of ids
        Map<UUID, Project> projectsById = template.inTransaction(READ_ONLY, handle -> {
            ProjectDAO repository = handle.attach(ProjectDAO.class);

            return repository.findByIds(new HashSet<>(finalIds), workspaceId);
        }).stream().collect(Collectors.toMap(Project::id, Function.identity()));

        // compose the final projects list by the correct order and add last trace to it
        List<Project> projects = finalIds.stream()
                .map(projectsById::get)
                .map(project -> project.toBuilder()
                        .lastUpdatedTraceAt(projectLastUpdatedTraceAtMap.get(project.id()))
                        .build())
                .toList();

        return new ProjectPage(page, projects.size(), allProjectIdsLastUpdated.size(), projects,
                sortingFactory.getSortableFields());
    }

    private List<UUID> sortByLastTrace(
            @NonNull List<ProjectIdLastUpdated> allProjectIdsLastUpdated,
            @NonNull Map<UUID, Instant> projectLastUpdatedTraceAtMap,
            @NonNull SortingField sortingField) {
        // for projects with no traces - use last_updated_at
        allProjectIdsLastUpdated.forEach(
                project -> projectLastUpdatedTraceAtMap.computeIfAbsent(project.id(), key -> project.lastUpdatedAt()));

        Comparator<Map.Entry<UUID, Instant>> comparator = sortingField.direction() == Direction.DESC
                ? reverseOrder(Map.Entry.comparingByValue())
                : Map.Entry.comparingByValue();

        return projectLastUpdatedTraceAtMap.entrySet()
                .stream()
                .sorted(comparator)
                .map(Map.Entry::getKey)
                .toList();
    }

    @Override
    public List<Project> findByNames(@NonNull String workspaceId, @NonNull List<String> names) {

        if (names.isEmpty()) {
            return List.of();
        }

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.findByNames(workspaceId, names);
        });
    }

    @Override
    public Mono<Project> getOrCreate(@NonNull String projectName) {
        return makeMonoContextAware((userName, workspaceId) -> Mono
                .fromCallable(() -> getOrCreate(workspaceId, projectName, userName))
                .onErrorResume(e -> handleProjectCreationError(e, projectName, workspaceId))
                .subscribeOn(Schedulers.boundedElastic()));
    }

    private Project getOrCreate(String workspaceId, String projectName, String userName) {

        return findByNames(workspaceId, List.of(projectName))
                .stream()
                .findFirst()
                .orElseGet(() -> {
                    log.info("Creating project with name '{}' on workspaceId '{}'", projectName, workspaceId);
                    var project = Project.builder()
                            .name(projectName)
                            .visibility(Visibility.PRIVATE)
                            .build();

                    UUID projectId = idGenerator.generateId();

                    project = createProject(project, projectId, userName, workspaceId);

                    log.info("Created project with id '{}', name '{}' on workspaceId '{}'", projectId, projectName,
                            workspaceId);
                    return project;
                });
    }

    @Override
    public Project retrieveByName(@NonNull String projectName) {
        var workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.findByNames(workspaceId, List.of(projectName))
                    .stream()
                    .findFirst()
                    .map(project -> {

                        Map<UUID, Instant> projectLastUpdatedTraceAtMap = transactionTemplateAsync
                                .nonTransaction(connection -> {
                                    Set<UUID> projectIds = Set.of(project.id());
                                    return traceDAO.getLastUpdatedTraceAt(projectIds, workspaceId, connection);
                                }).block();

                        Map<UUID, Map<String, Object>> projectStats = getProjectStats(List.of(project.id()),
                                workspaceId);

                        return project.toBuilder()
                                .lastUpdatedTraceAt(projectLastUpdatedTraceAtMap.get(project.id()))
                                .feedbackScores(StatsMapper.getStatsFeedbackScores(projectStats.get(project.id())))
                                .usage(StatsMapper.getStatsUsage(projectStats.get(project.id())))
                                .duration(StatsMapper.getStatsDuration(projectStats.get(project.id())))
                                .totalEstimatedCost(
                                        StatsMapper.getStatsTotalEstimatedCost(projectStats.get(project.id())))
                                .traceCount(StatsMapper.getStatsTraceCount(projectStats.get(project.id())))
                                .guardrailsFailedCount(
                                        StatsMapper.getStatsGuardrailsFailedCount(projectStats.get(project.id())))
                                .build();
                    })
                    .orElseThrow(this::createNotFoundError);
        });
    }

    @Override
    public Mono<List<Project>> retrieveByNamesOrCreate(Set<String> projectNames) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);
            String userName = ctx.get(RequestContext.USER_NAME);

            return checkIfNeededToCreateProjectsWithContext(workspaceId, userName, projectNames) // create projects if needed
                    .then(Mono.fromCallable(() -> getAllProjectsByName(workspaceId, projectNames))
                            .subscribeOn(Schedulers.boundedElastic())); // get all project itemIds
        });
    }

    @Override
    public void recordLastUpdatedTrace(String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces) {
        template.inTransaction(WRITE,
                handle -> handle.attach(ProjectDAO.class).recordLastUpdatedTrace(workspaceId, lastUpdatedTraces));
    }

    @Override
    public UUID resolveProjectIdAndVerifyVisibility(UUID projectId, String projectName) {
        String workspaceId = requestContext.get().getWorkspaceId();

        Project project = verifyVisibility(projectId != null
                ? get(projectId)
                : findByNames(workspaceId, List.of(projectName)).stream().findFirst()
                        .orElseThrow(() -> ErrorUtils.failWithNotFoundName("Project", projectName)))
                .orElseThrow(() -> ErrorUtils.failWithNotFoundName("Project", projectName));

        return project.id();
    }

    private Optional<Project> verifyVisibility(@NonNull Project project) {
        boolean publicOnly = Optional.ofNullable(requestContext.get().getVisibility())
                .map(v -> v == Visibility.PUBLIC)
                .orElse(false);

        return Optional.of(project)
                .filter(p -> !publicOnly || p.visibility() == Visibility.PUBLIC);
    }

    private Mono<Void> checkIfNeededToCreateProjectsWithContext(String workspaceId,
            String userName, Set<String> projectNames) {

        return Mono.fromRunnable(() -> checkIfNeededToCreateProjects(projectNames, userName, workspaceId))
                .publishOn(Schedulers.boundedElastic())
                .then();
    }

    private List<Project> getAllProjectsByName(String workspaceId,
            Set<String> projectNames) {
        return template.inTransaction(READ_ONLY, handle -> {

            var projectDAO = handle.attach(ProjectDAO.class);

            return projectDAO.findByNames(workspaceId, projectNames);
        });
    }

    private void checkIfNeededToCreateProjects(Set<String> projectNames,
            String userName, String workspaceId) {

        Map<String, Project> projectsPerLowerCaseName = ProjectService.groupByName(
                getAllProjectsByName(workspaceId, projectNames));

        template.inTransaction(WRITE, handle -> {

            var projectDAO = handle.attach(ProjectDAO.class);

            projectNames
                    .stream()
                    .filter(projectName -> !projectsPerLowerCaseName.containsKey(projectName))
                    .forEach(projectName -> {
                        UUID projectId = idGenerator.generateId();
                        var newProject = Project.builder()
                                .name(projectName)
                                .visibility(Visibility.PRIVATE)
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

    private Mono<Project> handleProjectCreationError(Throwable exception, String projectName, String workspaceId) {
        return switch (exception) {
            case EntityAlreadyExistsException __ -> Mono.fromCallable(
                    () -> findByNames(workspaceId, List.of(projectName)).stream().findFirst()
                            .orElseThrow())
                    .subscribeOn(Schedulers.boundedElastic());
            default -> Mono.error(exception);
        };
    }
}
