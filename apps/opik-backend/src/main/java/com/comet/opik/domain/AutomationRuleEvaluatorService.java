package com.comet.opik.domain;

import com.comet.opik.api.AutomationRuleEvaluator;
import com.comet.opik.api.AutomationRuleEvaluatorCriteria;
import com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorType;
import com.comet.opik.api.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import reactor.core.publisher.Mono;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.AutomationRuleEvaluator.AutomationRuleEvaluatorPage;
import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AutomationRuleEvaluatorServiceImpl.class)
public interface AutomationRuleEvaluatorService {

    <E, T extends AutomationRuleEvaluator<E>> T save(T automationRuleEvaluator, @NonNull UUID projectId,
            @NonNull String workspaceId, @NonNull String userName);

    void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId, @NonNull String userName,
            AutomationRuleEvaluatorUpdate<?> automationRuleEvaluator);

    <E, T extends AutomationRuleEvaluator<E>> T findById(@NonNull UUID id, UUID projectId,
            @NonNull String workspaceId);

    void delete(@NonNull Set<UUID> ids, @NonNull UUID projectId, @NonNull String workspaceId);

    AutomationRuleEvaluatorPage find(UUID projectId, @NonNull String workspaceId,
            String name, int page, int size);

    <E, T extends AutomationRuleEvaluator<E>> List<T> findAll(@NonNull UUID projectId, @NonNull String workspaceId,
            AutomationRuleEvaluatorType automationRuleEvaluatorType);

    Mono<LogPage> getLogs(LogCriteria criteria);
}

