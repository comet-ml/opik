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
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AutomationRuleEvaluatorServiceImpl.class)
public interface AutomationRuleEvaluatorService {

    <E, T extends AutomationRuleEvaluator<E>> T save(T automationRuleEvaluator, @NonNull String workspaceId, @NonNull String userName);

    void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId, @NonNull String userName,
                AutomationRuleEvaluatorUpdate automationRuleEvaluator);

    <E, T extends AutomationRuleEvaluator<E>> T findById(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId);

    void delete(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId);

    void delete(Set<UUID> ids, @NonNull UUID projectId, @NonNull String workspaceId);

    void deleteByProject(@NonNull UUID projectId, @NonNull String workspaceId);

    AutomationRuleEvaluator.AutomationRuleEvaluatorPage find(int page, int size, @NonNull UUID projectId, @NonNull String workspaceId);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AutomationRuleEvaluatorServiceImpl implements AutomationRuleEvaluatorService {

    private static final String EVALUATOR_ALREADY_EXISTS = "AutomationRuleEvaluator already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final int DEFAULT_PAGE_LIMIT = 10;

    @Override

    public <E, T extends AutomationRuleEvaluator<E>> T save(T inputRuleEvaluator,
                                                            @NonNull String workspaceId,
                                                            @NonNull String userName) {

        log.info(inputRuleEvaluator.toString());
        UUID id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "AutomationRuleEvaluator");

        return template.inTransaction(WRITE, handle -> {
            var evaluatorsDAO = handle.attach(AutomationRuleEvaluatorDAO.class);

            AutomationRuleEvaluatorModel<?> evaluator = switch (inputRuleEvaluator) {
                case AutomationRuleEvaluator.AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> {
                    var definition = llmAsJudge.toBuilder()
                        .id(id)
                        .createdBy(userName)
                        .lastUpdatedBy(userName)
                        .build();

                    log.info(definition.toString());

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }

            };

            try {
                evaluatorsDAO.saveBaseRule(evaluator, workspaceId);
                evaluatorsDAO.saveEvaluator(evaluator);

                return findById(evaluator.id(), evaluator.projectId(), workspaceId);

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
    public void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId, @NonNull String userName,
            @NonNull AutomationRuleEvaluatorUpdate evaluatorUpdate) {

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);

            try {
                int resultBase = dao.updateBaseRule(id, projectId, workspaceId, evaluatorUpdate.samplingRate(), userName);
                int resultEval = dao.updateEvaluator(id, evaluatorUpdate, userName);

                if (resultEval == 0 || resultBase == 0) {
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
    public <E, T extends AutomationRuleEvaluator<E>> T findById(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId) {
        log.debug("Finding AutomationRuleEvaluator with id '{}', projectId '{}'", id, projectId);
        return (T) template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var singleIdSet = Collections.singleton(id);
            return dao.find(singleIdSet, projectId, workspaceId, AutomationRule.AutomationRuleAction.EVALUATOR, 0, DEFAULT_PAGE_LIMIT)
                    .stream()
                    .findFirst()
                    .map(ruleEvaluator -> switch (ruleEvaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge -> AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                    })
                    .orElseThrow(this::newNotFoundException);
        });
    }

    /**
     * Deletes a AutomationRuleEvaluator.
     **/
    @Override
    public void delete(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId) {
        Set<UUID> singleIdSet = Collections.singleton(id);
        delete(singleIdSet, projectId, workspaceId);
    }

    @Override
    public void delete(Set<UUID> ids, @NonNull UUID projectId, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("ids list is empty, returning");
            return;
        }

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            dao.deleteEvaluatorsByIds(ids, projectId, workspaceId);
            dao.deleteBaseRules(ids, projectId, workspaceId);
            return null;
        });
    }

    /**
     * Explicit method to make sure there will be no deleting of all project evaluators by mistake using delete()
     * with an empty list.
     *
     * @param projectId the project to delete all evaluators
     * @param workspaceId workspace the project belongs
     */
    @Override
    public void deleteByProject(@NonNull UUID projectId, @NonNull String workspaceId) {
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            dao.deleteEvaluatorsByIds(null, projectId, workspaceId);
            dao.deleteBaseRules(null, projectId, workspaceId);
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
    public AutomationRuleEvaluator.AutomationRuleEvaluatorPage find(int pageNum, int size, @NonNull UUID projectId, @NonNull String workspaceId) {

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var total = dao.findCount(projectId, workspaceId, AutomationRule.AutomationRuleAction.EVALUATOR);

            var offset = (pageNum - 1) * size;
            var automationRuleEvaluators = dao.find(Collections.emptySet(), projectId, workspaceId, AutomationRule.AutomationRuleAction.EVALUATOR, offset, size)
                            .stream()
                            .map(evaluator -> switch (evaluator) {
                                case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                                        AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                            })
                            .toList();
            log.info("Found {} AutomationRuleEvaluators for projectId '{}'", automationRuleEvaluators.size(),
                    projectId);
            return new AutomationRuleEvaluator.AutomationRuleEvaluatorPage(pageNum, automationRuleEvaluators.size(), total,
                    automationRuleEvaluators);

        });
    }

}
