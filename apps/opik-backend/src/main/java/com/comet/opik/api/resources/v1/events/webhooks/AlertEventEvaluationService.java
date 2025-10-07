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

import java.util.List;

@Slf4j
@Singleton
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertEventEvaluationService {

    private final @NonNull AlertService alertService;
    private final @NonNull AlertBucketService alertBucketService;
    private final @NonNull IdGenerator idGenerator;

    public void evaluateAlertEvent(@NonNull AlertEvent alertEvent) {
        log.debug("Evaluating alert event {}", alertEvent);
        alertService.findAllByWorkspace(alertEvent.getWorkspaceId())
                .stream()
                .filter(alert -> {
                    var triggers = alert.triggers();
                    if (CollectionUtils.isNotEmpty(triggers)) {
                        return triggers.stream().anyMatch(trigger -> trigger.eventType() == alertEvent.getEventType());
                    }
                    return false;
                })
                .forEach(alert -> {
                    if (isValidForAlert(alertEvent, alert.triggers())) {
                        log.debug("Alert {} matches event {}", alert.id(), alertEvent);

                        String eventId = idGenerator.generateId().toString();
                        alertBucketService
                                .addEventToBucket(alert.id(), alertEvent.getWorkspaceId(), alertEvent.getEventType(),
                                        eventId, JsonUtils.writeValueAsString(alertEvent.getPayload()))
                                .block();
                    }
                });

    }

    private boolean isValidForAlert(AlertEvent alertEvent, List<AlertTrigger> triggers) {
        return switch (alertEvent.getEventType()) {
            case PROMPT_CREATED -> true;
            // TODO: implement evaluation for all event types
            default -> false;
        };
    }
}