@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class AutomationRuleEvaluatorServiceImpl implements AutomationRuleEvaluatorService {

    private static final String EVALUATOR_ALREADY_EXISTS = "AutomationRuleEvaluator already exists";

    private final @NonNull IdGenerator idGenerator;
    private final @NonNull TransactionTemplate template;
    private final @NonNull AutomationRuleEvaluatorLogsDAO logsDAO;

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_by_type", key = "$projectId +'-'+ $workspaceId  +'-'+ $inputRuleEvaluator.type")
    public <E, T extends AutomationRuleEvaluator<E>> T save(@NonNull T inputRuleEvaluator,
            @NonNull UUID projectId,
            @NonNull String workspaceId,
            @NonNull String userName) {

        UUID id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "AutomationRuleEvaluator");

        var savedEvaluator = template.inTransaction(WRITE, handle -> {
            var evaluatorsDAO = handle.attach(AutomationRuleEvaluatorDAO.class);

            AutomationRuleEvaluatorModel<?> evaluator = switch (inputRuleEvaluator) {
                case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> {
                    var definition = llmAsJudge.toBuilder()
                            .id(id)
                            .projectId(projectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorUserDefinedMetricPython userDefinedMetricPython -> {
                    var definition = userDefinedMetricPython.toBuilder()
                            .id(id)
                            .projectId(projectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
            };

            try {
                log.debug("Creating {} AutomationRuleEvaluator with id '{}' in projectId '{}' and workspaceId '{}'",
                        evaluator.type(), id, evaluator.projectId(), workspaceId);

                evaluatorsDAO.saveBaseRule(evaluator, workspaceId);
                evaluatorsDAO.saveEvaluator(evaluator);

                return evaluator;
            } catch (UnableToExecuteStatementException e) {
                if (e.getCause() instanceof SQLIntegrityConstraintViolationException) {
                    log.info(EVALUATOR_ALREADY_EXISTS, e);
                    throw new EntityAlreadyExistsException(new ErrorMessage(List.of(EVALUATOR_ALREADY_EXISTS)));
                } else {
                    throw e;
                }
            }
        });

        return findById(savedEvaluator.id(), savedEvaluator.projectId(), workspaceId);
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_by_type", key = "$projectId +'-'+ $workspaceId  +'-*'", keyUsesPatternMatching = true)
    public void update(@NonNull UUID id, @NonNull UUID projectId, @NonNull String workspaceId,
            @NonNull String userName, @NonNull AutomationRuleEvaluatorUpdate<?> evaluatorUpdate) {

        log.debug("Updating AutomationRuleEvaluator with id '{}' in projectId '{}' and workspaceId '{}'", id, projectId,
                workspaceId);
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);

            try {
                int resultBase = dao.updateBaseRule(id, projectId, workspaceId, evaluatorUpdate.getName(),
                        evaluatorUpdate.getSamplingRate());

                AutomationRuleEvaluatorModel<?> modelUpdate = switch (evaluatorUpdate) {
                    case AutomationRuleEvaluatorUpdateLlmAsJudge evaluatorUpdateLlmAsJudge ->
                        LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE.map(evaluatorUpdateLlmAsJudge.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    case AutomationRuleEvaluatorUpdateUserDefinedMetricPython evaluatorUpdateUserDefinedMetricPython ->
                        UserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                };

                int resultEval = dao.updateEvaluator(id, modelUpdate);

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
    public <E, T extends AutomationRuleEvaluator<E>> T findById(@NonNull UUID id, UUID projectId,
            @NonNull String workspaceId) {
        log.debug("Finding AutomationRuleEvaluator with id '{}' in projectId '{}' and workspaceId '{}'", id, projectId,
                workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var singleIdSet = Collections.singleton(id);
            var criteria = AutomationRuleEvaluatorCriteria.builder().ids(singleIdSet).build();
            return dao.find(workspaceId, projectId, criteria)
                    .stream()
                    .findFirst()
                    .map(ruleEvaluator -> switch (ruleEvaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                    })
                    .map(evaluator -> (T) evaluator)
                    .orElseThrow(this::newNotFoundException);
        });
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_by_type", key = "$projectId +'-'+ $workspaceId  +'-*'", keyUsesPatternMatching = true)
    public void delete(@NonNull Set<UUID> ids, @NonNull UUID projectId, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("Delete AutomationRuleEvaluator: ids list is empty, returning");
            return;
        }

        log.debug("Deleting AutomationRuleEvaluators with ids {} in projectId '{}' and workspaceId '{}'", ids,
                projectId, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            dao.deleteEvaluatorsByIds(workspaceId, projectId, ids);
            dao.deleteBaseRules(ids, projectId, workspaceId);
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
    public AutomationRuleEvaluatorPage find(UUID projectId,
            @NonNull String workspaceId,
            String name,
            int pageNum, int size) {

        log.debug("Finding AutomationRuleEvaluators with name pattern '{}' in projectId '{}' and workspaceId '{}'",
                name, projectId, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder().name(name).build();
            var total = dao.findCount(workspaceId, projectId, criteria);
            var offset = (pageNum - 1) * size;

            List<AutomationRuleEvaluator<?>> automationRuleEvaluators = List.copyOf(
                    dao.find(workspaceId, projectId, criteria, offset, size)
                            .stream()
                            .map(evaluator -> switch (evaluator) {
                                case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                                case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                            })
                            .toList());

            log.info("Found {} AutomationRuleEvaluators for projectId '{}'", automationRuleEvaluators.size(),
                    projectId);
            return new AutomationRuleEvaluatorPage(pageNum, automationRuleEvaluators.size(),
                    total,
                    automationRuleEvaluators);
        });
    }

    @Override
    @Cacheable(name = "automation_rule_evaluators_find_by_type", key = "$projectId +'-'+ $workspaceId  +'-'+  $type", returnType = AutomationRuleEvaluator.class, wrapperType = List.class)
    public <E, T extends AutomationRuleEvaluator<E>> List<T> findAll(
            @NonNull UUID projectId, @NonNull String workspaceId, @NonNull AutomationRuleEvaluatorType type) {
        log.debug("Finding AutomationRuleEvaluators with type '{}' in projectId '{}' and workspaceId '{}'", type,
                projectId, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder().type(type).build();

            return dao.find(workspaceId, projectId, criteria)
                    .stream()
                    .map(evaluator -> switch (evaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                    })
                    .toList();

        });
    }

    @Override
    public Mono<LogPage> getLogs(@NonNull LogCriteria criteria) {
        return logsDAO.findLogs(criteria);
    }

}
