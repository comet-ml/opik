package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.BatchOperationsConfig;
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
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AutomationRuleServiceImpl.class)
public interface AutomationRuleService {

    AutomationRuleEvaluator save(AutomationRuleEvaluator AutomationRuleEvaluator, @NonNull String workspaceId);

    Optional<AutomationRuleEvaluator> getById(UUID id, UUID projectId, @NonNull String workspaceId);

    void update(UUID id, UUID projectId, @NonNull String workspaceId,
            AutomationRuleEvaluatorUpdate AutomationRuleEvaluator);

    AutomationRuleEvaluator findById(UUID id, UUID projectId, @NonNull String workspaceId);

    List<AutomationRuleEvaluator> findByProjectId(UUID projectId, @NonNull String workspaceId);

    List<AutomationRuleEvaluator> findByIds(Set<UUID> ids, UUID projectId, @NonNull String workspaceId);

    void deleteByProject(UUID projectId, @NonNull String workspaceId);

    void delete(UUID id, UUID projectId, @NonNull String workspaceId);

    void delete(Set<UUID> ids, UUID projectId, @NonNull String workspaceId);

    AutomationRuleEvaluator.AutomationRuleEvaluatorPage find(int page, int size, @NonNull UUID projectId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AutomationRuleServiceImpl implements AutomationRuleService {

    private static final String EVALUATOR_ALREADY_EXISTS = "AutomationRuleEvaluator already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;
    private final @NonNull @Config BatchOperationsConfig batchOperationsConfig;

    @Override
    public AutomationRuleEvaluator save(@NonNull AutomationRuleEvaluator automationRuleEvaluator,
            @NonNull String workspaceId) {

        var builder = automationRuleEvaluator.id() == null
                ? automationRuleEvaluator.toBuilder().id(idGenerator.generateId())
                : automationRuleEvaluator.toBuilder();

        String userName = requestContext.get().getUserName();

        builder.createdBy(userName)
               .lastUpdatedBy(userName);

        var newAutomationRuleEvaluator = builder.build();

        IdGenerator.validateVersion(newAutomationRuleEvaluator.id(), "AutomationRuleEvaluator");

        return template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);

            try {
                dao.save(newAutomationRuleEvaluator, workspaceId);
                return dao
                        .findById(newAutomationRuleEvaluator.id(), newAutomationRuleEvaluator.projectId(), workspaceId)
                        .orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(EVALUATOR_ALREADY_EXISTS);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(EVALUATOR_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });
    }

    @Override
    public Optional<AutomationRuleEvaluator> getById(@NonNull UUID id, @NonNull UUID projectId,
            @NonNull String workspaceId) {
        log.info("Getting AutomationRuleEvaluator with id '{}', workspaceId '{}'", id, projectId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            var AutomationRuleEvaluator = dao.findById(id, projectId, workspaceId);
            log.info("Got AutomationRuleEvaluator with id '{}', workspaceId '{}'", id, projectId);
            return AutomationRuleEvaluator;
        });
    }

    @Override
    public void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull AutomationRuleEvaluatorUpdate automationRuleEvaluator) {
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);

            try {
                int result = dao.update(id, projectId, workspaceId, automationRuleEvaluator, userName);

                if (result == 0) {
                    throw newNotFoundException();
                }
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(EVALUATOR_ALREADY_EXISTS);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(EVALUATOR_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }

            return null;
        });
    }

    @Override
    public AutomationRuleEvaluator findById(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId) {
        log.info("Finding AutomationRuleEvaluator with id '{}', projectId '{}'", id, projectId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            var AutomationRuleEvaluator = dao.findById(id, projectId, workspaceId)
                    .orElseThrow(this::newNotFoundException);
            log.info("Found AutomationRuleEvaluator with id '{}', projectId '{}'", id, projectId);
            return AutomationRuleEvaluator;
        });
    }

    @Override
    public List<AutomationRuleEvaluator> findByProjectId(@NonNull UUID projectId, @NonNull String workspaceId) {
        log.info("Finding AutomationRuleEvaluators with for projectId '{}'", projectId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            var automationRuleEvaluators = dao.findByProjectId(projectId, workspaceId);
            log.info("Found {} AutomationRuleEvaluators for projectId '{}'", automationRuleEvaluators.size(),
                    projectId);
            return automationRuleEvaluators;
        });
    }
    @Override
    public List<AutomationRuleEvaluator> findByIds(@NonNull Set<UUID> ids, @NonNull UUID projectId,
            @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("Returning empty AutomationRuleEvaluators for empty ids, projectId '{}'", projectId);
            return List.of();
        }
        log.info("Finding AutomationRuleEvaluators with ids '{}', projectId '{}'", ids, projectId);
        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            var automationRuleEvaluators = dao.findByIds(ids, projectId, workspaceId);
            log.info("Found AutomationRuleEvaluators with ids '{}', projectId '{}'", ids, projectId);
            return automationRuleEvaluators;
        });
    }

    /**
     * Deletes a AutomationRuleEvaluator.
     **/
    @Override
    public void delete(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            dao.delete(id, projectId, workspaceId);
            return null;
        });
    }

    @Override
    public void deleteByProject(@NonNull UUID projectId, @NonNull String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            dao.deleteByProject(projectId, workspaceId);
            return null;
        });
    }

    private NotFoundException newNotFoundException() {
        String message = "AutomationRuleEvaluator not found";
        log.info(message);
        return new NotFoundException(message,
                Response.status(Response.Status.NOT_FOUND).entity(new ErrorMessage(List.of(message))).build());
    }

    @Override
    public void delete(Set<UUID> ids, @NonNull UUID projectId, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        template.inTransaction(WRITE, handle -> {
            handle.attach(AutomationRuleDAO.class).delete(ids, projectId, workspaceId);
            return null;
        });
    }

    @Override
    public AutomationRuleEvaluator.AutomationRuleEvaluatorPage find(int page, int size, @NonNull UUID projectId) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleDAO.class);
            var total = dao.findCount(projectId, workspaceId);
            var offset = (page - 1) * size;
            var automationRuleEvaluators = dao.find(size, offset, projectId, workspaceId);
            log.info("Found {} AutomationRuleEvaluators for projectId '{}'", automationRuleEvaluators.size(),
                    projectId);
            return new AutomationRuleEvaluator.AutomationRuleEvaluatorPage(page, automationRuleEvaluators.size(), total,
                    automationRuleEvaluators);
        });
    }

}
