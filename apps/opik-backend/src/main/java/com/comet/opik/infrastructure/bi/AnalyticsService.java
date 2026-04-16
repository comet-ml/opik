package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import com.google.inject.ProvisionException;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

@ImplementedBy(AnalyticsServiceImpl.class)
public interface AnalyticsService {
    String EVENT_PREFIX = "opik_";

    void trackEvent(String eventType, Map<String, String> properties);

    void trackEvent(String identity, String eventType, Map<String, String> properties);
}

@Singleton
@Slf4j
class AnalyticsServiceImpl implements AnalyticsService {

    private final @NonNull OpikConfiguration config;
    private final @NonNull UsageReportService usageReportService;
    private final @NonNull StatsClient statsClient;
    private final @NonNull Provider<RequestContext> requestContext;

    @Inject
    AnalyticsServiceImpl(@NonNull OpikConfiguration config, @NonNull UsageReportService usageReportService,
            @NonNull StatsClient statsClient, @NonNull Provider<RequestContext> requestContext) {
        this.config = config;
        this.usageReportService = usageReportService;
        this.statsClient = statsClient;
        this.requestContext = requestContext;
    }

    @Override
    public void trackEvent(@NonNull String eventType, @NonNull Map<String, String> properties) {
        sendEvent(resolveIdentity(), eventType, properties);
    }

    @Override
    public void trackEvent(@NonNull String identity, @NonNull String eventType,
            @NonNull Map<String, String> properties) {
        sendEvent(identity, eventType, properties);
    }

    private void sendEvent(String identity, String eventType, Map<String, String> properties) {
        if (!config.getAnalytics().isEnabled()) {
            log.info("Analytics disabled — skipping event '{}'", eventType);
            return;
        }

        var prefixedEventType = eventType.startsWith(EVENT_PREFIX) ? eventType : EVENT_PREFIX + eventType;

        var enrichedProperties = new HashMap<>(properties);
        var environment = config.getAnalytics().getEnvironment();
        if (StringUtils.isNotBlank(environment)) {
            enrichedProperties.put("environment", environment);
        }

        var event = BiEvent.builder()
                .anonymousId(identity)
                .eventType(prefixedEventType)
                .eventProperties(enrichedProperties)
                .build();

        statsClient.sendEvent(event);
    }

    private String resolveIdentity() {
        try {
            var userName = requestContext.get().getUserName();
            if (StringUtils.isNotBlank(userName)) {
                return userName;
            }
        } catch (ProvisionException e) {
            log.debug("No request scope available, falling back to installation anonymous ID");
        }
        return usageReportService.getAnonymousId().orElse("unknown");
    }
}
