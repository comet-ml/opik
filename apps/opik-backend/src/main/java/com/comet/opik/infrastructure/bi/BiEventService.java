package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.ImplementedBy;
import com.google.inject.Inject;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.util.Map;

@ImplementedBy(BiEventServiceImpl.class)
interface BiEventService {
    void reportEvent(@NonNull String anonymousId, @NonNull String eventType, @NonNull String biEventType,
            @NonNull Map<String, String> eventProperties);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Slf4j
class BiEventServiceImpl implements BiEventService {

    private final @NonNull UsageReportService usageReport;
    private final @NonNull OpikConfiguration config;
    private final @NonNull Client client;

    public void reportEvent(@NonNull String anonymousId, @NonNull String eventType, @NonNull String biEventType,
            @NonNull Map<String, String> eventProperties) {
        var event = new BiEvent(anonymousId, biEventType, eventProperties);

        try (Response response = client.target(URI.create(config.getUsageReport().getUrl()))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(event))) {

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
