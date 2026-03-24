package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.domain.AlertService;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.utils.JsonUtils;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.AlertTriggerConfig.PROJECT_IDS_CONFIG_KEY;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertEventEvaluationService {

    private final @NonNull AlertService alertService;
    private final @NonNull AlertBucketService alertBucketService;
    private final @NonNull IdGenerator idGenerator;

    public void evaluateAlertEvent(@NonNull AlertEvent alertEvent) {
        log.debug("Evaluating alert event {}", alertEvent);
        alertService.findAllByWorkspaceAndEventTypes(alertEvent.workspaceId(), Set.of(alertEvent.eventType()))
                .forEach(alert -> {
                    if (isValidForAlert(alertEvent, alert)) {
                        log.debug("Alert {} matches event {}", alert.id(), alertEvent);

                        String eventId = idGenerator.generateId().toString();
                        alertBucketService
                                .addEventToBucket(alert.id(), alertEvent.workspaceId(), alertEvent.workspaceName(),
                                        alertEvent.eventType(), eventId,
                                        JsonUtils.writeValueAsString(alertEvent.payload()),
                                        alertEvent.userName())
                                .block();
                    }
                });

    }

    private boolean isValidForAlert(AlertEvent alertEvent, Alert alert) {
        return switch (alertEvent.eventType()) {
            case PROMPT_CREATED, PROMPT_COMMITTED, PROMPT_DELETED, EXPERIMENT_FINISHED,
                    TRACE_GUARDRAILS_TRIGGERED ->
                isWithinProjectScope(alertEvent, alert);
            default -> false;
        };
    }

    private boolean isWithinProjectScope(AlertEvent alertEvent, Alert alert) {
        // Events without a project ID are workspace-wide (e.g. prompt events) — bypass project scope
        if (alertEvent.projectId() == null) {
            return true;
        }

        // Collect project IDs from both the alert's project_id column and the scope:project trigger config
        Set<UUID> projectIdSet = new HashSet<>();
        if (alert.projectId() != null) {
            projectIdSet.add(alert.projectId());
        }

        if (CollectionUtils.isNotEmpty(alert.triggers())) {
            alert.triggers().stream()
                    .filter(t -> CollectionUtils.isNotEmpty(t.triggerConfigs()))
                    .flatMap(t -> t.triggerConfigs().stream())
                    .filter(c -> c.type() == AlertTriggerConfigType.SCOPE_PROJECT)
                    .findFirst()
                    .map(AlertTriggerConfig::configValue)
                    .map(v -> v.get(PROJECT_IDS_CONFIG_KEY))
                    .filter(StringUtils::isNotBlank)
                    .ifPresent(projectIdsString -> projectIdSet
                            .addAll(JsonUtils.readCollectionValue(projectIdsString, List.class, UUID.class)));
        }

        if (projectIdSet.isEmpty()) {
            // No project scope defined — alert applies to all projects
            return true;
        }

        return projectIdSet.contains(alertEvent.projectId());
    }
}
