package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.domain.evaluators.AutomationRuleAnnotationQueueRouterDAO;
import com.comet.opik.domain.evaluators.AutomationRuleAnnotationQueueRouterModel;
import com.comet.opik.domain.evaluators.AutomationRuleDAO;
import com.comet.opik.domain.evaluators.AutomationRuleProjectsDAO;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;

/**
 * Automation configuration for annotation queues, stored as an automation rule.
 *
 * <p>A queue automation is an {@code annotation_queue_router} rule: a row in {@code automation_rules} for
 * everything true of any rule, and a row in {@code automation_rule_annotation_queue_routers} for what is
 * specific to filling a queue. It is not exposed through the automation-rules API — it is created and
 * edited through its queue's own endpoints — so the rule buys the shared columns and the family's
 * conventions rather than a new public resource.
 *
 * <p>Deliberately separate from {@link AnnotationQueueService}: the queue itself lives in ClickHouse and
 * is served reactively over R2DBC, whereas this configuration lives in MySQL behind a blocking JDBI
 * {@link TransactionTemplate}. Keeping them apart avoids threading a blocking transaction template through
 * a reactive service.
 */
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
public class AnnotationQueueAutomationService {

    // A rule that fills a review queue runs on everything that matches; sampling belongs to evaluators,
    // which pay per call. Stored rather than assumed because the column is NOT NULL on the parent.
    private static final float FULL_SAMPLING_RATE = 1.0f;

    private final @NonNull TransactionTemplate transactionTemplate;
    private final @NonNull IdGenerator idGenerator;

    public void save(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID queueId,
            @NonNull UUID projectId, @NonNull AnnotationQueue.AnnotationScope scope,
            @NonNull String queueName, @NonNull AnnotationQueueAutomation automation) {

        boolean enabled = Boolean.TRUE.equals(automation.enabled());

        transactionTemplate.inTransaction(WRITE, handle -> {
            var routerDao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);
            var ruleDao = handle.attach(AutomationRuleDAO.class);
            var projectsDao = handle.attach(AutomationRuleProjectsDAO.class);

            var existing = routerDao.findByQueueIdForUpdate(workspaceId, queueId);
            var resolved = resolve(existing, automation);

            UUID ruleId = existing.map(AutomationRuleAnnotationQueueRouterModel::id)
                    .orElseGet(idGenerator::generateId);

            var rule = AutomationRuleAnnotationQueueRouterModel.builder()
                    .id(ruleId)
                    .name(queueName)
                    .samplingRate(FULL_SAMPLING_RATE)
                    .enabled(enabled)
                    .triggerScope(EvalTriggerScope.PRODUCTION)
                    .queueId(queueId)
                    .scope(scope)
                    .conditions(resolved.conditions())
                    .maxItemsInQueue(resolved.maxItemsInQueue())
                    .build();

            if (existing.isEmpty()) {
                ruleDao.saveBaseRule(rule, workspaceId);
                projectsDao.saveRuleProjects(ruleId, Set.of(projectId), workspaceId);
            } else {
                ruleDao.updateBaseRule(ruleId, workspaceId, queueName, FULL_SAMPLING_RATE, enabled,
                        EvalTriggerScope.PRODUCTION, null);
            }

            routerDao.save(ruleId, queueId, scope.getValue(), resolved.conditions(),
                    resolved.maxItemsInQueue(), userName);
            return null;
        });

