package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.google.inject.ImplementedBy;
import com.google.inject.ProvisionException;
import jakarta.inject.Inject;
import jakarta.inject.Provider;
import jakarta.inject.Singleton;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * Product analytics event tracking service. Enriches events with environment metadata and
 * delegates to {@link StatsClient} for async delivery.
 *
 * <p><strong>Contract:</strong> all methods are fire-and-forget. They must never throw, never block
 * the calling thread on I/O, and never disrupt production API traffic. Implementations must guard
 * against all exceptions — including non-obvious ones such as Lombok {@code @NonNull} null-checks
 * that fire before user code. Use {@code catch (RuntimeException)} at the outermost scope.
 *
 * <p><strong>Async requirement:</strong> any I/O (network calls, DB queries) must be non-blocking
 * or offloaded to a background thread. The current implementation relies on {@link StatsClient}
 * for async HTTP via Jersey's {@link jakarta.ws.rs.client.InvocationCallback} API. Identity
 * resolution ({@code resolveIdentity}) may hit the DB as a rare fallback; this is acceptable
 * because all request-scoped callers resolve identity from the in-memory request context.
 *
 * <p><strong>Current limitations:</strong>
 * <ul>
 *   <li>Identity fallback ({@code usageReportService.getAnonymousId()}) performs a synchronous
 *       DB read. This only fires when there is no request scope and no explicit identity — not
 *       reachable from current request-scoped callers.</li>
 *   <li>Requires {@code usageReport.enabled=true} at the transport level ({@link StatsClient}),
 *       in addition to {@code analytics.enabled=true}, for events to actually be sent.</li>
 * </ul>
 */
@ImplementedBy(AnalyticsServiceImpl.class)
public interface AnalyticsService {
    String EVENT_PREFIX = "opik_";

    /**
     * Track an analytics event, resolving identity from the current request context.
     *
     * @param eventType  event name (auto-prefixed with {@value EVENT_PREFIX} if missing)
     * @param properties event payload
     */
    void trackEvent(String eventType, Map<String, String> properties);

    /**
     * Track an analytics event with an explicit identity, bypassing request context resolution.
     *
     * @param eventType  event name (auto-prefixed with {@value EVENT_PREFIX} if missing)
     * @param properties event payload
     * @param identity   user or installation identifier
     */
    void trackEvent(String eventType, Map<String, String> properties, String identity);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
@Slf4j
class AnalyticsServiceImpl implements AnalyticsService {

    private final @NonNull OpikConfiguration config;
    private final @NonNull UsageReportService usageReportService;
    private final @NonNull StatsClient statsClient;
    private final @NonNull Provider<RequestContext> requestContext;

    @Override
    public void trackEvent(String eventType, Map<String, String> properties) {
        sendEvent(eventType, properties, null);
    }

    @Override
    public void trackEvent(String eventType, Map<String, String> properties, String identity) {
        sendEvent(eventType, properties, identity);
    }

    private void sendEvent(String eventType, Map<String, String> properties, String identity) {
        try {
            if (!config.getAnalytics().isEnabled()) {
                log.info("Analytics disabled — skipping event '{}'", eventType);
                return;
            }

            var resolvedIdentity = identity != null ? identity : resolveIdentity();
            var prefixedEventType = eventType.startsWith(EVENT_PREFIX) ? eventType : EVENT_PREFIX + eventType;

            var enrichedProperties = new HashMap<>(properties);
            var environment = config.getAnalytics().getEnvironment();
            if (StringUtils.isNotBlank(environment)) {
                enrichedProperties.put("environment", environment);
            }

            var event = BiEvent.builder()
                    .anonymousId(resolvedIdentity)
                    .eventType(prefixedEventType)
                    .eventProperties(enrichedProperties)
                    .build();

            statsClient.sendEvent(event);
        } catch (RuntimeException exception) {
            log.warn("Failed to send analytics event '{}'", eventType, exception);
        }
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
