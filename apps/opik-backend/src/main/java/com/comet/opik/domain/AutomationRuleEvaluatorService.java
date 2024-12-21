package com.comet.opik.domain;

import com.comet.opik.api.AutomationRule;
import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AutomationRuleEvaluatorServiceImpl.class)
public interface AutomationRuleEvaluatorService {

    AutomationRuleEvaluator save(AutomationRuleEvaluator AutomationRuleEvaluator, @NonNull String workspaceId, @NonNull String userName);

    Optional<AutomationRuleEvaluator> getById(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId);

    void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId, @NonNull String userName,
                AutomationRuleEvaluatorUpdate AutomationRuleEvaluator);

    AutomationRuleEvaluator findById(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId);

    void deleteByProject(@NonNull UUID projectId, @NonNull String workspaceId);

    void delete(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId);

    void delete(Set<UUID> ids, @NonNull UUID projectId, @NonNull String workspaceId);

    AutomationRuleEvaluator.AutomationRuleEvaluatorPage find(int page, int size, @NonNull UUID projectId, @NonNull String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AutomationRuleEvaluatorServiceImpl implements AutomationRuleEvaluatorService {

    private static final String EVALUATOR_ALREADY_EXISTS = "AutomationRuleEvaluator already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;

    @Override
    public AutomationRuleEvaluator save(@NonNull AutomationRuleEvaluator ruleEvaluator,
                                        @NonNull String workspaceId,
                                        @NonNull String userName) {

        var builder = ruleEvaluator.id() == null
                ? ruleEvaluator.toBuilder().id(idGenerator.generateId())
                : ruleEvaluator.toBuilder();

        builder.createdBy(userName)
               .lastUpdatedBy(userName);

        var evaluatorToSave = builder.build();

        IdGenerator.validateVersion(evaluatorToSave.id(), "AutomationRuleEvaluator");

        return template.inTransaction(WRITE, handle -> {
            var evaluatorsDAO = handle.attach(AutomationRuleEvaluatorDAO.class);

            try {
                evaluatorsDAO.saveBaseRule(evaluatorToSave, workspaceId);
                evaluatorsDAO.save(evaluatorToSave);

                return evaluatorsDAO
                        .findById(evaluatorToSave.id(), evaluatorToSave.projectId(), workspaceId)
                        .orElseThrow();
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(e.getMessage());
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
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var AutomationRuleEvaluator = dao.findById(id, projectId, workspaceId);
            log.info("Got AutomationRuleEvaluator with id '{}', workspaceId '{}'", id, projectId);
            return AutomationRuleEvaluator;
        });
    }

    @Override
    public void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull AutomationRuleEvaluatorUpdate evaluatorUpdate) {

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);

            try {
                dao.updateBaseRule(id, projectId, workspaceId, evaluatorUpdate.samplingRate(), userName);
                int result = dao.update(id, evaluatorUpdate);

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
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var AutomationRuleEvaluator = dao.findById(id, projectId, workspaceId)
                    .orElseThrow(this::newNotFoundException);
            log.info("Found AutomationRuleEvaluator with id '{}', projectId '{}'", id, projectId);
            return AutomationRuleEvaluator;
        });
    }

    /**
     * Deletes a AutomationRuleEvaluator.
     **/
    @Override
    public void delete(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            dao.delete(id, projectId, workspaceId);
            return null;
        });
    }

    @Override
    public void deleteByProject(@NonNull UUID projectId, @NonNull String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            dao.deleteByProject(projectId, workspaceId, AutomationRule.AutomationRuleAction.EVALUATOR);
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
            handle.attach(AutomationRuleEvaluatorDAO.class).delete(ids, projectId, workspaceId);
            return null;
        });
    }

    @Override
    public AutomationRuleEvaluator.AutomationRuleEvaluatorPage find(int pageNum, int size, @NonNull UUID projectId, @NonNull String workspaceId) {

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var total = dao.findCount(projectId, workspaceId);
            var offset = (pageNum - 1) * size;
            var automationRuleEvaluators = dao.find(size, offset, projectId, workspaceId);
            log.info("Found {} AutomationRuleEvaluators for projectId '{}'", automationRuleEvaluators.size(),
                    projectId);
            return new AutomationRuleEvaluator.AutomationRuleEvaluatorPage(pageNum, automationRuleEvaluators.size(), total,
                    automationRuleEvaluators);
        });
    }

}
