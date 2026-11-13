package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.automations.Automation;
import com.comet.opik.api.events.webhooks.AlertEvent;
import com.comet.opik.domain.automations.AutomationEvent;
import com.comet.opik.domain.automations.AutomationEventPublisher;
import com.comet.opik.domain.automations.AutomationOriginStore;
import com.comet.opik.domain.automations.AutomationService;
import com.comet.opik.infrastructure.FeatureFlags;
import com.google.common.eventbus.Subscribe;
import jakarta.annotation.Nullable;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Turns the alert events automations listen to into automation events on the stream. Like the routing
 * listener it only guards and publishes; matching, running and waiting happen behind the stream.
 *
 * <p>An experiment finishing is published even when no automation listens to it as a trigger, because a
 * run may be waiting on it: the subscriber checks that first.
 */
@EagerSingleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
public class AutomationEventListener {

    private final @NonNull FeatureFlags featureFlags;
    private final @NonNull AutomationService automationService;
    private final @NonNull AutomationEventPublisher publisher;
    private final @NonNull AutomationOriginStore originStore;

    @Subscribe
    public void onAlertEvent(@NonNull AlertEvent event) {
        if (!featureFlags.isAutomationsEnabled()) {
            return;
        }
        switch (event.eventType()) {
            case PROMPT_COMMITTED -> promptCommitted(event);
            case EXPERIMENT_FINISHED -> experimentFinished(event);
            default -> {
            }
        }
    }

    private void promptCommitted(AlertEvent event) {
        if (!(event.payload() instanceof PromptVersion version)) {
            return;
        }
        String automationEvent = Automation.Event.PROMPT_COMMITTED.getValue();
        Map<String, Object> payload = new HashMap<>();
        payload.put("prompt_id", String.valueOf(version.promptId()));
        payload.put("version_id", String.valueOf(version.id()));
        payload.put("commit", version.commit());
        payload.put("version_number", version.versionNumber());
        admitted(event, automationEvent)
                .filter(Boolean::booleanValue)
                .flatMap(__ -> publish(event, automationEvent, String.valueOf(version.id()), payload))
                .subscribe(__ -> {
                }, error -> log.error("Failed to publish prompt committed automation event, workspace '{}'",
                        event.workspaceId(), error));
    }

    private void experimentFinished(AlertEvent event) {
        if (!(event.payload() instanceof List<?> experiments)) {
            return;
        }
        String automationEvent = Automation.Event.EXPERIMENT_FINISHED.getValue();
        Flux.fromIterable(experiments)
                .filter(Experiment.class::isInstance)
                .map(Experiment.class::cast)
                .concatMap(experiment -> {
                    Map<String, Object> payload = new HashMap<>();
                    payload.put("experiment_id", String.valueOf(experiment.id()));
                    payload.put("experiment_name", experiment.name());
                    payload.put("dataset_id", String.valueOf(experiment.datasetId()));
                    payload.put("dataset_name", experiment.datasetName());
                    payload.put("pass_rate", experiment.passRate());
                    payload.put("passed_count", experiment.passedCount());
                    payload.put("total_count", experiment.totalCount());
                    payload.put("prompt_version_ids", experiment.promptVersions() == null
                            ? List.of()
                            : experiment.promptVersions().stream()
                                    .map(link -> String.valueOf(link.id())).toList());
                    // Published whenever a run might be waiting on it, not only when a trigger listens.
                    return publish(event, automationEvent, String.valueOf(experiment.id()), payload);
                })
                .subscribe(__ -> {
                }, error -> log.error("Failed to publish experiment finished automation event, workspace '{}'",
                        event.workspaceId(), error));
    }

    private Mono<Boolean> admitted(AlertEvent event, String automationEvent) {
        return Mono.fromCallable(() -> automationService.hasEnabledAutomation(event.workspaceId(),
                event.projectId(), automationEvent))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private Mono<Void> publish(AlertEvent event, String automationEvent, String itemId,
            Map<String, Object> payload) {
        return originStore.find(itemId)
                .flatMap(origin -> publisher.enqueue(AutomationEvent.builder()
                        .workspaceId(event.workspaceId())
                        .workspaceName(event.workspaceName())
                        .userName(event.userName())
                        .projectId(nullable(event.projectId()))
                        .event(automationEvent)
                        .itemId(itemId)
                        .payload(withoutNulls(payload))
                        .originRunId(origin.map(AutomationOriginStore.Origin::runId).orElse(null))
                        .depth(origin.map(AutomationOriginStore.Origin::depth).map(depth -> depth + 1).orElse(0))
                        .build()));
    }

    private static @Nullable UUID nullable(@Nullable UUID value) {
        return value;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> payload) {
        Map<String, Object> copy = new HashMap<>();
        payload.forEach((key, value) -> {
            if (value != null) {
                copy.put(key, value);
            }
        });
        return copy;
    }
}
