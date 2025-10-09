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
            case PROMPT_CREATED -> true;
            // TODO: implement evaluation for all event types
            default -> false;
        };
    }
}
