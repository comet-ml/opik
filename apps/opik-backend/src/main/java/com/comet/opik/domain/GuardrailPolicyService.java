package com.comet.opik.domain;

import com.comet.opik.api.GuardrailExecutionScope;
import com.comet.opik.api.GuardrailPolicy;
import com.comet.opik.api.GuardrailPolicy.GuardrailPolicyPage;
import com.comet.opik.api.GuardrailPolicyRule;
import com.comet.opik.api.GuardrailType;
import com.comet.opik.api.error.EntityAlreadyExistsException;
import com.comet.opik.api.error.ErrorMessage;
import com.comet.opik.api.sorting.SortingFactoryGuardrailPolicies;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.domain.sorting.SortingQueryBuilder;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.NotFoundException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.statement.UnableToExecuteStatementException;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.sql.SQLIntegrityConstraintViolationException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

@ImplementedBy(GuardrailPolicyServiceImpl.class)
public interface GuardrailPolicyService {

    UUID create(GuardrailPolicy policy);

    GuardrailPolicy findById(UUID id);

    GuardrailPolicyPage find(int page, int size, String name, Set<String> names,
            GuardrailExecutionScope executionScope, List<SortingField> sortingFields);

    void update(UUID id, GuardrailPolicy policy);

    void delete(UUID id);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailPolicyServiceImpl implements GuardrailPolicyService {

    private static final Set<GuardrailType> SUPPORTED_TYPES = EnumSet.of(GuardrailType.PII, GuardrailType.TOPIC,
            GuardrailType.PROMPT_INJECTION);
    private static final String POLICY_NOT_FOUND = "Guardrail policy not found";
    private static final String POLICY_ALREADY_EXISTS = "Guardrail policy already exists";

    private final @NonNull TransactionTemplate template;
    private final @NonNull Provider<RequestContext> requestContext;
    private final @NonNull IdGenerator idGenerator;
    private final @NonNull SortingFactoryGuardrailPolicies sortingFactory;
    private final @NonNull SortingQueryBuilder sortingQueryBuilder;

    @Override
    public UUID create(@NonNull GuardrailPolicy policy) {
        var newPolicy = withValidRules(policy).toBuilder()
                .id(policy.id() == null ? idGenerator.generateId() : policy.id())
                .build();
        IdGenerator.validateVersion(newPolicy.id(), "guardrail policy");
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            try {
                dao.save(newPolicy, workspaceId, userName);
            } catch (UnableToExecuteStatementException exception) {
                throw conflictOr(exception);
            }
            saveRules(dao, newPolicy, userName);
            return null;
        });
        log.info("Created guardrail policy with id '{}', name '{}' in workspace '{}'", newPolicy.id(),
                newPolicy.name(), workspaceId);
        return newPolicy.id();
    }

    @Override
    public GuardrailPolicy findById(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> handle.attach(GuardrailPolicyDAO.class)
                .findById(id, workspaceId)
                .orElseThrow(() -> new NotFoundException(POLICY_NOT_FOUND)));
    }

    @Override
    public GuardrailPolicyPage find(int page, int size, String name, @NonNull Set<String> names,
            GuardrailExecutionScope executionScope, @NonNull List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String nameTerm = Optional.ofNullable(StringUtils.trimToNull(name))
                .map(GuardrailPolicyDAO::escapeLike)
                .orElse(null);
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            long total = dao.count(workspaceId, nameTerm, names, executionScope);
            var pageIds = dao.findPageIds(workspaceId, nameTerm, names, executionScope, sortingFieldsSql, size,
                    (page - 1) * size);
            Map<UUID, GuardrailPolicy> byId = pageIds.isEmpty()
                    ? Map.of()
                    : dao.findByIds(pageIds, workspaceId).stream()
                            .collect(Collectors.toMap(GuardrailPolicy::id, Function.identity()));
            var policies = pageIds.stream().map(byId::get).toList();
            return GuardrailPolicyPage.builder()
                    .content(policies)
                    .page(page)
                    .size(policies.size())
                    .total(total)
                    .sortableBy(sortingFactory.getSortableFields())
                    .build();
        });
    }

    @Override
    public void update(@NonNull UUID id, @NonNull GuardrailPolicy policy) {
        var updatedPolicy = withValidRules(policy).toBuilder().id(id).build();
        String workspaceId = requestContext.get().getWorkspaceId();
        String userName = requestContext.get().getUserName();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            int updated;
            try {
                updated = dao.update(updatedPolicy, workspaceId, userName);
            } catch (UnableToExecuteStatementException exception) {
                throw conflictOr(exception);
            }
            if (updated == 0) {
                throw new NotFoundException(POLICY_NOT_FOUND);
            }
            dao.deleteRules(List.of(id));
            saveRules(dao, updatedPolicy, userName);
            return null;
        });
        log.info("Updated guardrail policy with id '{}' in workspace '{}'", id, workspaceId);
    }

    @Override
    public void delete(@NonNull UUID id) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            // Rules carry no workspace, so they go only once this workspace's policy is gone.
            if (dao.delete(id, workspaceId) > 0) {
                dao.deleteRules(List.of(id));
            }
            return null;
        });
        log.info("Deleted guardrail policy with id '{}' in workspace '{}'", id, workspaceId);
    }

    private void saveRules(GuardrailPolicyDAO dao, GuardrailPolicy policy, String userName) {
        var rules = policy.rules();
        dao.saveRules(rules.stream().map(rule -> idGenerator.generateId()).toList(), policy.id(),
                rules.stream().map(GuardrailPolicyRule::type).toList(),
                rules.stream().map(GuardrailPolicyRule::config).toList(), userName);
    }

    private static RuntimeException conflictOr(UnableToExecuteStatementException exception) {
        if (exception.getCause() instanceof SQLIntegrityConstraintViolationException) {
            return new EntityAlreadyExistsException(new ErrorMessage(List.of(POLICY_ALREADY_EXISTS)));
        }
        return exception;
    }

    /** Checks every rule and keeps only the settings its type uses, so stored configs hold nothing else. */
    private static GuardrailPolicy withValidRules(GuardrailPolicy policy) {
        var types = EnumSet.noneOf(GuardrailType.class);
        var rules = new ArrayList<GuardrailPolicyRule>(policy.rules().size());
        for (int index = 0; index < policy.rules().size(); index++) {
            var rule = policy.rules().get(index);
            if (!types.add(rule.type())) {
                throw new BadRequestException(
                        "rules[%d].type '%s' is repeated: a policy holds at most one rule per type"
                                .formatted(index, rule.type()));
            }
            rules.add(rule.toBuilder().config(config(index, rule)).build());
        }
        return policy.toBuilder()
                .executionScope(Optional.ofNullable(policy.executionScope()).orElse(GuardrailExecutionScope.ON_REQUEST))
                .rules(rules)
                .build();
    }

    private static JsonNode config(int index, GuardrailPolicyRule rule) {
        if (!SUPPORTED_TYPES.contains(rule.type())) {
            throw new BadRequestException("rules[%d].type '%s' is not supported".formatted(index, rule.type()));
        }
        var config = rule.config();
        if (!config.isObject()) {
            throw new BadRequestException("rules[%d].config must be an object".formatted(index));
        }
        if (config.has("threshold")) {
            throw new BadRequestException(
                    "rules[%d].config.threshold is not accepted: thresholds are set by the server".formatted(index));
        }
        // The same checks as the validations API, so a policy that can be saved is one the checks accept.
        var path = "rules[%d].config".formatted(index);
        var normalized = JsonUtils.createObjectNode();
        if (rule.type() == GuardrailType.PII) {
            GuardrailQuestions.piiEntities(path, config).forEach(normalized.putArray("entities")::add);
        } else if (rule.type() == GuardrailType.TOPIC) {
            var topics = GuardrailQuestions.topicLists(path, config);
            if (!topics.allowed().isEmpty()) {
                topics.allowed().forEach(normalized.putArray(GuardrailQuestions.ALLOWED_TOPICS)::add);
            }
            if (!topics.restricted().isEmpty()) {
                topics.restricted().forEach(normalized.putArray(GuardrailQuestions.RESTRICTED_TOPICS)::add);
            }
        }
        return normalized;
    }
}
