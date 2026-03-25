package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTriggerConfig;
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

import java.util.List;
import java.util.Set;

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

        // Only inspect the trigger whose eventType matches the incoming event
        var matchingTriggerConfigs = CollectionUtils.isNotEmpty(alert.triggers())
                ? alert.triggers().stream()
                        .filter(t -> t.eventType() == alertEvent.eventType())
                        .filter(t -> CollectionUtils.isNotEmpty(t.triggerConfigs()))
                        .flatMap(t -> t.triggerConfigs().stream())
                        .toList()
                : List.<AlertTriggerConfig>of();

        var projectIds = AlertScopeUtils.collectProjectIds(alert.projectId(), matchingTriggerConfigs);

        if (projectIds.isEmpty()) {
            // No project scope defined — alert applies to all projects
            return true;
        }

        return projectIds.contains(alertEvent.projectId());
    }
}
