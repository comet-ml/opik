package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.api.evaluators.AutomationRule;
import com.comet.opik.api.evaluators.EvalTriggerScope;
import com.comet.opik.api.filter.Filter;
import com.comet.opik.domain.evaluators.AnnotationQueueAutomationMapper;
import com.comet.opik.domain.evaluators.AutomationRuleAnnotationQueueRouterDAO;
import com.comet.opik.domain.evaluators.AutomationRuleAnnotationQueueRouterModel;
import com.comet.opik.domain.evaluators.AutomationRuleDAO;
import com.comet.opik.domain.evaluators.AutomationRuleProjectsDAO;
import com.comet.opik.infrastructure.cache.CacheEvict;
import com.comet.opik.infrastructure.cache.Cacheable;
import com.comet.opik.utils.JsonUtils;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.Builder;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.collections4.MapUtils;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
    private final @NonNull AnnotationQueueAutomationFilters automationFilters;

    @CacheEvict(name = "annotation_queue_automations", key = "$workspaceId + '-*'", keyUsesPatternMatching = true)
    public void save(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID queueId,
            @NonNull UUID projectId, @NonNull AnnotationQueue.AnnotationScope scope,
            @NonNull String queueName, @NonNull AnnotationQueueAutomation automation) {

        boolean enabled = automation.enabled();

        transactionTemplate.inTransaction(WRITE, handle -> {
            var routerDao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);
            var ruleDao = handle.attach(AutomationRuleDAO.class);
            var projectsDao = handle.attach(AutomationRuleProjectsDAO.class);

            var existing = routerDao.findByQueueId(workspaceId, queueId);
            var resolved = resolve(existing, automation, scope);

            UUID ruleId = existing.map(AutomationRuleAnnotationQueueRouterModel::id)
                    .orElseGet(idGenerator::generateId);

            var rule = AutomationRuleAnnotationQueueRouterModel.builder()
                    .id(ruleId)
                    .name(queueName)
                    .samplingRate(FULL_SAMPLING_RATE)
                    .enabled(enabled)
                    .triggerScope(EvalTriggerScope.PRODUCTION)
                    .filters(resolved.filterGroups())
                    .queueId(queueId)
                    .scope(scope)
                    .maxItemsInQueue(resolved.maxItemsInQueue())
                    .build();

            if (existing.isEmpty()) {
                ruleDao.saveBaseRule(rule, workspaceId);
                projectsDao.saveRuleProjects(ruleId, Set.of(projectId), workspaceId);
            } else {
                ruleDao.updateBaseRule(ruleId, workspaceId, queueName, FULL_SAMPLING_RATE, enabled,
                        EvalTriggerScope.PRODUCTION, resolved.filterGroups());
            }

            routerDao.save(ruleId, queueId, scope.getValue(), resolved.maxItemsInQueue(), userName);
            return null;
        });

        log.info("Saved annotation queue automation, queueId '{}', enabled '{}'", queueId, enabled);
    }

    /**
     * Renames the rule to follow its queue.
     *
     * <p>The rule's name is the queue's, so a queue renamed on its own would otherwise leave the rule
     * carrying the old one. The update names only that column, so a rename racing a save cannot write
     * back a stale copy of the fields it never meant to touch.
     */
    @CacheEvict(name = "annotation_queue_automations", key = "$workspaceId + '-*'", keyUsesPatternMatching = true)
    public void renameRule(@NonNull String workspaceId, @NonNull UUID queueId, @NonNull String queueName) {
        transactionTemplate.inTransaction(WRITE, handle -> {
            var routerDao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);

            routerDao.findByQueueId(workspaceId, queueId)
                    .ifPresent(rule -> handle.attach(AutomationRuleDAO.class)
                            .updateBaseRuleName(rule.id(), workspaceId, queueName));
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
    public void validate(@NonNull String workspaceId, Map<UUID, AnnotationQueueAutomation> automations,
            @NonNull Map<UUID, AnnotationQueue.AnnotationScope> scopes) {
        if (MapUtils.isEmpty(automations)) {
            return;
        }

        transactionTemplate.inTransaction(READ_ONLY, handle -> {
            // One lookup for the whole batch: a bulk import validates every queue it is about to create,
            // and a lookup per queue would make that cost scale with the batch.
            Map<UUID, AutomationRuleAnnotationQueueRouterModel> existing = handle
                    .attach(AutomationRuleAnnotationQueueRouterDAO.class)
                    .findByQueueIds(workspaceId, List.copyOf(automations.keySet()))
                    .collect(Collectors.toMap(AutomationRuleAnnotationQueueRouterModel::queueId,
                            model -> model));

            automations.forEach(
                    (queueId, automation) -> resolve(Optional.ofNullable(existing.get(queueId)), automation,
                            scopes.get(queueId)));
            return null;
        });
    }

    private record Resolved(String filterGroups, Integer maxItemsInQueue) {
    }

    /**
     * The stored form of an automation payload — the filter groups JSON and the item ceiling — being what is
     * kept from the request and what is carried over from the existing row. Shared by {@link #save} and
     * {@link #validate} so the rules cannot drift apart.
     */
    private Resolved resolve(Optional<AutomationRuleAnnotationQueueRouterModel> existing,
            AnnotationQueueAutomation automation, @NonNull AnnotationQueue.AnnotationScope scope) {

        // Null filter groups mean "leave the stored ones alone" — the toggle-only request. There is nothing to
        // leave alone on a first save, so require them there.
        String filterGroups;
        if (automation.filterGroups() != null) {
            automationFilters.toFilterGroups(scope, automation.filterGroups());
            filterGroups = automation.filterGroups().isEmpty()
                    ? null
                    : JsonUtils.writeValueAsString(automation.filterGroups());
        } else {
            filterGroups = existing.orElseThrow(() -> new BadRequestException(
                    "Annotation queue automation requires filter groups"))
                    .filters();
        }

        if (automation.enabled() && automationFilters.toFilterGroups(scope, filterGroups).isEmpty()) {
            throw new BadRequestException("An enabled annotation queue automation requires at least one filter");
        }

        // Null keeps the stored ceiling, as null does for every field on this resource, so removing one has
        // to be said outright rather than implied by leaving the field out.
        Integer maxItemsInQueue = Boolean.TRUE.equals(automation.clearMaxItemsInQueue())
                ? null
                : automation.maxItemsInQueue() != null
                        ? automation.maxItemsInQueue()
                        : existing.map(AutomationRuleAnnotationQueueRouterModel::maxItemsInQueue).orElse(null);

        return new Resolved(filterGroups, maxItemsInQueue);
    }

    public Optional<AnnotationQueueAutomation> findByQueueId(@NonNull String workspaceId, @NonNull UUID queueId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleAnnotationQueueRouterDAO.class)
                        .findByQueueId(workspaceId, queueId))
                .map(AnnotationQueueAutomationMapper.INSTANCE::map);
    }

    public Map<UUID, AnnotationQueueAutomation> findByQueueIds(@NonNull String workspaceId,
            List<UUID> queueIds) {
        if (CollectionUtils.isEmpty(queueIds)) {
            return Map.of();
        }

        // Mapped inside the transaction: the rows are converted as they arrive rather than held as a
        // list first, and the stream is only valid while the handle is open.
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleAnnotationQueueRouterDAO.class)
                        .findByQueueIds(workspaceId, queueIds)
                        .collect(Collectors.toMap(AutomationRuleAnnotationQueueRouterModel::queueId,
                                AnnotationQueueAutomationMapper.INSTANCE::map)));
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

        List<AutomationRuleAnnotationQueueRouterModel> models = transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AutomationRuleAnnotationQueueRouterDAO.class)
                        .findEnabledByProjects(workspaceId, List.copyOf(projectIds), scope.getValue())
                        .toList());

        return models.stream()
                .flatMap(model -> toQueueAutomation(model, scope).stream())
                .toList();
    }

    /**
     * A stored filter can stop being valid after it was saved, for instance when a field is retired, or be
     * unreadable after a manual edit. That automation is skipped rather than failing routing for every other queue
     * in the batch.
     */
    private Optional<QueueAutomation> toQueueAutomation(AutomationRuleAnnotationQueueRouterModel model,
            AnnotationQueue.AnnotationScope scope) {
        try {
            return Optional.of(QueueAutomation.builder()
                    .queueId(model.queueId())
                    .projectId(model.projectId())
                    .filterGroups(automationFilters.toFilterGroups(scope, model.filters()))
                    .build());
        } catch (RuntimeException exception) {
            log.warn("Skipping annotation queue automation with invalid filters, queueId '{}'",
                    model.queueId(), exception);
            return Optional.empty();
        }
    }

    /**
     * Whether anything could route for this event, as the listener's guard.
     *
     * <p>A null {@code projectId} is the batch score path, which cannot name a project because one batch
     * may span several. The question then widens to whether the workspace has any router at all, which is
     * a pre-filter only: the project scope is enforced by {@link #findEnabledByProjects} once the consumer
     * learns each entity's project from its scores. Both forms are an index seek on an equality prefix of
     * {@code automation_rules_workspace_action_enabled_idx}, not a scan.
     *
     * <p>One method rather than two overloads so that callers, which receive the project id already
     * nullable from the event, do not each have to branch on it.
     *
     * <p>Cached, following {@code AutomationRuleEvaluatorService#findAll}: this is a database round trip on
     * the busiest event in the system, and the answer is no for most workspaces most of the time. The
     * writes below evict the whole workspace by prefix, because one rule change can flip the answer for
     * the workspace-wide key and every project key at once.
     *
     * <p>Eviction is not the only thing keeping this fresh, and must not be: a rule can also be disabled by
     * a write this class never sees, and a stale {@code false} is silent - the events it turns away are
     * dropped at the guard, and there is no backfill to route them later. The TTL is the floor under that,
     * which is why it is short and configurable rather than left to the cache manager's default.
     */
    // workspaceId first because it is the coarsest entity: every eviction is per workspace, so a prefix
    // beats a glob with a leading wildcard. CacheInterceptor substitutes "" for a null argument before
    // evaluating the expression, so the batch path's absent project becomes the literal 'all' rather than
    // an empty segment, and the two forms cannot collide.
    @Cacheable(name = "annotation_queue_automations", key = "$workspaceId + '-' + ($projectId == '' ? 'all' : $projectId) + '-' + $scope", returnType = Boolean.class)
    public boolean hasEnabledAutomation(@NonNull String workspaceId, @Nullable UUID projectId,
            @NonNull AnnotationQueue.AnnotationScope scope) {
        return transactionTemplate.inTransaction(READ_ONLY, handle -> {
            var dao = handle.attach(AutomationRuleAnnotationQueueRouterDAO.class);
            return projectId == null
                    ? dao.existsEnabledByWorkspace(workspaceId, scope.getValue())
                    : dao.existsEnabledByProject(workspaceId, projectId, scope.getValue());
        });
    }

    /**
     * An enabled router reduced to what routing needs: which queue, which project, and what to match.
     */
    @Builder(toBuilder = true)
    public record QueueAutomation(@NonNull UUID queueId, @NonNull UUID projectId,
            @NonNull List<List<Filter>> filterGroups) {
    }

    @CacheEvict(name = "annotation_queue_automations", key = "$workspaceId + '-*'", keyUsesPatternMatching = true)
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
            handle.attach(AutomationRuleDAO.class).deleteBaseRules(Set.copyOf(ruleIds), workspaceId,
                    AutomationRule.AutomationRuleAction.ANNOTATION_QUEUE_ROUTER.getAction());
            return null;
        });
    }

}