        log.info("Saved annotation queue automation, queueId '{}', enabled '{}'", queueId, enabled);
    }

    /**
     * Renames the rule to follow its queue.
     *
     * <p>The rule's name is the queue's, so a queue renamed on its own would otherwise leave the rule
     * carrying the old one. Only the name changes: everything else is read back and rewritten as-is, so
     * this cannot disturb an automation the caller did not mention.
     */
    public void renameRule(@NonNull String workspaceId, @NonNull UUID queueId, @NonNull String queueName) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            var routerDao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);

            routerDao.findByQueueIdForUpdate(workspaceId, queueId)
                    .ifPresent(rule -> handle.attach(AutomationRuleDAO.class).updateBaseRule(rule.id(),
                            workspaceId, queueName, rule.samplingRate(), rule.enabled(), rule.triggerScope(),
                            rule.filters()));
            return null;
        });
    }

    /**
     * Rejects an automation the same way {@link #save} would, without writing anything.
     *
     * <p>Exists so a caller can check the payload before it commits the queue itself. Queue storage and
     * this configuration are in different databases with no shared transaction, so a rejection discovered
     * during the save would otherwise leave a queue behind that the caller believes was never created.
     */
    public void validate(@NonNull String workspaceId, Map<UUID, AnnotationQueueAutomation> automations) {
        if (MapUtils.isEmpty(automations)) {
            return;
        }

        transactionTemplate.inTransaction(READ_ONLY, handle -> {
            // One lookup for the whole batch: a bulk import validates every queue it is about to create,
            // and a lookup per queue would make that cost scale with the batch.
            Map<UUID, AutomationRuleAnnotationQueueRouterModel> existing = handle
                    .attach(AutomationRuleAnnotationQueueRouterDAO.class)
                    .findByQueueIds(workspaceId, List.copyOf(automations.keySet()))
                    .stream()
                    .collect(Collectors.toMap(AutomationRuleAnnotationQueueRouterModel::queueId, model -> model));

            automations.forEach(
                    (queueId, automation) -> resolve(Optional.ofNullable(existing.get(queueId)), automation));
            return null;
        });
    }

    /**
     * The stored form of an automation payload: what is kept from the request and what is carried over
     * from the existing row. Shared by {@link #save} and {@link #validate} so the rules cannot drift apart.
     */
    private ResolvedAutomation resolve(Optional<AutomationRuleAnnotationQueueRouterModel> existing,
            AnnotationQueueAutomation automation) {

        rejectNonFiniteThresholds(automation.conditions());

        // A null conditions payload means "leave the stored conditions alone" — the toggle-only
        // request. There is nothing to leave alone on a first save, so require them there.
        String conditions = automation.conditions() != null
                ? JsonUtils.writeValueAsString(automation.conditions())
                : existing.map(AutomationRuleAnnotationQueueRouterModel::conditions)
                        .orElseThrow(() -> new BadRequestException(
                                "Annotation queue automation requires conditions"));

        if (Boolean.TRUE.equals(automation.enabled()) && !hasAnyCondition(conditions)) {
            throw new BadRequestException("An enabled annotation queue automation requires at least one condition");
        }

        // Same "null means leave it alone" rule as conditions, so a toggle-only request cannot drop
        // the ceiling as a side effect.
        Integer maxItemsInQueue = automation.maxItemsInQueue() != null
                ? automation.maxItemsInQueue()
                : existing.map(AutomationRuleAnnotationQueueRouterModel::maxItemsInQueue).orElse(null);

        return new ResolvedAutomation(conditions, maxItemsInQueue);
    }

    private record ResolvedAutomation(String conditions, Integer maxItemsInQueue) {
    }

    /**
     * Rejects NaN and the infinities.
     *
     * <p>The request mapper has {@code ALLOW_NON_NUMERIC_NUMBERS} enabled, so they parse, satisfy
     * {@code @NotNull}, and serialise into the stored JSON as strings. Nothing downstream would fail:
     * every comparison against NaN is false, so the automation would be saved, shown back as configured,
     * and quietly never match.
     */
    private void rejectNonFiniteThresholds(AnnotationQueueAutomation.Conditions conditions) {
        if (conditions == null || conditions.groups() == null) {
            return;
        }

        boolean nonFinite = conditions.groups().stream()
                .filter(Objects::nonNull)
                .flatMap(group -> group.conditions() == null
                        ? Stream.<AnnotationQueueAutomation.ScoreCondition>empty()
                        : group.conditions().stream())
                .filter(Objects::nonNull)
                .map(AnnotationQueueAutomation.ScoreCondition::value)
                .anyMatch(value -> value != null && !Double.isFinite(value));

        if (nonFinite) {
            throw new BadRequestException("Annotation queue automation thresholds must be finite numbers");
        }
    }

    private boolean hasAnyCondition(String conditionsJson) {
        var conditions = JsonUtils.readValue(conditionsJson, AnnotationQueueAutomation.Conditions.class);

        return conditions != null && CollectionUtils.isNotEmpty(conditions.groups())
                && conditions.groups().stream().anyMatch(group -> CollectionUtils.isNotEmpty(group.conditions()));
    }

    public Optional<AnnotationQueueAutomation> findByQueueId(@NonNull String workspaceId, @NonNull UUID queueId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleAnnotationQueueRouterDAO.class)
                        .findByQueueId(workspaceId, queueId))
                .map(this::toApi);
    }

    public Map<UUID, AnnotationQueueAutomation> findByQueueIds(@NonNull String workspaceId,
            List<UUID> queueIds) {
        if (CollectionUtils.isEmpty(queueIds)) {
            return Map.of();
        }

        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleAnnotationQueueRouterDAO.class)
                        .findByQueueIds(workspaceId, queueIds))
                .stream()
                .collect(Collectors.toMap(AutomationRuleAnnotationQueueRouterModel::queueId, this::toApi));
    }

    /**
     * Enabled routers for the given projects — the scope routing actually runs at, since a router belongs
     * to a queue and a queue belongs to a project.
     */
    public List<QueueAutomation> findEnabledByProjects(@NonNull String workspaceId,
            Set<UUID> projectIds, @NonNull AnnotationQueue.AnnotationScope scope) {
        if (CollectionUtils.isEmpty(projectIds)) {
            return List.of();
        }

        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleAnnotationQueueRouterDAO.class)
                        .findEnabledByProjects(workspaceId, List.copyOf(projectIds), scope.getValue()))
                .stream()
                .map(model -> new QueueAutomation(
                        model.queueId(),
                        model.projectId(),
                        JsonUtils.readValue(model.conditions(), AnnotationQueueAutomation.Conditions.class)))
                .toList();
    }

    /**
     * Whether anything could route for this event, as the listener's guard.
     *
     * <p>Checks the specific project when the event names one. The batch score path cannot name one — a
     * batch may span several projects — so there it falls back to the workspace. That fallback is only a
     * pre-filter against publishing for workspaces with no automation at all; the project scope itself is
     * enforced by {@link #findEnabledByProjects} once the consumer knows the entities' projects.
     */
    public boolean hasEnabledAutomation(@NonNull String workspaceId, UUID projectId,
            @NonNull AnnotationQueue.AnnotationScope scope) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);
            return projectId != null
                    ? dao.existsEnabledByProject(workspaceId, projectId, scope.getValue())
                    : dao.existsEnabledByWorkspace(workspaceId, scope.getValue());
        });
    }

    /**
     * An enabled router reduced to what routing needs: which queue, which project, and what to match.
     */
    public record QueueAutomation(UUID queueId, UUID projectId, AnnotationQueueAutomation.Conditions conditions) {
    }

    public void deleteByQueueIds(@NonNull String workspaceId, List<UUID> queueIds) {
        if (CollectionUtils.isEmpty(queueIds)) {
            return;
        }

        transactionTemplate.inTransaction(WRITE, handle -> {
            var routerDao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);
            List<UUID> ruleIds = routerDao.findRuleIdsByQueueIds(workspaceId, queueIds);

            if (ruleIds.isEmpty()) {
                return null;
            }

            // Subtype first, then the junction, then the parent: the reverse of the write order, so no
            // step can leave a row pointing at something already gone.
            routerDao.deleteByRuleIds(ruleIds);
            handle.attach(AutomationRuleProjectsDAO.class).deleteByRuleIds(Set.copyOf(ruleIds), workspaceId);
            handle.attach(AutomationRuleDAO.class).deleteBaseRules(Set.copyOf(ruleIds), workspaceId);
            return null;
        });
    }

    private AnnotationQueueAutomation toApi(AutomationRuleAnnotationQueueRouterModel model) {
        return AnnotationQueueAutomation.builder()
                .enabled(model.enabled())
                .conditions(JsonUtils.readValue(model.conditions(), AnnotationQueueAutomation.Conditions.class))
                .maxItemsInQueue(model.maxItemsInQueue())
                .build();
    }
}
