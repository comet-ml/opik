package com.comet.opik.domain;

import com.comet.opik.api.DashboardTemplate;
import com.comet.opik.api.DashboardTemplateUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class DashboardTemplateServiceImpl implements DashboardTemplateService {

    private static final String DASHBOARD_TEMPLATE_ALREADY_EXISTS = "Dashboard template already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public DashboardTemplate create(@NonNull DashboardTemplate dashboardTemplate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID id = idGenerator.generateId();

        try {
            return template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(DashboardTemplateDAO.class);

                dao.insert(id, dashboardTemplate.name(), dashboardTemplate.description(),
                        dashboardTemplate.configuration(), workspaceId, userName);

                return findById(id);
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new EntityAlreadyExistsException(new ErrorMessage(List.of(DASHBOARD_TEMPLATE_ALREADY_EXISTS)));
            }
            throw e;
        }
    }

    @Override
    public DashboardTemplate update(@NonNull UUID id, @NonNull DashboardTemplateUpdate dashboardTemplateUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardTemplateDAO.class);

            // Get existing template
            DashboardTemplate existing = findById(id);

            // Update with new values or keep existing ones
            String name = dashboardTemplateUpdate.name() != null ? dashboardTemplateUpdate.name() : existing.name();
            String description = dashboardTemplateUpdate.description() != null
                    ? dashboardTemplateUpdate.description()
                    : existing.description();
            var configuration = dashboardTemplateUpdate.configuration() != null
                    ? dashboardTemplateUpdate.configuration()
                    : existing.configuration();

            int updated = dao.update(id, name, description, configuration, workspaceId, userName);
            if (updated == 0) {
                throw new NotFoundException("Dashboard template not found");
            }

            return findById(id);
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(DashboardTemplateDAO.class);
            dao.delete(id, workspaceId);
            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            handle.attach(DashboardTemplateDAO.class).delete(ids, workspaceId);
            return null;
        });
    }

    @Override
    public DashboardTemplate findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardTemplateDAO.class);
            return dao.findById(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException("Dashboard template not found"));
        });
    }

    @Override
    public List<DashboardTemplate> findAll() {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(DashboardTemplateDAO.class);
            return dao.findAll(workspaceId);
        });
    }
}