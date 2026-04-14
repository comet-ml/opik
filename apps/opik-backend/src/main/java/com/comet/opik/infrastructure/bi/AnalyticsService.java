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
import org.apache.commons.lang3.StringUtils;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

@ImplementedBy(AnalyticsServiceImpl.class)
public interface AnalyticsService {
    String EVENT_PREFIX = "opik_";

    void trackEvent(@NonNull String eventType, @NonNull Map<String, String> properties);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
@Slf4j
class AnalyticsServiceImpl implements AnalyticsService {

    private final @NonNull OpikConfiguration config;
    private final @NonNull UsageReportService usageReportService;
    private final @NonNull Client client;

    @Override
    public void trackEvent(@NonNull String eventType, @NonNull Map<String, String> properties) {
        if (!config.getAnalytics().isEnabled()) {
            return;
        }

        var prefixedEventType = eventType.startsWith(EVENT_PREFIX) ? eventType : EVENT_PREFIX + eventType;

        var enrichedProperties = new HashMap<>(properties);
        var environment = config.getAnalytics().getEnvironment();
        if (StringUtils.isNotBlank(environment)) {
            enrichedProperties.put("environment", environment);
        }

        var anonymousId = usageReportService.getAnonymousId().orElse("unknown");
        var event = new BiEvent(anonymousId, prefixedEventType, enrichedProperties);

        try (Response response = client.target(URI.create(config.getUsageReport().getUrl()))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .post(Entity.json(event))) {

            if (response.getStatusInfo().getFamily() == Response.Status.Family.SUCCESSFUL && response.hasEntity()) {
                var body = response.readEntity(NotificationEventResponse.class);

                if (body.success()) {
                    log.info("Analytics event '{}' sent successfully", eventType);
                } else {
                    log.warn("Failed to send analytics event '{}': {}", eventType, body.message());
                }
                return;
            }

            log.warn("Failed to send analytics event '{}': {}", eventType, response.getStatusInfo());
        } catch (Exception e) {
            log.warn("Failed to send analytics event '{}'", eventType, e);
        }
    }
}
