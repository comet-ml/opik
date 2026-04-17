package com.comet.opik.infrastructure.bi;

import com.comet.opik.infrastructure.OpikConfiguration;
import com.google.inject.ImplementedBy;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.InvocationCallback;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.glassfish.jersey.client.ClientProperties;

import java.net.URI;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Async HTTP client for sending BI and analytics events to the external stats endpoint.
 * This is the single gateway for all outbound telemetry HTTP traffic.
 *
 * <p><strong>Contract:</strong> {@link #sendEvent} must never throw and must never block the
 * calling thread. It returns a {@link CompletableFuture} that always resolves to {@code true}
 * (sent successfully) or {@code false} (failed or disabled) — it never completes exceptionally.
 * Callers can safely chain {@code thenAccept} without worrying about threading; the future
 * completes on {@code ForkJoinPool} via {@code completeAsync}, not on Jersey's I/O thread.
 *
 * <p><strong>Async requirement:</strong> the HTTP call uses Jersey's
 * {@link jakarta.ws.rs.client.InvocationCallback} API for truly non-blocking I/O. No thread is
 * blocked during the network round-trip. Implementations must keep this guarantee — do not replace
 * with synchronous HTTP calls. Guard all code paths against exceptions, including non-obvious ones
 * such as Lombok {@code @NonNull} null-checks that fire before user code.
 *
 * <p><strong>Current limitations:</strong>
 * <ul>
 *   <li>Requires {@code usageReport.enabled=true} to send. When disabled, returns
 *       {@code completedFuture(false)} immediately.</li>
 *   <li>Connect and read timeouts are configurable via {@code UsageReportConfig} and applied
 *       as per-request Jersey properties ({@link org.glassfish.jersey.client.ClientProperties}).
 *       Shared across all event types (BI and analytics).</li>
 * </ul>
 */
@ImplementedBy(StatsClientImpl.class)
public interface StatsClient {

    /**
     * Send a BI event to the external stats endpoint asynchronously.
     *
     * @param event the event to send (null-safe — returns {@code false} if null)
     * @return a future that resolves to {@code true} on success, {@code false} on any failure;
     *         never completes exceptionally
     */
    CompletableFuture<Boolean> sendEvent(BiEvent event);
}

@RequiredArgsConstructor(onConstructor_ = @Inject)
@Singleton
@Slf4j
class StatsClientImpl implements StatsClient {

    private final @NonNull OpikConfiguration config;
    private final @NonNull Client client;

    @Override
    public CompletableFuture<Boolean> sendEvent(BiEvent event) {
        var eventType = Optional.ofNullable(event).map(BiEvent::eventType).orElse(null);
        try {
            var usageReport = config.getUsageReport();
            if (!usageReport.isEnabled()) {
                log.info("Usage reporting is disabled — skipping event '{}'", eventType);
                return CompletableFuture.completedFuture(false);
            }
            if (event == null) {
                log.warn("Usage reporting received null event, skipping");
                return CompletableFuture.completedFuture(false);
            }

            var result = new CompletableFuture<Boolean>();
            client.target(URI.create(usageReport.getUrl()))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .property(ClientProperties.CONNECT_TIMEOUT,
                            (int) usageReport.getConnectTimeout().toMilliseconds())
                    .property(ClientProperties.READ_TIMEOUT,
                            (int) usageReport.getReadTimeout().toMilliseconds())
                    .async()
                    .post(Entity.json(event), new InvocationCallback<Response>() {
                        @Override
                        public void completed(Response response) {
                            try {
                                var success = processResponse(response, eventType);
                                result.completeAsync(() -> success);
                            } catch (RuntimeException exception) {
                                log.warn("Failed to process response for event, type '{}'", eventType, exception);
                                result.completeAsync(() -> false);
                            }
                        }

                        @Override
                        public void failed(Throwable throwable) {
                            log.warn("Failed to send event, type '{}'", eventType, throwable);
                            result.completeAsync(() -> false);
                        }
                    });

            return result;
        } catch (RuntimeException exception) {
            log.warn("Failed to send event, type '{}'", eventType, exception);
            return CompletableFuture.completedFuture(false);
        }
    }

    private boolean processResponse(Response response, String eventType) {
        try (response) {
            var statusInfo = response.getStatusInfo();
            if (statusInfo.getFamily() == Response.Status.Family.SUCCESSFUL
                    && response.hasEntity()
                    && response.bufferEntity()) {
                var body = response.readEntity(NotificationEventResponse.class);
                if (body.success()) {
                    log.info("Successfully sent event, type '{}', message '{}'", eventType, body.message());
                    return true;
                }
                log.warn("Failed to send event, type '{}', message '{}'", eventType, body.message());
                return false;
            }
            log.warn("Failed to send event, type '{}', statusInfo '{}'", eventType, statusInfo);
            if (response.hasEntity() && response.bufferEntity()) {
                var entity = response.readEntity(String.class);
                log.warn("Failed event, type '{}', statusInfo '{}', response entity: '{}'",
                        eventType, statusInfo, entity);
            }
            return false;
        }
    }
}
