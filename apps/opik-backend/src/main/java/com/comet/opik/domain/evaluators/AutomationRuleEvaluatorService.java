package com.comet.opik.domain.evaluators;

import com.comet.opik.api.LogCriteria;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.evaluators.AutomationRuleEvaluator;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorType;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdate;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadLlmAsJudge;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateTraceThreadUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUpdateUserDefinedMetricPython;
import com.comet.opik.api.evaluators.AutomationRuleEvaluatorUserDefinedMetricPython;
import com.comet.opik.api.evaluators.ProjectReference;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.api.sorting.AutomationRuleEvaluatorSortingFactory;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.ProjectService;
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
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final @NonNull ProjectService projectService;

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
    public <E, F extends Filter, T extends AutomationRuleEvaluator<E, F>> T save(@NonNull T inputRuleEvaluator,
            @NonNull Set<UUID> projectIds, @NonNull String workspaceId, @NonNull String userName) {

        UUID id = idGenerator.generateId();
        IdGenerator.validateVersion(id, "AutomationRuleEvaluator");

        // Dual-field sync: First projectId becomes the legacy project_id field
        UUID primaryProjectId = projectIds.isEmpty() ? null : projectIds.iterator().next();

        var savedEvaluator = template.inTransaction(WRITE, handle -> {
            var evaluatorsDAO = handle.attach(AutomationRuleEvaluatorDAO.class);
            var projectsDAO = handle.attach(AutomationRuleProjectsDAO.class);

            AutomationRuleEvaluatorModel<?> evaluator = switch (inputRuleEvaluator) {
                case AutomationRuleEvaluatorLlmAsJudge llmAsJudge -> {
                    var definition = llmAsJudge.toBuilder()
                            .id(id)
                            .projectId(primaryProjectId)
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
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorTraceThreadLlmAsJudge traceThreadLlmAsJudge -> {
                    var definition = traceThreadLlmAsJudge.toBuilder()
                            .id(id)
                            .projectId(primaryProjectId)
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
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorSpanLlmAsJudge spanLlmAsJudge -> {
                    var definition = spanLlmAsJudge.toBuilder()
                            .id(id)
                            .projectId(primaryProjectId)
                            .createdBy(userName)
                            .lastUpdatedBy(userName)
                            .build();

                    yield AutomationModelEvaluatorMapper.INSTANCE.map(definition);
                }
                case AutomationRuleEvaluatorSpanUserDefinedMetricPython spanUserDefinedMetricPython -> {
                    if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                        throw new ServerErrorException("Python evaluator is disabled", 501);
                    }
                    var definition = spanUserDefinedMetricPython.toBuilder()
                            .id(id)
                            .projectId(primaryProjectId)
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
                log.debug("Saving {} project associations for rule '{}'", projectIds.size(), id);
                projectsDAO.saveRuleProjects(id, projectIds, workspaceId);

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
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
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

                // Update base rule (project associations handled separately in junction table)
                int resultBase = dao.updateBaseRule(id, workspaceId, evaluatorUpdate.getName(),
                        evaluatorUpdate.getSamplingRate(), evaluatorUpdate.isEnabled(), filtersJson);

                // Update project associations in junction table
                projectsDAO.deleteByRuleIds(Set.of(id), workspaceId);
                projectsDAO.saveRuleProjects(id, projectIds, workspaceId);

                // Clear legacy project_id field to prevent stale data
                dao.clearLegacyProjectId(id, workspaceId);

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
                    case AutomationRuleEvaluatorUpdateSpanUserDefinedMetricPython evaluatorUpdateSpanUserDefinedMetricPython -> {
                        if (!opikConfiguration.getServiceToggles().isPythonEvaluatorEnabled()) {
                            throw new ServerErrorException("Python evaluator is disabled", 501);
                        }
                        yield SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel.builder()
                                .code(AutomationModelEvaluatorMapper.INSTANCE
                                        .map(evaluatorUpdateSpanUserDefinedMetricPython.getCode()))
                                .lastUpdatedBy(userName)
                                .build();
                    }
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
            List<AutomationRuleEvaluatorModel<?>> models = findRulesWithProjects(dao, workspaceId, projectIds,
                    criteria, null, null, Map.of(), null, null);

            // Enrich models with project names for backward compatibility
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(models, workspaceId);

            return enrichedModels.stream()
                    .findFirst()
                    .map(ruleEvaluator -> (AutomationRuleEvaluator<?, ?>) switch (ruleEvaluator) {
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
                        case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
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
            List<AutomationRuleEvaluatorModel<?>> models = findRulesWithProjects(dao, workspaceId, projectIds,
                    criteria, null, null, Map.of(), null, null);

            // Enrich models with project names for backward compatibility
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(models, workspaceId);

            return enrichedModels.stream()
                    .map(ruleEvaluator -> (AutomationRuleEvaluator<?, ?>) switch (ruleEvaluator) {
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
                        case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                            AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
                    })
                    .map(evaluator -> (T) evaluator)
                    .toList();
        });
    }

    @Override
    @CacheEvict(name = "automation_rule_evaluators_find_all", key = "'*-' + $workspaceId + '-*'", keyUsesPatternMatching = true)
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

            List<AutomationRuleEvaluatorModel<?>> models = findRulesWithProjects(dao, workspaceId,
                    searchCriteria.projectId(), criteria, sortingFieldsSql, filtersSQL, filterMapping, offset, size);

            // Enrich models with project names for backward compatibility
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(models, workspaceId);

            List<AutomationRuleEvaluator<?, ?>> automationRuleEvaluators = List.copyOf(
                    enrichedModels.stream()
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
                                case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                                    AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
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

    /**
     * Find all automation rule evaluators for a project.
     * <p>
     * <strong>WARNING:</strong> Do NOT add {@code @Cacheable} annotation to this method.
     * This method delegates to the 3-parameter {@link #findAll(UUID, String, AutomationRuleEvaluatorType)}
     * which already has caching. Adding {@code @Cacheable} here would create nested cache operations
     * that cause nested {@code Mono.block()} calls, leading to reactor threading violations and
     * Redis timeout exceptions.
     * </p>
     *
     * @param projectId the project ID
     * @param workspaceId the workspace ID
     * @param <E> the entity type
     * @param <F> the filter type
     * @param <T> the automation rule evaluator type
     * @return list of automation rule evaluators
     */
    @Override
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
            var results = findRulesWithProjects(dao, workspaceId, projectId, criteria);
            log.debug("Found {} evaluators for projectId '{}', workspaceId '{}', type '{}'",
                    results.size(), projectId, workspaceId, type);

            // Enrich models with project names for backward compatibility
            List<AutomationRuleEvaluatorModel<?>> enrichedModels = enrichWithProjectNames(results, workspaceId);

            return enrichedModels
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
                        case SpanUserDefinedMetricPythonAutomationRuleEvaluatorModel spanUserDefinedMetricPython ->
                            (T) AutomationModelEvaluatorMapper.INSTANCE.map(spanUserDefinedMetricPython);
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

    private List<AutomationRuleEvaluatorModel<?>> findRulesWithProjects(
            AutomationRuleEvaluatorDAO dao,
            String workspaceId,
            Set<UUID> projectIds,
            AutomationRuleEvaluatorCriteria criteria,
            String sortingFields,
            String filters,
            Map<String, Object> filterMapping,
            Integer offset,
            Integer limit) {

        // Query 1: Get paginated rules without project data (no duplication)
        var rules = dao.findRulesWithoutProjects(workspaceId, projectIds, criteria.action(), criteria.type(),
                criteria.ids(), criteria.id(), criteria.name(), sortingFields, filters, filterMapping, offset, limit);

        if (rules.isEmpty()) {
            return List.of();
        }

        // Query 2: Bulk fetch project associations for these rules
        var ruleIds = rules.stream().map(AutomationRuleEvaluatorModel::id).toList();
        var projectMappings = dao.findProjectMappings(ruleIds, workspaceId);

        // Merge project IDs into rules with legacy fallback (business logic)
        return rules.stream()
                .<AutomationRuleEvaluatorModel<?>>map(rule -> {
                    var projectsFromJunction = projectMappings.getOrDefault(rule.id(), Set.of());

                    // Legacy fallback: If junction table is empty but rule has legacy project_id,
                    // keep the legacy value (set by row mapper)
                    if (projectsFromJunction.isEmpty() && !rule.projectIds().isEmpty()) {
                        // Rule was created before multi-project support, use legacy value
                        return rule;
                    }

                    // Use junction table data (new/updated rules)
                    return rule.withProjectIds(projectsFromJunction);
                })
                .toList();
    }

    private List<AutomationRuleEvaluatorModel<?>> findRulesWithProjects(
            AutomationRuleEvaluatorDAO dao,
            String workspaceId,
            UUID projectId,
            AutomationRuleEvaluatorCriteria criteria,
            String sortingFields,
            String filters,
            Map<String, Object> filterMapping,
            Integer offset,
            Integer limit) {
        // Backward compatibility: convert single projectId to set
        return findRulesWithProjects(dao, workspaceId,
                Optional.ofNullable(projectId).map(Set::of).orElse(null),
                criteria, sortingFields, filters, filterMapping, offset, limit);
    }

    private List<AutomationRuleEvaluatorModel<?>> findRulesWithProjects(
            AutomationRuleEvaluatorDAO dao,
            String workspaceId,
            UUID projectId,
            AutomationRuleEvaluatorCriteria criteria) {
        return findRulesWithProjects(dao, workspaceId, projectId, criteria, null, null, Map.of(), null, null);
    }

    /**
     * Enriches a list of AutomationRuleEvaluatorModel with project names resolved from their projectId.
     * This supports backwards compatibility by populating the legacy projectName field.
     *
     * @param models the models to enrich
     * @param workspaceId the workspace ID for fetching projects
     * @return the enriched models with projectName populated
     */
    private List<AutomationRuleEvaluatorModel<?>> enrichWithProjectNames(
            List<AutomationRuleEvaluatorModel<?>> models,
            String workspaceId) {

        if (models.isEmpty()) {
            return models;
        }

        // Log incoming models for debugging
        models.forEach(model -> log.debug(
                "Model before enrichment - id: '{}', projectId: '{}', projectIds: '{}'",
                model.id(), model.projectId(), model.projectIds()));

        // Extract unique project IDs from all models' projectIds sets
        Set<UUID> allProjectIds = models.stream()
                .flatMap(model -> model.projectIds().stream())
                .collect(java.util.stream.Collectors.toSet());

        if (allProjectIds.isEmpty()) {
            return models;
        }

        // Use ProjectService to fetch project names (ensures consistent logic and forward compatibility)
        Map<UUID, String> projectNameMap = projectService.findIdToNameByIds(workspaceId, allProjectIds);

        // Log enrichment details
        log.debug("Fetched '{}' project names for '{}' project IDs", projectNameMap.size(), allProjectIds.size());

        // Enrich each model with its project name
        List<AutomationRuleEvaluatorModel<?>> enrichedModels = models.stream()
                .<AutomationRuleEvaluatorModel<?>>map(model -> enrichModelWithProjectName(model, projectNameMap))
                .toList();

        // Log enriched models for debugging
        enrichedModels.forEach(model -> log.debug("Model after enrichment - id: '{}', projectId: '{}'",
                model.id(), model.projectId()));

        return enrichedModels;
    }

    /**
     * Enriches a single AutomationRuleEvaluatorModel with project references.
     *
     * @param model the model to enrich
     * @param projectNameMap map of projectId to projectName
     * @return the enriched model
     */
    private AutomationRuleEvaluatorModel<?> enrichModelWithProjectName(
            AutomationRuleEvaluatorModel<?> model,
            Map<UUID, String> projectNameMap) {

        if (model.projectIds().isEmpty()) {
            log.debug("Skipping enrichment for rule '{}' - no projects assigned", model.id());
            return model;
        }

        // Build SortedSet of ProjectReference objects (unique, sorted alphabetically by name)
        SortedSet<ProjectReference> projects = model.projectIds().stream()
                .map(id -> {
                    String name = projectNameMap.get(id);
                    if (name == null) {
                        log.warn("Project name not found for projectId '{}' in rule '{}'", id, model.id());
                        return null;
                    }
                    return new ProjectReference(id, name);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toCollection(TreeSet::new));

        log.debug("Enriched rule '{}' with {} projects", model.id(), projects.size());

        // For backward compatibility: derive legacy fields from first project
        UUID projectId = projects.isEmpty() ? null : projects.first().projectId();
        String projectName = projects.isEmpty() ? null : projects.first().projectName();

        // Use polymorphic method to update the model with projects and legacy fields
        return model.withProjectDetails(projectId, projectName, projects);
    }
}
