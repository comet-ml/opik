package com.comet.opik.domain.evaluators;

import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.AutomationRuleEvaluatorSortingFactory;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.filter.FilterQueryBuilder;
import com.comet.opik.domain.filter.FilterStrategy;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ServerErrorException;
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
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.LogItem.LogPage;
import static com.comet.opik.api.evaluators.AutomationRuleEvaluator.AutomationRuleEvaluatorPage;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(AutomationRuleEvaluatorServiceImpl.class)
public interface AutomationRuleEvaluatorService {

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T save(T automationRuleEvaluator,
            @NonNull Set<UUID> projectIds,
            @NonNull String workspaceId, @NonNull String userName);

    void update(@NonNull UUID id, @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull String userName,
            AutomationRuleEvaluatorUpdate<?, ?> automationRuleEvaluator);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T findById(@NonNull UUID id, Set<UUID> projectIds,
            @NonNull String workspaceId);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findByIds(@NonNull Set<UUID> ids,
            Set<UUID> projectIds,
            @NonNull String workspaceId);

    void delete(@NonNull Set<UUID> ids, Set<UUID> projectIds, @NonNull String workspaceId);

    AutomationRuleEvaluatorPage find(int page, int size,
            @NonNull AutomationRuleEvaluatorSearchCriteria searchCriteria,
            @NonNull String workspaceId,
            @NonNull List<String> sortableBy);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(@NonNull UUID projectId,
            @NonNull String workspaceId);

