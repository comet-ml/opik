package com.comet.opik.api.resources.v1.events.webhooks;

import com.comet.opik.api.AlertTrigger;
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

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertEventEvaluationService {

    public static final String PROJECT_SCOPE_CONFIG_KEY = "project_ids";

    private final @NonNull AlertService alertService;
    private final @NonNull AlertBucketService alertBucketService;
    private final @NonNull IdGenerator idGenerator;

    public void evaluateAlertEvent(@NonNull AlertEvent alertEvent) {
        log.debug("Evaluating alert event {}", alertEvent);
        alertService.findAllByWorkspaceAndEventType(alertEvent.workspaceId(), alertEvent.eventType())
                .forEach(alert -> {
                    if (isValidForAlert(alertEvent, alert.triggers())) {
                        log.debug("Alert {} matches event {}", alert.id(), alertEvent);

                        String eventId = idGenerator.generateId().toString();
                        alertBucketService
                                .addEventToBucket(alert.id(), alertEvent.workspaceId(), alertEvent.eventType(),
                                        eventId, JsonUtils.writeValueAsString(alertEvent.payload()))
                                .block();
                    }
                });

    }

    private boolean isValidForAlert(AlertEvent alertEvent, List<AlertTrigger> triggers) {
        return switch (alertEvent.eventType()) {
            case PROMPT_CREATED, PROMPT_COMMITTED, PROMPT_DELETED -> true;
            case TRACE_FEEDBACK_SCORE, TRACE_THREAD_FEEDBACK_SCORE -> isWithinProjectScope(alertEvent, triggers);
            default -> false;
        };
    }

    private boolean isWithinProjectScope(AlertEvent alertEvent, List<AlertTrigger> triggers) {
        // Find relevant trigger, at this point Alert must have at least one trigger matching event type
        // According to current design, there should be max one trigger per event type
        var trigger = triggers.stream()
                .filter(t -> t.eventType() == alertEvent.eventType())
                .findFirst().orElse(null);

        if (trigger == null) {
            log.warn("Could not find trigger for event type {} in triggers {}", alertEvent.eventType(), triggers);
            return false;
        }

        if (CollectionUtils.isEmpty(trigger.triggerConfigs())) {
            // No project scope defined, all projects are in scope
            return true;
        }

        // For now, we assume there is max one config per trigger, related to project scope
        var config = trigger.triggerConfigs().getFirst();
        var projectIdsString = Optional.ofNullable(config.configValue())
                .map(v -> v.get(PROJECT_SCOPE_CONFIG_KEY))
                .orElse(null);

        if (projectIdsString == null) {
            // No project scope defined, all projects are in scope
            return true;
        }

        return parseProjectIds(projectIdsString).contains(alertEvent.projectId());
    }

    // Project IDs are passed as a comma-separated string
    // Ex.: "01993e30-0fd9-79bf-8d94-a813302e2185,01993e25-9ec2-7fb1-bd7c-b394920c27ff"
    private Set<UUID> parseProjectIds(String projectIdsString) {
        if (StringUtils.isEmpty(projectIdsString)) {
            return Set.of();
        }

        return JsonUtils.readCollectionValue(projectIdsString, Set.class, UUID.class);
    }
}
