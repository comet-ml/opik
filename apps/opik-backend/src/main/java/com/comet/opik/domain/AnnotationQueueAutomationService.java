package com.comet.opik.domain;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueAutomation;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.BadRequestException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
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
 * Automation configuration for annotation queues.
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

    private final @NonNull TransactionTemplate transactionTemplate;

    public void save(@NonNull String workspaceId, @NonNull String userName, @NonNull UUID queueId,
            @NonNull UUID projectId, @NonNull AnnotationQueue.AnnotationScope scope,
            @NonNull AnnotationQueueAutomation automation) {

        boolean enabled = Boolean.TRUE.equals(automation.enabled());

        transactionTemplate.inTransaction(WRITE, handle -> {
            var dao = handle.attach(AnnotationQueueAutomationDAO.class);
            var existing = dao.findByQueueId(workspaceId, queueId);

            // A null conditions payload means "leave the stored conditions alone" — the toggle-only
            // request. There is nothing to leave alone on a first save, so require them there.
            String conditions = automation.conditions() != null
                    ? JsonUtils.writeValueAsString(automation.conditions())
                    : existing.map(AnnotationQueueAutomationModel::conditions)
                            .orElseThrow(() -> new BadRequestException(
                                    "Annotation queue automation requires conditions"));

            if (enabled && !hasAnyCondition(conditions)) {
                throw new BadRequestException("An enabled annotation queue automation requires at least one condition");
            }

            dao.save(workspaceId, queueId, projectId, scope.getValue(), enabled, conditions, userName);
            return null;
        });

        log.info("Saved annotation queue automation for queue '{}', enabled '{}'", queueId, enabled);
    }

    private boolean hasAnyCondition(String conditionsJson) {
        var conditions = JsonUtils.readValue(conditionsJson, AnnotationQueueAutomation.Conditions.class);

        return conditions != null && CollectionUtils.isNotEmpty(conditions.groups())
                && conditions.groups().stream().anyMatch(group -> CollectionUtils.isNotEmpty(group.conditions()));
    }

    public Optional<AnnotationQueueAutomation> findByQueueId(@NonNull String workspaceId, @NonNull UUID queueId) {
        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AnnotationQueueAutomationDAO.class).findByQueueId(workspaceId, queueId))
                .map(this::toApi);
    }

    public Map<UUID, AnnotationQueueAutomation> findByQueueIds(@NonNull String workspaceId,
            @NonNull List<UUID> queueIds) {
        if (queueIds.isEmpty()) {
            return Map.of();
        }

        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AnnotationQueueAutomationDAO.class).findByQueueIds(workspaceId, queueIds))
                .stream()
                .collect(Collectors.toMap(AnnotationQueueAutomationModel::queueId, this::toApi));
    }

    /**
     * Enabled automations for the given projects — the scope routing actually runs at, since an automation
     * belongs to a queue and a queue belongs to a project.
     */
    public List<QueueAutomation> findEnabledByProjects(@NonNull String workspaceId,
            @NonNull Set<UUID> projectIds, @NonNull AnnotationQueue.AnnotationScope scope) {
        if (projectIds.isEmpty()) {
            return List.of();
        }

        return transactionTemplate.inTransaction(READ_ONLY,
                handle -> handle.attach(AnnotationQueueAutomationDAO.class)
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
            var dao = handle.attach(AnnotationQueueAutomationDAO.class);
            return projectId != null
                    ? dao.existsEnabledByProject(workspaceId, projectId, scope.getValue())
                    : dao.existsEnabledByWorkspace(workspaceId, scope.getValue());
        });
    }

    /**
     * An enabled automation reduced to what routing needs: which queue, which project, and what to match.
     */
    public record QueueAutomation(UUID queueId, UUID projectId, AnnotationQueueAutomation.Conditions conditions) {
    }

    public void deleteByQueueIds(@NonNull String workspaceId, @NonNull List<UUID> queueIds) {
        if (queueIds.isEmpty()) {
            return;
        }

        transactionTemplate.inTransaction(WRITE, handle -> {
            handle.attach(AnnotationQueueAutomationDAO.class).deleteByQueueIds(workspaceId, queueIds);
            return null;
        });
    }

    private AnnotationQueueAutomation toApi(AnnotationQueueAutomationModel model) {
        return AnnotationQueueAutomation.builder()
                .enabled(model.enabled())
                .conditions(JsonUtils.readValue(model.conditions(), AnnotationQueueAutomation.Conditions.class))
                .build();
    }
}
