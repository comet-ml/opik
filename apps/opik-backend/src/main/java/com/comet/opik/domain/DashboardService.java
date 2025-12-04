package com.comet.opik.domain;

import com.comet.opik.api.Dashboard;
import com.comet.opik.api.Dashboard.DashboardPage;
import com.comet.opik.api.DashboardUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryDashboards;
import com.comet.opik.api.sorting.SortingField;
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
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(DashboardServiceImpl.class)
public interface DashboardService {

    Dashboard create(@NonNull Dashboard dashboard);

    Dashboard findById(@NonNull UUID id);

    DashboardPage find(int page, int size, String name, List<SortingField> sortingFields);

    Dashboard update(@NonNull UUID id, @NonNull DashboardUpdate dashboardUpdate);

    void delete(@NonNull UUID id);

    void delete(@NonNull Set<UUID> ids);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class DashboardServiceImpl implements DashboardService {

    private static final String DASHBOARD_NOT_FOUND = "Dashboard not found";
    private static final String DASHBOARD_ALREADY_EXISTS = "Dashboard with this name already exists";

    private final @NonNull TransactionTemplate template;
    private final @NonNull TransactionTemplateAsync templateAsync;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryDashboards sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @Override
    public Dashboard create(@NonNull Dashboard dashboard) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        // Generate ID if not provided
        var dashboardId = dashboard.id() != null ? dashboard.id() : idGenerator.generateId();
        IdGenerator.validateVersion(dashboardId, "dashboard");

        // Generate slug from name
        String baseSlug = SlugUtils.generateSlug(dashboard.name());

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            // Check for existing slugs to generate unique slug
            long existingCount = dao.countBySlugPrefix(workspaceId, baseSlug);
            String uniqueSlug = SlugUtils.generateUniqueSlug(baseSlug, existingCount);

            // Build the complete dashboard
            var newDashboard = dashboard.toBuilder()
                    .id(dashboardId)
                    .workspaceId(workspaceId)
                    .slug(uniqueSlug)
                    .createdBy(userName)
                    .lastUpdatedBy(userName)
                    .build();

            try {
                dao.save(newDashboard, workspaceId);
                log.info("Created dashboard with id '{}', name '{}', slug '{}' in workspace '{}'",
                        dashboardId, dashboard.name(), uniqueSlug, workspaceId);
                return dao.findById(dashboardId, workspaceId).orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.warn("Dashboard already exists with name '{}' in workspace '{}'", dashboard.name(),
                            workspaceId);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public Dashboard findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Finding dashboard by id '{}' in workspace '{}'", id, workspaceId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);
            return dao.findById(id, workspaceId)
                    .orElseThrow(() -> {
                        log.warn("Dashboard not found with id '{}' in workspace '{}'", id, workspaceId);
                        return new NotFoundException(DASHBOARD_NOT_FOUND);
                    });
        });
    }

    @Override
    public DashboardPage find(int page, int size, String name, List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        log.info("Finding dashboards in workspace '{}', page '{}', size '{}', name '{}'", workspaceId, page, size,
                name);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            String nameTerm = StringUtils.isNotBlank(name) ? name.trim() : null;
            int offset = (page - 1) * size;

            long total = dao.findCount(workspaceId, nameTerm);
            List<Dashboard> dashboards = dao.find(workspaceId, nameTerm, sortingFieldsSql, size, offset);

            log.info("Found '{}' dashboards in workspace '{}'", total, workspaceId);
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
    public Dashboard update(@NonNull UUID id, @NonNull DashboardUpdate dashboardUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        log.info("Updating dashboard with id '{}' in workspace '{}'", id, workspaceId);

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
                int result = dao.update(workspaceId, id, dashboardUpdate, newSlug, userName);

                if (result == 0) {
                    log.warn("Dashboard not found with id '{}' in workspace '{}'", id, workspaceId);
                    throw new NotFoundException(DASHBOARD_NOT_FOUND);
                }

                log.info("Updated dashboard with id '{}' in workspace '{}'", id, workspaceId);

                // Return updated dashboard
                return dao.findById(id, workspaceId).orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.warn("Dashboard already exists with name in workspace '{}'", workspaceId);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboard with id '{}' in workspace '{}'", id, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardDAO.class);

            int result = dao.delete(id, workspaceId);

            if (result == 0) {
                log.info("Dashboard with id '{}' not found in workspace '{}', nothing to delete", id, workspaceId);
            } else {
                log.info("Deleted dashboard with id '{}' in workspace '{}'", id, workspaceId);
            }

            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("Dashboard ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        log.info("Deleting dashboards by ids, count '{}' in workspace '{}'", ids.size(), workspaceId);

        template.inTransaction(WRITE, handle -> {
            handle.attach(DashboardDAO.class).delete(ids, workspaceId);
            return null;
        });

        log.info("Deleted dashboards by ids, count '{}' in workspace '{}'", ids.size(), workspaceId);
    }
}
