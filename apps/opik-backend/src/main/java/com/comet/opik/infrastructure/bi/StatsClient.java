package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;

@ImplementedBy(StatsClientImpl.class)
public interface StatsClient {
    boolean sendEvent(BiEvent event);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
@Slf4j
class StatsClientImpl implements StatsClient {

    private final @NonNull OpikConfiguration config;
    private final @NonNull Client client;

    @Override
    public boolean sendEvent(@NonNull BiEvent event) {
        try (Response response = client.target(URI.create(config.getUsageReport().getUrl()))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(event))) {

            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL && response.hasEntity()) {
                var body = response.readEntity(NotificationEventResponse.class);

                if (body.success()) {
                    log.info("Event '{}' sent successfully: {}", event.eventType(), body.message());
                    return true;
                }

                log.warn("Failed to send event '{}': {}", event.eventType(), body.message());
                return false;
            }

            log.warn("Failed to send event '{}': {}", event.eventType(), response.getStatusInfo());
            if (response.hasEntity()) {
                log.warn("Response body: {}", response.readEntity(String.class));
            }
            return false;
        } catch (Exception e) {
            log.warn("Failed to send event '{}'", event.eventType(), e);
            return false;
        }
    }
}