    <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(@NonNull UUID projectId,
            @NonNull String workspaceId, AutomationRuleEvaluatorType type);

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
    private final @NonNull OpikConfiguration opikConfiguration;
    private final @NonNull FilterQueryBuilder filterQueryBuilder;
    private final @NonNull AutomationRuleEvaluatorSortingFactory sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "$projectIds + '-' + $workspaceId")
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T save(@NonNull T inputRuleEvaluator,
            @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull String userName) {

        UUID id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "AutomationRuleEvaluator");

        var savedEvaluator = template.inTransaction(WRITE, handle -> {
            var evaluatorsDAO = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);

            AutomationRuleEvaluatorModel<?> evaluator = switch (inputRuleEvaluator) {
                case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> {
                    var definition = llmAsJudge.toBuilder()
                            .id(id)
                            .projectIds(projectIds)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorUserDefinedMetricPython userDefinedMetricPython -> {
                    if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                        throw new ServerErrorException("Python evaluator is disabled", 501);
                    }
                    var definition = userDefinedMetricPython.toBuilder()
                            .id(id)
                            .projectIds(projectIds)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorTraceThreadLlmAsJudge traceThreadLlmAsJudge -> {
                    var definition = traceThreadLlmAsJudge.toBuilder()
                            .id(id)
                            .projectIds(projectIds)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython userDefinedMetricPython -> {
                    if (!opikConfiguration.getServiceToggles().isTraceThreadPythonEvaluatorEnabled()) {
                        throw new ServerErrorException("Python evaluator is disabled", 501);
                    }
                    var definition = userDefinedMetricPython.toBuilder()
                            .id(id)
                            .projectIds(projectIds)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmAsJudge -> {
                    var definition = spanLlmAsJudge.toBuilder()
                            .id(id)
                            .projectIds(projectIds)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
            };

            try {
                log.debug("Creating {} AutomationRuleEvaluator with id '{}' in projectIds '{}' and workspaceId '{}'",
                        evaluator.type(), id, evaluator.projectIds(), workspaceId);

                evaluatorsDAO.saveBaseRule(evaluator, workspaceId);
                evaluatorsDAO.saveEvaluator(evaluator);

                // Save project associations
                for (UUID projectId : projectIds) {
                    projectsDAO.saveRuleProjects(id, Set.of(projectId), workspaceId);
                }

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

        return findById(savedEvaluator.id(), savedEvaluator.projectIds(), workspaceId);
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "$projectIds + '-' + $workspaceId")
    public void update(@NonNull UUID id, @NonNull Set<UUID> projectIds, @NonNull String workspaceId,
            @NonNull String userName, @NonNull AutomationRuleEvaluatorUpdate<?, ?> evaluatorUpdate) {

        log.debug("Updating AutomationRuleEvaluator with id '{}' in projectIds '{}' and workspaceId '{}'", id,
                projectIds,
                workspaceId);
        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);

            try {
                String filtersJson = AutomationModelEvaluatorMapper.INSTANCE.map(evaluatorUpdate.getFilters());
                int resultBase = dao.updateBaseRule(id, workspaceId, evaluatorUpdate.getName(),
                        evaluatorUpdate.getSamplingRate(), evaluatorUpdate.isEnabled(), filtersJson);

                // Update project associations
                projectsDAO.deleteByRuleId(id, workspaceId);
                for (UUID projectId : projectIds) {
                    projectsDAO.saveRuleProjects(id, Set.of(projectId), workspaceId);
                }

                AutomationRuleEvaluatorModel<?> modelUpdate = switch (evaluatorUpdate) {
                    case AutomationRuleEvaluatorUpdateLlmAsJudge evaluatorUpdateLlmAsJudge ->
                        LlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE.map(evaluatorUpdateLlmAsJudge.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    case AutomationRuleEvaluatorUpdateUserDefinedMetricPython evaluatorUpdateUserDefinedMetricPython -> {
                        if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                            throw new ServerErrorException("Python evaluator is disabled", 501);
                        }
                        yield UserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    }
                    case AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge evaluatorUpdateTraceThreadLlmAsJudge ->
                        TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateTraceThreadLlmAsJudge.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    case AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython evaluatorUpdateTraceThreadUserDefinedMetricPython -> {
                        if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                            throw new ServerErrorException("Python evaluator is disabled", 501);
                        }
                        yield TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateTraceThreadUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    }
                    case AutomationRuleEvaluatorUpdateSpanLlmAsJudge evaluatorUpdateSpanLlmAsJudge ->
                        SpanLlmAsJudgeAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateSpanLlmAsJudge.getCode()))
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
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T findById(@NonNull UUID id,
            Set<UUID> projectIds,
            @NonNull String workspaceId) {
        log.debug("Finding AutomationRuleEvaluator with id '{}' in projectIds '{}' and workspaceId '{}'", id,
                projectIds,
                workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var singleIdSet = Collections.singleton(id);
            var criteria = AutomationRuleEvaluatorCriteria.builder().ids(singleIdSet).build();
            List<UUID> projectIdsList = projectIds != null ? new java.util.ArrayList<>(projectIds) : null;
            return dao.find(workspaceId, projectIdsList, criteria)
                    .stream()
                    .findFirst()
                    .map(ruleEvaluator -> switch (ruleEvaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                        case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                        case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                        case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                    })
                    .map(evaluator -> (T) evaluator)
                    .orElseThrow(this::newNotFoundException);
        });
    }

    @Override
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findByIds(@NonNull Set<UUID> ids,
            Set<UUID> projectIds,
            @NonNull String workspaceId) {
        log.debug("Finding AutomationRuleEvaluators with ids '{}' in projectIds '{}' and workspaceId '{}'", ids,
                projectIds, workspaceId);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder().ids(ids).build();
            List<UUID> projectIdsList = projectIds != null ? new java.util.ArrayList<>(projectIds) : null;
            return dao.find(workspaceId, projectIdsList, criteria)
                    .stream()
                    .map(ruleEvaluator -> switch (ruleEvaluator) {
                        case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                        case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                        case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                        case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                        case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                    })
                    .map(evaluator -> (T) evaluator)
                    .toList();
        });
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "$projectIds + '-' + $workspaceId")
    public void delete(@NonNull Set<UUID> ids, Set<UUID> projectIds, @NonNull String workspaceId) {
        if (ids.isEmpty()) {
            log.info("Delete AutomationRuleEvaluator: ids list is empty, returning");
            return;
        }

        log.debug("Deleting AutomationRuleEvaluators with ids {} in projectIds '{}' and workspaceId '{}'", ids,
                projectIds, workspaceId);

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);
            dao.deleteEvaluatorsByIds(workspaceId, ids);
            projectsDAO.deleteByRuleIds(ids, workspaceId);
            dao.deleteBaseRules(ids, workspaceId);
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
    public AutomationRuleEvaluatorPage find(int pageNum, int size,
            @NonNull AutomationRuleEvaluatorSearchCriteria searchCriteria,
            @NonNull String workspaceId,
            @NonNull List<String> sortableBy) {

        log.debug("Finding AutomationRuleEvaluators with searchCriteria '{}' in workspaceId '{}'",
                searchCriteria, workspaceId);

        String filtersSQL = Optional.ofNullable(searchCriteria.filters())
                .flatMap(f -> filterQueryBuilder.toAnalyticsDbFilters(f, FilterStrategy.AUTOMATION_RULE_EVALUATOR))
                .orElse(null);

        Map<String, Object> filterMapping = Optional.ofNullable(searchCriteria.filters())
                .map(filterQueryBuilder::toStateSQLMapping)
                .orElse(Map.of());

        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(
                searchCriteria.sortingFields(),
                sortingFactory.getFieldMapping());

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleEvaluatorDAO.class);
            var criteria = AutomationRuleEvaluatorCriteria.builder()
                    .id(searchCriteria.id())
                    .name(searchCriteria.name())
                    .filters(searchCriteria.filters())
                    .build();
            var total = dao.findCount(workspaceId, searchCriteria.projectId(), criteria);
            var offset = (pageNum - 1) * size;

            List<AutomationRuleEvaluator<?, ?>> automationRuleEvaluators = List.copyOf(
                    dao.find(workspaceId, searchCriteria.projectId(), criteria, sortingFieldsSql, filtersSQL,
                            filterMapping, offset, size)
                            .stream()
                            .map(evaluator -> switch (evaluator) {
                                case LlmAsJudgeAutomationRuleEvaluatorModel llmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(llmAsJudge);
                                case UserDefinedMetricPythonAutomationRuleEvaluatorModel userDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(userDefinedMetricPython);
                                case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                                case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                                case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                            })
                            .toList());

            log.info("Found {} AutomationRuleEvaluators for searchCriteria '{}'", automationRuleEvaluators.size(),
                    searchCriteria);
            return AutomationRuleEvaluatorPage.builder()
                    .page(pageNum)
                    .size(automationRuleEvaluators.size())
                    .total(total)
                    .content(automationRuleEvaluators)
                    .sortableBy(sortableBy)
                    .build();
        });
    }

    @Override
    @Cacheable(name = "automation_rule_evaluators_find_all", key = "$projectId + '-' + $workspaceId", returnType = AutomationRuleEvaluator.class, wrapperType = List.class)
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(
            @NonNull UUID projectId, @NonNull String workspaceId) {
        return findAll(projectId, workspaceId, null);
    }

    @Override
    @Cacheable(name = "automation_rule_evaluators_find_all", key = "$projectId + '-' + $workspaceId + '-' + ($type != null ? $type : 'all')", returnType = AutomationRuleEvaluator.class, wrapperType = List.class)
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> List<T> findAll(
            @NonNull UUID projectId, @NonNull String workspaceId, AutomationRuleEvaluatorType type) {
        log.info("Finding AutomationRuleEvaluators, projectId '{}', workspaceId '{}', type '{}'", projectId,
                workspaceId, type);
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
                        case TraceThreadLlmAsJudgeAutomationRuleEvaluatorModel traceThreadLlmAsJudge ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadLlmAsJudge);
                        case TraceThreadUserDefinedMetricPythonAutomationRuleEvaluatorModel traceThreadUserDefinedMetricPython ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(traceThreadUserDefinedMetricPython);
                        case SpanLlmAsJudgeAutomationRuleEvaluatorModel spanLlmAsJudge ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(spanLlmAsJudge);
                    })
                    .toList();
        });
    }

    @Override
    public Mono<LogPage> getLogs(@NonNull LogCriteria criteria) {
        return logsDAO.findLogs(criteria)
                .collectList()
                .map(logs -> LogPage.builder()
                        .content(logs)
                        .page(Optional.ofNullable(criteria.page()).orElse(1))
                        .total(logs.size())
                        .size(logs.size())
                        .build());
    }
}
