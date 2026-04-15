package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

@ImplementedBy(AnalyticsServiceImpl.class)
public interface AnalyticsService {
    String EVENT_PREFIX = "opik_";

    void trackEvent(String eventType, Map<String, String> properties);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
@Slf4j
class AnalyticsServiceImpl implements AnalyticsService {

    private final @NonNull OpikConfiguration config;
    private final @NonNull UsageReportService usageReportService;
    private final @NonNull StatsClient statsClient;

    @Override
    public void trackEvent(@NonNull String eventType, @NonNull Map<String, String> properties) {
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

        var anonymousId = usageReportService.getAnonymousId().orElse("unknown");
        var event = BiEvent.builder()
                .anonymousId(anonymousId)
                .eventType(prefixedEventType)
                .eventProperties(enrichedProperties)
                .build();

        statsClient.sendEvent(event);
    }
}
