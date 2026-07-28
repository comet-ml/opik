package com.comet.opik.domain.alerts;

import com.comet.opik.api.Alert;
import com.comet.opik.api.AlertTrigger;
import com.comet.opik.api.AlertTriggerConfig;
import com.comet.opik.api.AlertTriggerConfigType;
import com.comet.opik.api.Guardrail;
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

import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

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
            case PROMPT_CREATED, PROMPT_COMMITTED, PROMPT_DELETED, EXPERIMENT_FINISHED ->
                isWithinProjectScope(alertEvent, alert);
            case TRACE_GUARDRAILS_TRIGGERED ->
                isWithinProjectScope(alertEvent, alert) && matchesGuardrailTypeFilter(alertEvent, alert);
            default -> false;
        };
    }

    /**
     * Restricts a guardrails alert to the guardrail types configured on its triggers. The alert fires
     * if ANY matching-event-type trigger accepts the event: a trigger without a {@code filter:guardrail_type}
     * config accepts any guardrail type; a filtered trigger accepts only its configured types (OR-ed).
     * Evaluated per trigger so an unfiltered trigger is not narrowed by a sibling filtered trigger.
     */
    private boolean matchesGuardrailTypeFilter(AlertEvent alertEvent, Alert alert) {
        List<AlertTrigger> guardrailTriggers = CollectionUtils.emptyIfNull(alert.triggers()).stream()
                .filter(trigger -> trigger.eventType() == alertEvent.eventType())
                .toList();

        Set<String> failedTypes = failedGuardrailTypes(alertEvent.payload());
        if (failedTypes == null) {
            // Unexpected payload shape — do not silently drop the event.
            return true;
        }

        return guardrailTriggers.stream().anyMatch(trigger -> {
            Set<String> configuredTypes = configuredGuardrailTypes(trigger);
            // No filter on this trigger → fire on any guardrail type.
            return configuredTypes.isEmpty()
                    || failedTypes.stream().anyMatch(configuredTypes::contains);
        });
    }

    /**
     * The set of guardrail type names present in the alert event payload, or {@code null} when the
     * payload cannot be inspected (so callers can fail open rather than drop the event).
     */
    private Set<String> failedGuardrailTypes(Object payload) {
        if (!(payload instanceof List<?> guardrails)) {
            return null;
        }
        return guardrails.stream()
                .filter(Guardrail.class::isInstance)
                .map(guardrail -> ((Guardrail) guardrail).name())
                .filter(Objects::nonNull)
                .map(guardrailType -> guardrailType.name())
                .collect(Collectors.toSet());
    }

    /** The guardrail type names configured on a single trigger's {@code filter:guardrail_type} configs. */
    private Set<String> configuredGuardrailTypes(AlertTrigger trigger) {
        return CollectionUtils.emptyIfNull(trigger.triggerConfigs()).stream()
                .filter(config -> config.type() == AlertTriggerConfigType.FILTER_GUARDRAIL_TYPE)
                .map(config -> config.configValue() != null
                        ? config.configValue().get(AlertTriggerConfig.GUARDRAIL_TYPES_CONFIG_KEY)
                        : null)
                .filter(StringUtils::isNotBlank)
                .flatMap(value -> Arrays.stream(value.split(",")))
                .map(String::trim)
                .filter(StringUtils::isNotBlank)
                .map(type -> type.toUpperCase(Locale.ROOT))
                .collect(Collectors.toSet());
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
