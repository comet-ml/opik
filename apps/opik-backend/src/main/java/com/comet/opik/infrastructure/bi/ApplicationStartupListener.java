package com.comet.opik.infrastructure.bi;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.Injector;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.ClientBuilder;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycleListener;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.GuiceyLifecycleEvent;
import ru.vyarus.dropwizard.guice.module.lifecycle.event.InjectorPhaseEvent;

import java.net.URI;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static com.comet.opik.infrastructure.lock.LockService.Lock;

@Slf4j
@RequiredArgsConstructor
public class ApplicationStartupListener implements GuiceyLifecycleListener {

    // This event cannot depend on authentication
    private final Client client = ClientBuilder.newClient();
    private final AtomicReference<Injector> injector = new AtomicReference<>();

    @Override
    public void onEvent(GuiceyLifecycleEvent event) {

        if (event instanceof InjectorPhaseEvent injectorEvent) {
            injector.set(injectorEvent.getInjector());
        }

        if (event.getType() == GuiceyLifecycle.ApplicationStarted) {

            String eventType = GuiceyLifecycle.ApplicationStarted.name();

            var config = (OpikConfiguration) event.getSharedState().getConfiguration().get();

            if (!config.getMetadata().getUsageReport().enabled()) {
                log.info("Usage report is disabled");
                return;
            }

            if (StringUtils.isEmpty(config.getMetadata().getUsageReport().url())) {
                log.warn("Usage report URL is not set");
                return;
            }

            var lockService = injector.get().getInstance(LockService.class);
            var generator = injector.get().getInstance(IdGenerator.class);
            var usageReport = injector.get().getInstance(UsageReportDAO.class);

            var lock = new Lock("opik-%s".formatted(eventType));

            lockService.executeWithLock(lock, tryToReportStartupEvent(usageReport, generator, eventType, config))
                    .subscribeOn(Schedulers.boundedElastic())
                    .onErrorResume(e -> {
                        log.warn("Didn't reported due to error", e);
                        return Mono.empty();
                    }).block();
        }
    }

    private Mono<Void> tryToReportStartupEvent(UsageReportDAO usageReport, IdGenerator generator, String eventType,
            OpikConfiguration config) {
        return Mono.fromCallable(() -> {

            String anonymousId = getAnonymousId(usageReport, generator);

            log.info("Anonymous ID: {}", anonymousId);

            if (usageReport.isEventReported(eventType)) {
                log.info("Event already reported");
                return null;
            }

            reportEvent(anonymousId, eventType, config, usageReport);
            return null;
        });
    }

    private String getAnonymousId(UsageReportDAO usageReport, IdGenerator generator) {
        var anonymousId = usageReport.getAnonymousId();

        if (anonymousId.isEmpty()) {
            log.info("Anonymous ID not found, generating a new one");
            var newId = generator.generateId();
            log.info("Generated new ID: {}", newId);

            // Save the new ID
            usageReport.saveAnonymousId(newId.toString());

            anonymousId = Optional.of(newId.toString());
        }

        return anonymousId.get();
    }

    private void reportEvent(String anonymousId, String eventType, OpikConfiguration config,
            UsageReportDAO usageReport) {

        usageReport.addEvent(eventType);

        var startupEvent = new OpikStartupEvent(
                anonymousId,
                eventType,
                Map.of("opik_app_version", config.getMetadata().getVersion()));

        try (Response response = client.target(URI.create(config.getMetadata().getUsageReport().url()))
                .request()
                .post(Entity.json(startupEvent))) {

            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL) {
                usageReport.markEventAsReported(eventType);
                log.info("Event reported successfully");
            } else {
                log.warn("Failed to report event: {}", response.getStatusInfo());
                if (response.hasEntity()) {
                    log.warn("Response: {}", response.readEntity(String.class));
                }
            }
        }
    }
}