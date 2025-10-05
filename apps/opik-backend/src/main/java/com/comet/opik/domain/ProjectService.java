package com.comet.opik.domain;

import com.comet.opik.api.Page;
import com.comet.opik.api.Project;
import com.comet.opik.api.Project.ProjectPage;
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
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.api.Project.Configuration;
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

    Map<UUID, String> findIdToNameByIds(String workspaceId, Set<UUID> ids);

    Mono<Map<UUID, Instant>> getDemoProjectIdsWithTimestamps();

    Mono<Project> getOrCreate(String projectName);

    Project retrieveByName(String projectName);

    Mono<List<Project>> retrieveByNamesOrCreate(Set<String> projectNames);

    void recordLastUpdatedTrace(String workspaceId, Collection<ProjectIdLastUpdated> lastUpdatedTraces);

    Mono<UUID> resolveProjectIdAndVerifyVisibility(UUID projectId, String projectName);

    UUID validateProjectIdentifier(UUID projectId, String projectName, String workspaceId);

    ProjectStatsSummary getStats(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull List<SortingField> sortingFields);

    void updateConfiguration(UUID projectId, Configuration configuration);

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

        //TODO: make it async
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

        // Check if sorting is by metrics fields (from ClickHouse) or database fields (from MySQL)
        boolean hasMetricsSorting = sortingFields.stream()
                .anyMatch(field -> field.field().startsWith("duration")
                        || field.field().equals("total_estimated_cost_sum")
                        || field.field().equals("trace_count")
                        || field.field().equals("error_count")
                        || field.field().startsWith("usage.")
                        || field.field().startsWith("feedback_scores"));

        if (hasMetricsSorting) {
            // Use database-level sorting for metrics (proper pagination)
            return getStatsWithMetricsSorting(page, size, criteria, sortingFields, workspaceId);
        } else {
            // Use existing MySQL-based sorting
            return getStatsWithBasicSorting(page, size, criteria, sortingFields, workspaceId);
        }
    }

    private ProjectStatsSummary getStatsWithBasicSorting(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull List<SortingField> sortingFields, String workspaceId) {

        List<Project> projects = find(page, size, criteria, sortingFields).content();
        List<UUID> projectIds = projects.stream().map(Project::id).toList();

        Map<UUID, Map<String, Object>> projectStats = getProjectStats(projectIds, workspaceId);

        List<ProjectStatsSummaryItem> items = projects.stream()
                .map(project -> getStats(project, projectStats.get(project.id())))
                .toList();

        // Get total count for pagination
        int total = countByProjectCriteria(criteria);

        return ProjectStatsSummary.builder()
                .content(items)
                .page(page)
                .size(size)
                .total(total)
                .sortableBy(List.of("name", "created_by", "created_at", "last_updated_at", "total_estimated_cost_sum",
                        "duration.p50", "duration.p90", "duration.p99", "trace_count", "error_count",
                        "usage.total_tokens", "usage.prompt_tokens", "usage.completion_tokens", "feedback_scores"))
                .build();
    }

    private ProjectStatsSummary getStatsWithMetricsSorting(int page, int size, @NonNull ProjectCriteria criteria,
            @NonNull List<SortingField> sortingFields, String workspaceId) {

        // Step 1: Get ALL projects that match criteria (no pagination yet)
        List<Project> allProjects = getAllProjectsByCriteria(criteria, workspaceId);

        if (allProjects.isEmpty()) {
            return ProjectStatsSummary.builder()
                    .content(List.of())
                    .page(page)
                    .size(size)
                    .total(0)
                    .sortableBy(List.of("name", "created_by", "created_at", "last_updated_at",
                            "total_estimated_cost_sum",
                            "duration.p50", "duration.p90", "duration.p99", "trace_count", "error_count",
                            "usage.total_tokens", "usage.prompt_tokens", "usage.completion_tokens", "feedback_scores"))
                    .build();
        }

        // Step 2: Get metrics for ALL projects
        List<UUID> allProjectIds = allProjects.stream().map(Project::id).toList();
        Map<UUID, Map<String, Object>> allProjectStats = getProjectStats(allProjectIds, workspaceId);

        // Step 3: Convert to list and sort by metrics
        // Include ALL projects, even those without traces (they'll have empty stats)
        List<ProjectStatsSummaryItem> allItems = allProjects.stream()
                .map(project -> {
                    Map<String, Object> projectStats = allProjectStats.get(project.id());
                    // Use empty map if no stats available for this project
                    if (projectStats == null) {
                        projectStats = Map.of();
                    }
                    return getStats(project, projectStats); // Use Project object to get name
                })
                .collect(Collectors.toList());

        // Step 4: Apply database-level equivalent sorting
        SortingField metricSortField = sortingFields.stream()
                .filter(field -> field.field().startsWith("duration")
                        || field.field().equals("total_estimated_cost_sum")
                        || field.field().equals("trace_count")
                        || field.field().equals("error_count")
                        || field.field().startsWith("usage.")
                        || field.field().startsWith("feedback_scores"))
                .findFirst()
                .orElse(null);

        if (metricSortField != null) {
            String fieldName = metricSortField.field();
            Direction direction = metricSortField.direction();

            allItems.sort((item1, item2) -> {
                Double value1 = getMetricValue(item1, fieldName);
                Double value2 = getMetricValue(item2, fieldName);

                // Handle null values (put them last in both ASC and DESC)
                if (value1 == null && value2 == null) return 0;
                if (value1 == null) return 1;
                if (value2 == null) return -1;

                int comparison = Double.compare(value1, value2);
                return direction == Direction.DESC ? -comparison : comparison;
            });
        }

        // Step 5: Apply pagination to the sorted results
        int total = allItems.size();
        int fromIndex = (page - 1) * size; // Convert from 1-based to 0-based indexing
        int toIndex = Math.min(fromIndex + size, total);

        List<ProjectStatsSummaryItem> paginatedItems = fromIndex < total
                ? allItems.subList(fromIndex, toIndex)
                : List.of();

        return ProjectStatsSummary.builder()
                .content(paginatedItems)
                .page(page)
                .size(size)
                .total(total)
                .sortableBy(List.of("name", "created_by", "created_at", "last_updated_at", "total_estimated_cost_sum",
                        "duration.p50", "duration.p90", "duration.p99", "trace_count", "error_count",
                        "usage.total_tokens", "usage.prompt_tokens", "usage.completion_tokens", "feedback_scores"))
                .build();
    }

    @Override
    public void updateConfiguration(@NonNull UUID projectId, @NonNull Configuration configuration) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        Project project = get(projectId);
    }

    private ProjectStatsSummaryItem getStats(Project project, Map<String, Object> projectStats) {
        return ProjectStatsSummaryItem.builder()
                .projectId(project.id())
                .name(project.name())
                .visibility(project.visibility())
                .description(project.description())
                .createdAt(project.createdAt())
                .createdBy(project.createdBy())
                .lastUpdatedAt(project.lastUpdatedAt())
                .lastUpdatedBy(project.lastUpdatedBy())
                .lastUpdatedTraceAt(project.lastUpdatedTraceAt())
                .feedbackScores(StatsMapper.getStatsFeedbackScores(projectStats))
                .duration(StatsMapper.getStatsDuration(projectStats))
                .totalEstimatedCost(StatsMapper.getStatsTotalEstimatedCost(projectStats))
                .totalEstimatedCostSum(StatsMapper.getStatsTotalEstimatedCostSum(projectStats))
                .usage(StatsMapper.getStatsUsage(projectStats))
                .traceCount(StatsMapper.getStatsTraceCount(projectStats))
                .guardrailsFailedCount(StatsMapper.getStatsGuardrailsFailedCount(projectStats))
                .errorCount(StatsMapper.getStatsErrorCount(projectStats))
                .build();
    }

    private List<Project> getAllProjectsByCriteria(@NonNull ProjectCriteria criteria, String workspaceId) {
        Visibility visibility = requestContext.get().getVisibility();

        return template.inTransaction(READ_ONLY, handle -> {
            ProjectDAO repository = handle.attach(ProjectDAO.class);
            return repository.getAllProjectIdsLastUpdated(workspaceId, criteria.projectName(), visibility)
                    .stream()
                    .map(projectIdLastUpdated -> {
                        try {
                            return repository.fetch(projectIdLastUpdated.id(), workspaceId).orElse(null);
                        } catch (Exception e) {
                            // Log error but continue processing other projects
                            log.warn("Failed to fetch project with id: {}", projectIdLastUpdated.id(), e);
                            return null;
                        }
                    })
                    .filter(project -> project != null) // Filter out any null projects
                    .toList();
        });
    }

    private int countByProjectCriteria(@NonNull ProjectCriteria criteria) {
        String workspaceId = requestContext.get().getWorkspaceId();
        Visibility visibility = requestContext.get().getVisibility();

        return template.inTransaction(READ_ONLY, handle -> {
            ProjectDAO repository = handle.attach(ProjectDAO.class);
            return (int) repository.findCount(workspaceId, criteria.projectName(), visibility);
        });
    }

    private Double getMetricValue(ProjectStatsSummaryItem item, String fieldName) {
        return switch (fieldName) {
            case "total_estimated_cost_sum" -> {
                Double cost = item.totalEstimatedCostSum();
                // Treat 0.0 as null for sorting purposes (same as usage tokens behavior)
                yield (cost != null && cost == 0.0) ? null : cost;
            }
            case "duration.p50" -> item.duration() != null && item.duration().p50() != null
                    ? item.duration().p50().doubleValue()
                    : null;
            case "duration.p90" -> item.duration() != null && item.duration().p90() != null
                    ? item.duration().p90().doubleValue()
                    : null;
            case "duration.p99" -> item.duration() != null && item.duration().p99() != null
                    ? item.duration().p99().doubleValue()
                    : null;
            case "trace_count" -> item.traceCount() != null ? item.traceCount().doubleValue() : null;
            case "error_count" -> item.errorCount() != null ? (double) item.errorCount().count() : null;
            case "usage.total_tokens" -> getUsageValue(item, "total_tokens");
            case "usage.prompt_tokens" -> getUsageValue(item, "prompt_tokens");
            case "usage.completion_tokens" -> getUsageValue(item, "completion_tokens");
            case "feedback_scores" -> getAverageFeedbackScoreValue(item);
            default -> {
                // Handle feedback_scores.* pattern
                if (fieldName.startsWith("feedback_scores.")) {
                    String scoreName = fieldName.substring("feedback_scores.".length());
                    yield getFeedbackScoreValue(item, scoreName);
                }
                yield null;
            }
        };
    }

    private Double getUsageValue(ProjectStatsSummaryItem item, String tokenType) {
        if (item.usage() == null) return null;
        Object value = item.usage().get(tokenType);
        return value instanceof Number number ? number.doubleValue() : null;
    }

    private Double getFeedbackScoreValue(ProjectStatsSummaryItem item, String scoreName) {
        if (item.feedbackScores() == null) return null;
        return item.feedbackScores().stream()
                .filter(score -> score.name().equals(scoreName))
                .map(score -> score.value().doubleValue())
                .findFirst()
                .orElse(null);
    }

    private Double getAverageFeedbackScoreValue(ProjectStatsSummaryItem item) {
        if (item.feedbackScores() == null || item.feedbackScores().isEmpty()) return null;

        // Calculate average of all feedback scores for this project
        return item.feedbackScores().stream()
                .mapToDouble(score -> score.value().doubleValue())
                .average()
                .orElse(0.0);
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

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
        String userName = requestContext.get().getUserName();

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

        // Check if sorting is by metrics fields (from ClickHouse) or database fields (from MySQL)
        boolean hasMetricsSorting = sortingFields.stream()
                .anyMatch(field -> field.field().startsWith("duration")
                        || field.field().equals("total_estimated_cost_sum")
                        || field.field().equals("trace_count")
                        || field.field().equals("error_count")
                        || field.field().startsWith("usage.")
                        || field.field().startsWith("feedback_scores"));

        // If metrics sorting is requested, delegate to getStats() method which handles this properly
        if (hasMetricsSorting) {
            ProjectStatsSummary stats = getStats(page, size, criteria, sortingFields);

            // Convert ProjectStatsSummaryItem to Project
            // ProjectStatsSummaryItem includes all Project fields plus stats
            // The JSON serialization will include all fields
            List<Project> projects = stats.content().stream()
                    .map(item -> Project.builder()
                            .id(item.projectId())
                            .name(item.name())
                            .visibility(item.visibility())
                            .description(item.description())
                            .createdAt(item.createdAt())
                            .createdBy(item.createdBy())
                            .lastUpdatedAt(item.lastUpdatedAt())
                            .lastUpdatedBy(item.lastUpdatedBy())
                            .lastUpdatedTraceAt(item.lastUpdatedTraceAt())
                            .feedbackScores(item.feedbackScores())
                            .duration(item.duration())
                            .totalEstimatedCost(item.totalEstimatedCost())
                            .totalEstimatedCostSum(item.totalEstimatedCostSum())
                            .usage(item.usage())
                            .traceCount(item.traceCount())
                            .guardrailsFailedCount(item.guardrailsFailedCount())
                            .errorCount(item.errorCount())
                            .build())
                    .toList();

            return new ProjectPage(
                    stats.page(),
                    projects.size(),
                    stats.total(),
                    projects,
                    sortingFactory.getSortableFields());
        }

        // Create final sorting fields for use in lambda
        final List<SortingField> finalSortingFields = sortingFields;

        ProjectRecordSet projectRecordSet = template.inTransaction(READ_ONLY, handle -> {

            ProjectDAO repository = handle.attach(ProjectDAO.class);

            int offset = (page - 1) * size;

            return new ProjectRecordSet(
                    repository.find(size, offset, workspaceId, criteria.projectName(), visibility,
                            sortingQueryBuilder.toOrderBySql(finalSortingFields)),
                    repository.findCount(workspaceId, criteria.projectName(), visibility));
        });

        if (projectRecordSet.content().isEmpty()) {
            return ProjectPage.empty(page);
        }

        Set<UUID> projectIds = projectRecordSet.content().stream().map(Project::id).collect(toSet());

        Map<UUID, Instant> projectLastUpdatedTraceAtMap = transactionTemplateAsync
                .nonTransaction(connection -> traceDAO.getLastUpdatedTraceAt(projectIds, workspaceId, connection))
                .block();

        // Get project stats from ClickHouse
        Map<UUID, Map<String, Object>> projectStatsMap = getProjectStats(projectIds.stream().toList(), workspaceId);

        List<Project> projects = projectRecordSet.content()
                .stream()
                .map(project -> {
                    Instant lastUpdatedTraceAt = projectLastUpdatedTraceAtMap.get(project.id());
                    Map<String, Object> projectStats = projectStatsMap.get(project.id());

                    // Build project with stats
                    return project.toBuilder()
                            .lastUpdatedTraceAt(lastUpdatedTraceAt)
                            .feedbackScores(
                                    projectStats != null ? StatsMapper.getStatsFeedbackScores(projectStats) : null)
                            .duration(projectStats != null ? StatsMapper.getStatsDuration(projectStats) : null)
                            .totalEstimatedCost(
                                    projectStats != null ? StatsMapper.getStatsTotalEstimatedCost(projectStats) : null)
                            .totalEstimatedCostSum(projectStats != null
                                    ? StatsMapper.getStatsTotalEstimatedCostSum(projectStats)
                                    : null)
                            .usage(projectStats != null ? StatsMapper.getStatsUsage(projectStats) : null)
                            .traceCount(projectStats != null ? StatsMapper.getStatsTraceCount(projectStats) : null)
                            .guardrailsFailedCount(projectStats != null
                                    ? StatsMapper.getStatsGuardrailsFailedCount(projectStats)
                                    : null)
                            .errorCount(projectStats != null ? StatsMapper.getStatsErrorCount(projectStats) : null)
                            .build();
                })
                .toList();

        return new ProjectPage(page, projects.size(), projectRecordSet.total(), projects,
                sortingFactory.getSortableFields());
    }

    private Map<UUID, Map<String, Object>> getProjectStats(List<UUID> projectIds, String workspaceId) {
        // Batch project IDs to avoid query size limits (max 1000 per batch)
        final int BATCH_SIZE = 1000;

        if (projectIds.size() <= BATCH_SIZE) {
            // Small list - process directly
            Map<UUID, Map<String, Object>> result = traceDAO.getStatsByProjectIds(projectIds, workspaceId)
                    .map(stats -> stats.entrySet().stream()
                            .map(entry -> {
                                Map<String, Object> statsMap = entry.getValue().stats()
                                        .stream()
                                        .collect(toMap(ProjectStatItem::getName, ProjectStatItem::getValue));

                                return Map.entry(entry.getKey(), statsMap);
                            })
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .block();

            // Return empty map if no stats found
            return result != null ? result : Map.of();
        }

        // Large list - batch and merge results
        Map<UUID, Map<String, Object>> allStats = new HashMap<>();

        for (int i = 0; i < projectIds.size(); i += BATCH_SIZE) {
            int endIndex = Math.min(i + BATCH_SIZE, projectIds.size());
            List<UUID> batch = projectIds.subList(i, endIndex);

            Map<UUID, Map<String, Object>> batchStats = traceDAO.getStatsByProjectIds(batch, workspaceId)
                    .map(stats -> stats.entrySet().stream()
                            .map(entry -> {
                                Map<String, Object> statsMap = entry.getValue().stats()
                                        .stream()
                                        .collect(toMap(ProjectStatItem::getName, ProjectStatItem::getValue));

                                return Map.entry(entry.getKey(), statsMap);
                            })
                            .collect(toMap(Map.Entry::getKey, Map.Entry::getValue)))
                    .block();

            if (batchStats != null) {
                allStats.putAll(batchStats);
            }
        }

        return allStats;
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
        String userName = requestContext.get().getUserName();

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

        // Get project stats from ClickHouse
        Map<UUID, Map<String, Object>> projectStatsMap = getProjectStats(finalIds, workspaceId);

        // compose the final projects list by the correct order and add last trace and stats to it
        List<Project> projects = finalIds.stream()
                .map(projectsById::get)
                .map(project -> {
                    Map<String, Object> projectStats = projectStatsMap.get(project.id());

                    // Build project with stats
                    return project.toBuilder()
                            .lastUpdatedTraceAt(projectLastUpdatedTraceAtMap.get(project.id()))
                            .feedbackScores(
                                    projectStats != null ? StatsMapper.getStatsFeedbackScores(projectStats) : null)
                            .duration(projectStats != null ? StatsMapper.getStatsDuration(projectStats) : null)
                            .totalEstimatedCost(
                                    projectStats != null ? StatsMapper.getStatsTotalEstimatedCost(projectStats) : null)
                            .totalEstimatedCostSum(projectStats != null
                                    ? StatsMapper.getStatsTotalEstimatedCostSum(projectStats)
                                    : null)
                            .usage(projectStats != null ? StatsMapper.getStatsUsage(projectStats) : null)
                            .traceCount(projectStats != null ? StatsMapper.getStatsTraceCount(projectStats) : null)
                            .guardrailsFailedCount(projectStats != null
                                    ? StatsMapper.getStatsGuardrailsFailedCount(projectStats)
                                    : null)
                            .errorCount(projectStats != null ? StatsMapper.getStatsErrorCount(projectStats) : null)
                            .build();
                })
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
    public Map<UUID, String> findIdToNameByIds(String workspaceId, Set<UUID> ids) {
        return findByIds(workspaceId, ids)
                .stream()
                .collect(Collectors.toMap(Project::id, Project::name));
    }

    public Mono<Map<UUID, Instant>> getDemoProjectIdsWithTimestamps() {
        return Mono.fromCallable(() -> this.findByGlobalNames(DemoData.PROJECTS))
                .map(projects -> projects.stream()
                        .collect(Collectors.toMap(Project::id, Project::createdAt)))
                .subscribeOn(reactor.core.scheduler.Schedulers.boundedElastic());
    }

    private List<Project> findByGlobalNames(List<String> names) {
        if (names.isEmpty()) {
            return List.of();
        }

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.findByGlobalNames(names);
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
        var userName = requestContext.get().getUserName();

        return template.inTransaction(READ_ONLY, handle -> {

            var repository = handle.attach(ProjectDAO.class);

            return repository.findByNames(workspaceId, List.of(projectName))
                    .stream();
        }).findFirst()
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
                            .totalEstimatedCostSum(
                                    StatsMapper.getStatsTotalEstimatedCostSum(projectStats.get(project.id())))
                            .traceCount(StatsMapper.getStatsTraceCount(projectStats.get(project.id())))
                            .guardrailsFailedCount(
                                    StatsMapper.getStatsGuardrailsFailedCount(projectStats.get(project.id())))
                            .errorCount(StatsMapper.getStatsErrorCount(projectStats.get(project.id())))
                            .build();
                })
                .orElseThrow(this::createNotFoundError);
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
    public Mono<UUID> resolveProjectIdAndVerifyVisibility(UUID projectId, String projectName) {
        return Mono.deferContextual(ctx -> {
            String workspaceId = ctx.get(RequestContext.WORKSPACE_ID);

            return Mono.fromCallable(() -> {

                if (projectId != null) {
                    return findByIds(workspaceId, Set.of(projectId))
                            .stream()
                            .findFirst()
                            .orElseThrow(() -> ErrorUtils.failWithNotFound("Project", projectId));
                }

                return findByNames(workspaceId, List.of(projectName))
                        .stream()
                        .findFirst()
                        .orElseThrow(() -> ErrorUtils.failWithNotFoundName("Project", projectName));

            }).subscribeOn(Schedulers.boundedElastic())
                    .flatMap(this::verifyVisibilityAsync)
                    .switchIfEmpty(Mono.error(() -> {
                        if (projectId != null) {
                            return ErrorUtils.failWithNotFound("Project", projectId);
                        }

                        return ErrorUtils.failWithNotFoundName("Project", projectName);
                    }))
                    .map(Project::id);
        });
    }

    private Optional<Project> verifyVisibility(@NonNull Project project) {
        boolean publicOnly = Optional.ofNullable(requestContext.get().getVisibility())
                .map(v -> v == Visibility.PUBLIC)
                .orElse(false);

        return Optional.of(project)
                .filter(p -> !publicOnly || p.visibility() == Visibility.PUBLIC);
    }

    private Mono<Project> verifyVisibilityAsync(@NonNull Project project) {
        return Mono.deferContextual(ctx -> {

            boolean publicOnly = Optional.<Visibility>of(ctx.get(RequestContext.VISIBILITY))
                    .map(v -> v == Visibility.PUBLIC)
                    .orElse(false);

            return Mono.justOrEmpty(Optional.of(project))
                    .filter(p -> !publicOnly || p.visibility() == Visibility.PUBLIC);
        });
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

    @Override
    public UUID validateProjectIdentifier(UUID projectId, String projectName, String workspaceId) {
        // Verify project visibility
        if (projectId != null) {
            return get(projectId).id();
        }

        // If the project name is provided, find the project by name
        return findByNames(workspaceId, List.of(projectName))
                .stream()
                .findFirst()
                .orElseThrow(() -> ErrorUtils.failWithNotFoundName("Project", projectName))
                .id();
    }
}
