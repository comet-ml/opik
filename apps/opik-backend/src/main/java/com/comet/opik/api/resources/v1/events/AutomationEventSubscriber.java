package com.comet.opik.api.resources.v1.events;

import com.comet.opik.domain.automations.AutomationEvent;
import com.comet.opik.domain.automations.AutomationExecutor;
import com.comet.opik.infrastructure.AutomationEventsConfig;
import com.comet.opik.infrastructure.FeatureFlags;
import jakarta.inject.Inject;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonReactiveClient;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.module.installer.feature.eager.EagerSingleton;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

/** Runs the automations an event fires, and resumes the runs that were waiting on it. */
@Slf4j
@EagerSingleton
public class AutomationEventSubscriber extends BaseRedisSubscriber<AutomationEvent> {

    private final FeatureFlags featureFlags;
    private final AutomationExecutor executor;

    @Inject
    public AutomationEventSubscriber(@NonNull @Config("automationEvents") AutomationEventsConfig config,
            @NonNull RedissonReactiveClient redisson, @NonNull FeatureFlags featureFlags,
            @NonNull AutomationExecutor executor) {
        super(config, redisson, AutomationEventsConfig.PAYLOAD_FIELD, "opik", "automation_events");
        this.featureFlags = featureFlags;
        this.executor = executor;
    }

    @Override
    public void start() {
        if (!featureFlags.isAutomationsEnabled()) {
            log.info("Automations are disabled, skipping the automation events subscriber");
            return;
        }
        super.start();
    }

    @Override
    protected Mono<Void> processEvent(@NonNull AutomationEvent message) {
        return executor.handle(message);
    }
}
