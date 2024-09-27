package com.comet.opik.infrastructure.bi;

import com.comet.opik.domain.IdGenerator;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.lock.LockService;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import ru.vyarus.dropwizard.guice.module.lifecycle.GuiceyLifecycle;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

@ImplementedBy(InstallationReportServiceImpl.class)
interface InstallationReportService {
    String NOTIFICATION_EVENT_TYPE = "opik_os_startup_be";

    void reportInstallation();
}

@Singleton
@Slf4j
@RequiredArgsConstructor(onConstructor_ = @Inject)
class InstallationReportServiceImpl implements InstallationReportService {

    private final LockService lockService;
    private final IdGenerator generator;
    private final UsageReportService usageReport;
    private final OpikConfiguration config;
    private final Client client;

    public void reportInstallation() {

        String eventType = GuiceyLifecycle.ApplicationStarted.name();

        if (!config.getUsageReport().isEnabled()) {
            log.info("Usage report is disabled");
            return;
        }

        if (StringUtils.isEmpty(config.getUsageReport().getUrl())) {
            log.warn("Usage report URL is not set");
            return;
        }

        var lock = new LockService.Lock("opik-%s".formatted(eventType));

        lockService.executeWithLock(lock, tryToReportStartupEvent(eventType))
                .subscribeOn(Schedulers.boundedElastic())
                .onErrorResume(e -> {
                    log.warn("Didn't reported due to error", e);
                    return Mono.empty();
                }).block();

    }

    private Mono<Void> tryToReportStartupEvent(String eventType) {
        return Mono.fromCallable(() -> {

            String anonymousId = getAnonymousId();

            log.info("Anonymous ID: {}", anonymousId);

            if (usageReport.isEventReported(eventType)) {
                log.info("Event already reported");
                return null;
            }

            reportEvent(anonymousId, eventType);
            return null;
        });
    }

    private String getAnonymousId() {
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

    private void reportEvent(String anonymousId, String eventType) {

        usageReport.addEvent(eventType);

        var startupEvent = new OpikStartupEvent(
                anonymousId,
                NOTIFICATION_EVENT_TYPE,
                Map.of("opik_app_version", config.getMetadata().getVersion()));

        try (Response response = client.target(URI.create(config.getUsageReport().getUrl()))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(startupEvent))) {

            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL && response.hasEntity()) {

                var notificationEventResponse = response.readEntity(NotificationEventResponse.class);

                if (notificationEventResponse.success()) {
                    usageReport.markEventAsReported(eventType);
                    log.info("Event reported successfully: {}", notificationEventResponse.message());
                } else {
                    log.warn("Failed to report event: {}", notificationEventResponse.message());
                }

                return;
            }

            log.warn("Failed to report event: {}", response.getStatusInfo());
            if (response.hasEntity()) {
                log.warn("Response: {}", response.readEntity(String.class));
            }
        }
    }
}
