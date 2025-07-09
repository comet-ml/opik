package com.comet.opik.domain;

import com.comet.opik.api.DashboardPanel;
import com.comet.opik.api.ReusablePanelTemplate;
import com.comet.opik.api.ReusablePanelTemplateUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.fasterxml.jackson.databind.JsonNode;
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
public class ReusablePanelTemplateServiceImpl implements ReusablePanelTemplateService {

    private static final ErrorMessage TEMPLATE_ALREADY_EXISTS = new ErrorMessage(
            List.of("Reusable panel template with given name already exists"));

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;

    @Override
    public ReusablePanelTemplate create(@NonNull ReusablePanelTemplate panelTemplate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        UUID id = idGenerator.generateId();

        try {
            return template.inTransaction(WRITE, handle -> {
                var dao = handle.attach(ReusablePanelTemplateDAO.class);

                // Convert configuration and defaultLayout to JsonNode
                JsonNode configJson = null;
                JsonNode layoutJson = null;

                try {
                    if (panelTemplate.configuration() != null) {
                        configJson = panelTemplate.configuration();
                    }
                    if (panelTemplate.defaultLayout() != null) {
                        layoutJson = panelTemplate.defaultLayout();
                    }
                } catch (IllegalArgumentException e) {
                    throw new RuntimeException("Error processing configuration or layout - invalid input format", e);
                }

                dao.insert(id, panelTemplate.name(), panelTemplate.description(),
                        panelTemplate.type().name().toLowerCase(), configJson, layoutJson,
                        workspaceId, userName);

                return findById(id);
            });
        } catch (UnableToExecuteStatementException e) {
            if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                throw new EntityAlreadyExistsException(TEMPLATE_ALREADY_EXISTS);
            }
            throw e;
        }
    }

    @Override
    public ReusablePanelTemplate update(@NonNull UUID id, @NonNull ReusablePanelTemplateUpdate templateUpdate) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ReusablePanelTemplateDAO.class);

            // Get existing template
            ReusablePanelTemplate existing = findById(id);

            // Use existing values if update values are null
            String name = templateUpdate.name() != null ? templateUpdate.name() : existing.name();
            String description = templateUpdate.description() != null
                    ? templateUpdate.description()
                    : existing.description();
            DashboardPanel.PanelType type = templateUpdate.type() != null ? templateUpdate.type() : existing.type();
            JsonNode configuration = templateUpdate.configuration() != null
                    ? templateUpdate.configuration()
                    : existing.configuration();
            JsonNode defaultLayout = templateUpdate.defaultLayout() != null
                    ? templateUpdate.defaultLayout()
                    : existing.defaultLayout();

            int updated = dao.update(id, name, description, type.name().toLowerCase(),
                    configuration, defaultLayout, workspaceId, userName);
            if (updated == 0) {
                throw new NotFoundException("Reusable panel template not found");
            }

            return findById(id);
        });
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ReusablePanelTemplateDAO.class);
            dao.delete(id, workspaceId);
            return null;
        });
    }

    @Override
    public void delete(@NonNull Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(ReusablePanelTemplateDAO.class);
            dao.delete(ids, workspaceId);
            return null;
        });
    }

    @Override
    public ReusablePanelTemplate findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ReusablePanelTemplateDAO.class);
            return dao.findById(id, workspaceId)
                    .orElseThrow(() -> new NotFoundException("Reusable panel template not found"));
        });
    }

    @Override
    public List<ReusablePanelTemplate> findAll() {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ReusablePanelTemplateDAO.class);
            return dao.findAll(workspaceId);
        });
    }

    @Override
    public List<ReusablePanelTemplate> findByType(@NonNull DashboardPanel.PanelType type) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(ReusablePanelTemplateDAO.class);
            return dao.findByType(workspaceId, type.name().toLowerCase());
        });
    }
}