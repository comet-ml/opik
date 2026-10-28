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
import com.comet.opik.infrastructure.inference.Question;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
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

    GuardrailPolicyPage find(int page, int size, String name, List<SortingField> sortingFields);

    void update(UUID id, GuardrailPolicy policy);

    void delete(Set<UUID> ids);

    /** The named policies plus every global policy of the workspace. */
    List<GuardrailPolicy> retrieve(Set<String> names);
}

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
class GuardrailPolicyServiceImpl implements GuardrailPolicyService {

    /**
     * Each list becomes one choice question over its topics plus "none of these", within the service's option
     * limit.
     */
    static final int MAX_TOPICS = Question.ChoiceQuestion.MAX_OPTIONS - 1;
    static final int MAX_TOPIC_LENGTH = 256;
    static final String ALLOWED_TOPICS = "allowed_topics";
    static final String RESTRICTED_TOPICS = "restricted_topics";
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
        var newPolicy = withValidRules(policy).toBuilder().id(idGenerator.generateId()).build();
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
    public GuardrailPolicyPage find(int page, int size, String name, @NonNull List<SortingField> sortingFields) {
        String workspaceId = requestContext.get().getWorkspaceId();
        String nameTerm = StringUtils.trimToNull(name);
        String sortingFieldsSql = sortingQueryBuilder.toOrderBySql(sortingFields);

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            long total = dao.count(workspaceId, nameTerm);
            var pageIds = dao.findPageIds(workspaceId, nameTerm, sortingFieldsSql, size, (page - 1) * size);
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
    public void delete(@NonNull Set<UUID> ids) {
        String workspaceId = requestContext.get().getWorkspaceId();

        template.inTransaction(WRITE, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            // Rules carry no workspace, so only the rules of this workspace's policies are deleted.
            var existing = dao.findIds(ids, workspaceId);
            if (!existing.isEmpty()) {
                dao.deleteRules(existing);
                dao.delete(existing, workspaceId);
            }
            log.info("Deleted '{}' guardrail policies in workspace '{}'", existing.size(), workspaceId);
            return null;
        });
    }

    @Override
    public List<GuardrailPolicy> retrieve(@NonNull Set<String> names) {
        String workspaceId = requestContext.get().getWorkspaceId();

        return template.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(GuardrailPolicyDAO.class);
            var named = names.isEmpty() ? List.<GuardrailPolicy>of() : dao.findByNames(names, workspaceId);
            // Names match ignoring case and trailing spaces, as the schema collation does.
            var missing = names.stream()
                    .filter(name -> named.stream().noneMatch(policy -> StringUtils.stripEnd(policy.name(), " ")
                            .equalsIgnoreCase(StringUtils.stripEnd(name, " "))))
                    .sorted()
                    .toList();
            if (!missing.isEmpty()) {
                throw new NotFoundException("Guardrail policies not found: " + String.join(", ", missing));
            }
            var policies = new LinkedHashMap<UUID, GuardrailPolicy>();
            named.forEach(policy -> policies.put(policy.id(), policy));
            dao.findGlobal(workspaceId).forEach(policy -> policies.putIfAbsent(policy.id(), policy));
            return List.copyOf(policies.values());
        });
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
        var normalized = JsonUtils.createObjectNode();
        if (rule.type() == GuardrailType.PII) {
            var entities = normalizedUniqueStrings(index, config, "entities");
            entities.stream()
                    .filter(entity -> !GuardrailQuestions.PII_DESCRIPTIONS.containsKey(entity))
                    .findFirst()
                    .ifPresent(entity -> {
                        throw new BadRequestException("rules[%d].config.entities contains the unsupported class '%s'"
                                .formatted(index, entity));
                    });
            entities.forEach(normalized.putArray("entities")::add);
        } else if (rule.type() == GuardrailType.TOPIC) {
            var allowed = topics(index, config, ALLOWED_TOPICS);
            var restricted = topics(index, config, RESTRICTED_TOPICS);
            if (allowed.isEmpty() && restricted.isEmpty()) {
                throw new BadRequestException("rules[%d].config needs %s or %s"
                        .formatted(index, ALLOWED_TOPICS, RESTRICTED_TOPICS));
            }
            // Topics are compared ignoring case, as the model reads them.
            var restrictedLowerCase = restricted.stream()
                    .map(topic -> topic.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
            allowed.stream()
                    .filter(topic -> restrictedLowerCase.contains(topic.toLowerCase(Locale.ROOT)))
                    .findFirst()
                    .ifPresent(topic -> {
                        throw new BadRequestException("rules[%d].config has '%s' in both %s and %s"
                                .formatted(index, topic, ALLOWED_TOPICS, RESTRICTED_TOPICS));
                    });
            if (!allowed.isEmpty()) {
                allowed.forEach(normalized.putArray(ALLOWED_TOPICS)::add);
            }
            if (!restricted.isEmpty()) {
                restricted.forEach(normalized.putArray(RESTRICTED_TOPICS)::add);
            }
        }
        return normalized;
    }

    /** An absent or empty list means the rule does not use it. */
    private static Set<String> topics(int index, JsonNode config, String field) {
        var node = config.path(field);
        if (node.isMissingNode() || node.isNull() || (node.isArray() && node.isEmpty())) {
            return Set.of();
        }
        var topics = normalizedUniqueStrings(index, config, field);
        if (topics.size() > MAX_TOPICS) {
            throw new BadRequestException("rules[%d].config.%s has more than %d topics"
                    .formatted(index, field, MAX_TOPICS));
        }
        if (topics.stream().anyMatch(topic -> topic.length() > MAX_TOPIC_LENGTH)) {
            throw new BadRequestException("rules[%d].config.%s has a topic longer than %d characters"
                    .formatted(index, field, MAX_TOPIC_LENGTH));
        }
        // The check adds this option itself; a topic with the same name would make its answer ambiguous.
        if (topics.stream().anyMatch(GuardrailQuestions.NONE_OF_THESE::equalsIgnoreCase)) {
            throw new BadRequestException("rules[%d].config.%s cannot contain '%s'"
                    .formatted(index, field, GuardrailQuestions.NONE_OF_THESE));
        }
        // Case variants would be separate options of one choice question and split its probability.
        var topicsLowerCase = new HashSet<String>();
        topics.stream()
                .filter(topic -> !topicsLowerCase.add(topic.toLowerCase(Locale.ROOT)))
                .findFirst()
                .ifPresent(topic -> {
                    throw new BadRequestException("rules[%d].config.%s contains '%s' more than once, ignoring case"
                            .formatted(index, field, topic));
                });
        return topics;
    }

    private static Set<String> normalizedUniqueStrings(int index, JsonNode config, String field) {
        var node = config.path(field);
        if (!node.isArray() || node.isEmpty()) {
            throw new BadRequestException("rules[%d].config.%s must be a non-empty list of strings"
                    .formatted(index, field));
        }
        var values = new LinkedHashSet<String>();
        for (var item : node) {
            if (!item.isTextual() || StringUtils.isBlank(item.asText())) {
                throw new BadRequestException("rules[%d].config.%s must contain only non-blank strings"
                        .formatted(index, field));
            }
            if (!values.add(item.asText().trim())) {
                throw new BadRequestException("rules[%d].config.%s contains a value more than once"
                        .formatted(index, field));
            }
        }
        return values;
    }
}
