package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.domain.alerts.AlertEventEvaluationService;
import com.google.common.eventbus.Subscribe;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AlertEventListener {

    private final @NonNull AlertEventEvaluationService alertEventEvaluationService;

    @Subscribe
    public void onAlertEvent(@NonNull AlertEvent alertEvent) {
        log.debug("Received alert event: {}", alertEvent);
        alertEventEvaluationService.evaluateAlertEvent(alertEvent);
    }
}
