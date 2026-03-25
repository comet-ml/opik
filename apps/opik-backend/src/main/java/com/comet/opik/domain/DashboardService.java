package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardScope;
import com.comet.opik.api.DashboardType;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.SortingFactoryDashboards;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DashboardServiceImpl.class)
public interface DashboardService {

    Dashboard create(Dashboard dashboard, DashboardScope scope);

    Dashboard findById(UUID id, DashboardScope scope);

    Dashboard findByName(String name, UUID projectId);

    DashboardPage find(int page, int size, String name, UUID projectId, List<SortingField> sortingFields,
            List<? extends Filter> filters, DashboardScope scope);

    Dashboard update(UUID id, DashboardUpdate dashboardUpdate, DashboardScope scope);

    void delete(UUID id, DashboardScope scope);

    void delete(Set<UUID> ids, DashboardScope scope);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DashboardServiceImpl implements DashboardService {

    private static final String DASHBOARD_NOT_FOUND = "Dashboard not found";
    private static final String DASHBOARD_ALREADY_EXISTS = "Dashboard already exists";

    private final @NonNull TransactionTemplate template;
    private final @NonNull TransactionTemplateAsync templateAsync;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDashboards sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull ProjectService projectService;

    @Override
    public Dashboard create(@NonNull Dashboard dashboard, @NonNull DashboardScope scope) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Generate ID if not provided
        var dashboardId = dashboard.id() != null ? dashboard.id() : idGenerator.generateId();
        IdGenerator.validateVersion(dashboardId, "dashboard");

        final UUID resolvedProjectId;
        if (StringUtils.isNotBlank(dashboard.projectName()) && dashboard.projectId() == null) {
            var project = projectService.getOrCreate(workspaceId, dashboard.projectName(), userName);
            resolvedProjectId = project.id();
        } else {
            resolvedProjectId = dashboard.projectId();
        }

        projectService.validateProjectIdExists(resolvedProjectId, workspaceId);

        // Generate slug from name
        String baseSlug = SlugUtils.generateSlug(dashboard.name());

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            // Check for existing slugs to generate unique slug
            long existingCount = dao.countBySlugPrefix(workspaceId, baseSlug);
            String uniqueSlug = SlugUtils.generateUniqueSlug(baseSlug, existingCount);

            // Build the complete dashboard, forcing the provided scope
            var newDashboard = dashboard.toBuilder()
                    .id(dashboardId)
                    .projectId(resolvedProjectId)
                    .workspaceId(workspaceId)
                    .slug(uniqueSlug)
                    .type(Optional.ofNullable(dashboard.type()).orElse(DashboardType.MULTI_PROJECT))
                    .scope(scope)
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();

            try {
                dao.save(newDashboard, workspaceId);
                log.info("Created dashboard with id '{}', name '{}', slug '{}', scope '{}' in workspace '{}'",
                        dashboardId, dashboard.name(), uniqueSlug, scope, workspaceId);
                return dao.findById(dashboardId, workspaceId, scope.getValue()).orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.warn("Dashboard slug constraint violation in workspace '{}'", workspaceId);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public Dashboard findById(@NonNull UUID id, @NonNull DashboardScope scope) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboard by id '{}' with scope '{}' in workspace '{}'", id, scope, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            return dao.findById(id, workspaceId, scope.getValue())
                    .orElseThrow(() -> {
                        log.warn("Dashboard not found with id '{}' and scope '{}' in workspace '{}'",
                                id, scope, workspaceId);
                        return new NotFoundException(DASHBOARD_NOT_FOUND);
                    });
        });
    }

    @Override
    public Dashboard findByName(@NonNull String name, UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboard by name '{}' in workspace '{}', projectId '{}'", name, workspaceId, projectId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            return dao.findByName(workspaceId, name, projectId)
                    .or(() -> {
                        if (projectId == null) {
                            return Optional.empty();
                        }
                        return dao.findByName(workspaceId, name, null);
                    })
                    .orElseThrow(() -> {
                        log.info("Dashboard not found with name '{}' in workspace '{}'", name, workspaceId);
                        return new NotFoundException(DASHBOARD_NOT_FOUND);
                    });
        });
    }

    @Override
    public DashboardPage find(int page, int size, String name, UUID projectId, List<SortingField> sortingFields,
            List<? extends Filter> filters, @NonNull DashboardScope scope) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        log.info("Finding dashboards in workspace '{}', scope '{}', page '{}', size '{}', name '{}'",
                workspaceId, scope, page, size, name);

        String filtersSql = Optional.ofNullable(filters)
                .flatMap(f -> filterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.DASHBOARD))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(filters)
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            String nameTerm = StringUtils.isNotBlank(name) ? name.trim() : null;
            int offset = (page - 1) * size;

            long total = dao.findCount(workspaceId, nameTerm, projectId, scope.getValue(), filtersSql, filterMapping);
            List<Dashboard> dashboards = dao.find(workspaceId, nameTerm, projectId, scope.getValue(), filtersSql,
                    filterMapping, sortingFieldsSql, size, offset);

            log.info("Found '{}' dashboards with scope '{}' in workspace '{}'", total, scope, workspaceId);
            return DashboardPage.builder()
                    .content(dashboards)
                    .page(page)
                    .size(dashboards.size())
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    @Override
    public Dashboard update(@NonNull UUID id, @NonNull DashboardUpdate dashboardUpdate,
            @NonNull DashboardScope scope) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Updating dashboard with id '{}' and scope '{}' in workspace '{}'", id, scope, workspaceId);

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            // Generate new slug if name is being updated
            String newSlug = null;
            if (StringUtils.isNotBlank(dashboardUpdate.name())) {
                String baseSlug = SlugUtils.generateSlug(dashboardUpdate.name());
                long existingCount = dao.countBySlugPrefix(workspaceId, baseSlug);
                newSlug = SlugUtils.generateUniqueSlug(baseSlug, existingCount);
            }

            try {
                int result = dao.update(workspaceId, id, dashboardUpdate, newSlug, userName, scope.getValue());

                if (result == 0) {
                    log.warn("Dashboard not found with id '{}' and scope '{}' in workspace '{}'",
                            id, scope, workspaceId);
                    throw new NotFoundException(DASHBOARD_NOT_FOUND);
                }

                log.info("Updated dashboard with id '{}' and scope '{}' in workspace '{}'", id, scope, workspaceId);

                return dao.findById(id, workspaceId, scope.getValue()).orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.warn("Dashboard slug constraint violation in workspace '{}'", workspaceId);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public void delete(@NonNull UUID id, @NonNull DashboardScope scope) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboard with id '{}' and scope '{}' in workspace '{}'", id, scope, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            int result = dao.delete(id, workspaceId, scope.getValue());

            if (result == 0) {
                log.info("Dashboard with id '{}' and scope '{}' not found in workspace '{}', nothing to delete",
                        id, scope, workspaceId);
            } else {
                log.info("Deleted dashboard with id '{}' and scope '{}' in workspace '{}'", id, scope, workspaceId);
            }

            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids, @NonNull DashboardScope scope) {
        if (ids.isEmpty()) {
            log.info("Dashboard ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboards by ids, count '{}', scope '{}' in workspace '{}'",
                ids.size(), scope, workspaceId);

        template.inTransaction(WRITE, handle -> {
            handle.attach(DashboardDAO.class).delete(ids, workspaceId, scope.getValue());
            return null;
        });

        log.info("Deleted dashboards by ids, count '{}', scope '{}' in workspace '{}'",
                ids.size(), scope, workspaceId);
    }

}
