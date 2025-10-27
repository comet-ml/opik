package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardChart;
import com.comet.opik.api.DashboardType;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@ImplementedBy(DashboardServiceImpl.class)
public interface DashboardService {

    Dashboard create(Dashboard dashboard);

    Dashboard update(UUID id, Dashboard dashboard);

    Dashboard get(UUID id);

    void delete(UUID id);

    void delete(Set<UUID> ids);

    DashboardPage find(int page, int size, UUID projectId, String name, DashboardType type,
            List<SortingField> sortingFields);

    Dashboard getDefaultForProject(UUID projectId);

    void setAsDefault(UUID dashboardId, UUID projectId);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DashboardServiceImpl implements DashboardService {

    private static final String DASHBOARD_ALREADY_EXISTS = "Dashboard already exists";
    private static final String DASHBOARD_NOT_FOUND = "Dashboard not found";

    private final @NonNull TransactionTemplate template;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull DashboardChartService dashboardChartService;

    private NotFoundException createNotFoundError() {
        log.info(DASHBOARD_NOT_FOUND);
        return new NotFoundException(DASHBOARD_NOT_FOUND,
                Response.status(Response.Status.NOT_FOUND)
                        .entity(new ErrorMessage(List.of(DASHBOARD_NOT_FOUND)))
                        .build());
    }

    @Override
    public Dashboard create(@NonNull Dashboard dashboard) {
        UUID dashboardId = idGenerator.generateId();
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        Dashboard newDashboard = dashboard.toBuilder()
                .id(dashboardId)
                .workspaceId(workspaceId)
                .createdBy(userName)
                .lastUpdatedBy(userName)
                .createdAt(Instant.now())
                .lastUpdatedAt(Instant.now())
                .build();

        try {
            template.inTransaction(handle -> {
                var dashboardDAO = handle.attach(DashboardDAO.class);
                dashboardDAO.save(workspaceId, newDashboard);

                // Save project associations if any
                if (dashboard.projectIds() != null && !dashboard.projectIds().isEmpty()) {
                    var dashboardProjectDAO = handle.attach(DashboardProjectDAO.class);
                    List<UUID> ids = new ArrayList<>();
                    List<UUID> dashboardIds = new ArrayList<>();
                    List<UUID> projectIds = new ArrayList<>();

                    for (UUID projectId : dashboard.projectIds()) {
                        ids.add(idGenerator.generateId());
                        dashboardIds.add(dashboardId);
                        projectIds.add(projectId);
                    }

                    dashboardProjectDAO.saveBatch(ids, dashboardIds, projectIds);
                }

                return null;
            });

            log.info("Created dashboard with id '{}', name '{}'", dashboardId, dashboard.name());
            return newDashboard;
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                log.info("Dashboard with name '{}' already exists", dashboard.name());
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_ALREADY_EXISTS)));
            }
            throw e;
        }
    }

    @Override
    public Dashboard update(@NonNull UUID id, @NonNull Dashboard dashboard) {
        String userName = requestContext.get().getUserName();
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);

            // Verify dashboard exists
            Dashboard existing = dashboardDAO.findById(id, workspaceId);
            if (existing == null) {
                throw createNotFoundError();
            }

            // Update dashboard
            dashboardDAO.update(id, workspaceId, dashboard.name(), dashboard.description(), userName);

            // Update project associations if provided
            if (dashboard.projectIds() != null) {
                var dashboardProjectDAO = handle.attach(DashboardProjectDAO.class);

                // Remove old associations and add new ones
                dashboardProjectDAO.deleteByDashboardId(id);

                if (!dashboard.projectIds().isEmpty()) {
                    List<UUID> ids = new ArrayList<>();
                    List<UUID> dashboardIds = new ArrayList<>();
                    List<UUID> projectIds = new ArrayList<>();

                    for (UUID projectId : dashboard.projectIds()) {
                        ids.add(idGenerator.generateId());
                        dashboardIds.add(id);
                        projectIds.add(projectId);
                    }

                    dashboardProjectDAO.saveBatch(ids, dashboardIds, projectIds);
                }
            }

            log.info("Updated dashboard with id '{}'", id);
            return get(id);
        });
    }

    @Override
    public Dashboard get(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);
            Dashboard dashboard = dashboardDAO.findById(id, workspaceId);

            if (dashboard == null) {
                throw createNotFoundError();
            }

            // Load project associations
            var dashboardProjectDAO = handle.attach(DashboardProjectDAO.class);
            List<UUID> projectIds = dashboardProjectDAO.findProjectIdsByDashboardId(id);

            // Load charts
            List<DashboardChart> charts = dashboardChartService.findByDashboardId(id);

            return dashboard.toBuilder()
                    .projectIds(projectIds)
                    .charts(charts)
                    .build();
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);

            Dashboard dashboard = dashboardDAO.findById(id, workspaceId);
            if (dashboard == null) {
                throw createNotFoundError();
            }

            dashboardDAO.delete(id, workspaceId);
            log.info("Deleted dashboard with id '{}'", id);
            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);
            dashboardDAO.delete(ids, workspaceId);
            log.info("Deleted {} dashboards", ids.size());
            return null;
        });
    }

    @Override
    public DashboardPage find(int page, int size, UUID projectId, String name, DashboardType type,
            List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);

            String sortingQuery = sortingFields != null && !sortingFields.isEmpty()
                    ? sortingQueryBuilder.toOrderBySql(sortingFields)
                    : null;

            long total;
            List<Dashboard> dashboards;

            if (projectId != null) {
                // Find dashboards associated with project
                dashboards = dashboardDAO.findByProjectId(projectId, workspaceId);
                total = dashboards.size();

                // Apply pagination
                int offset = page * size;
                int end = Math.min(offset + size, dashboards.size());
                dashboards = offset < dashboards.size()
                        ? dashboards.subList(offset, end)
                        : List.of();
            } else {
                // Find all dashboards
                int offset = page * size;
                total = dashboardDAO.findCount(workspaceId, name, type);
                dashboards = dashboardDAO.find(size, offset, workspaceId, name, type, sortingQuery);
            }

            // Load project associations for each dashboard
            var dashboardProjectDAO = handle.attach(DashboardProjectDAO.class);
            List<Dashboard> enrichedDashboards = dashboards.stream()
                    .map(dashboard -> {
                        List<UUID> projectIds = dashboardProjectDAO.findProjectIdsByDashboardId(dashboard.id());
                        return dashboard.toBuilder()
                                .projectIds(projectIds)
                                .build();
                    })
                    .toList();

            return DashboardPage.builder()
                    .page(page)
                    .size(enrichedDashboards.size())
                    .total(total)
                    .content(enrichedDashboards)
                    .sortableBy(List.of("name", "created_at", "last_updated_at"))
                    .build();
        });
    }

    @Override
    public Dashboard getDefaultForProject(@NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);
            Dashboard dashboard = dashboardDAO.findDefaultByProjectId(projectId, workspaceId);

            if (dashboard == null) {
                return null;
            }

            // Load project associations
            var dashboardProjectDAO = handle.attach(DashboardProjectDAO.class);
            List<UUID> projectIds = dashboardProjectDAO.findProjectIdsByDashboardId(dashboard.id());

            // Load charts
            List<DashboardChart> charts = dashboardChartService.findByDashboardId(dashboard.id());

            return dashboard.toBuilder()
                    .projectIds(projectIds)
                    .charts(charts)
                    .build();
        });
    }

    @Override
    public void setAsDefault(@NonNull UUID dashboardId, @NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(handle -> {
            var dashboardDAO = handle.attach(DashboardDAO.class);

            // Verify dashboard exists
            Dashboard dashboard = dashboardDAO.findById(dashboardId, workspaceId);
            if (dashboard == null) {
                throw createNotFoundError();
            }

            var dashboardProjectDAO = handle.attach(DashboardProjectDAO.class);

            // Unset all defaults for this project
            dashboardProjectDAO.unsetAllDefaultsByProjectId(projectId);

            // Set this dashboard as default
            dashboardProjectDAO.setAsDefault(dashboardId, projectId);

            log.info("Set dashboard '{}' as default for project '{}'", dashboardId, projectId);
            return null;
        });
    }
}
