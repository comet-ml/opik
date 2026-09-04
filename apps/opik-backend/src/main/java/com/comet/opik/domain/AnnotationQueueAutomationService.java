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
