package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.AuthenticationConfig;
import com.comet.opik.infrastructure.OpikConfiguration;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.hc.client5.http.impl.ConnectionShutdownException;
import org.glassfish.jersey.client.ClientProperties;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;

import java.net.URI;
import java.util.Locale;

/**
 * Detects a shut-down connection pool on the shared outbound {@link Client}. The shared client is used by every
 * authenticated request via {@code RemoteAuthService}, so once its Apache pool is closed (e.g. an aborted graceful
 * shutdown or an OOM survivor) the pod keeps serving but fails every auth call with
 * {@code IllegalStateException: Connection pool shut down}. Wired to liveness so K8s restarts the poisoned pod and
 * recreates the pool, instead of leaving it {@code Ready} and poisoning its share of traffic.
 */
@Singleton
@Slf4j
public class AuthHttpClientHealthCheck extends NamedHealthCheck {

    public static final String NAME = "auth_http_client";

    // The lease() failure on a closed pool surfaces as this message; matched case-insensitively across the cause chain.
    private static final String DEAD_POOL_MARKER = "connection pool shut down";
    private static final int PROBE_TIMEOUT_MILLIS = 2_000;

    private final Client client;
    private final boolean enabled;
    private final URI reactServiceUri;

    @Inject
    public AuthHttpClientHealthCheck(@NonNull Client client, @NonNull OpikConfiguration configuration) {
        this.client = client;
        AuthenticationConfig authConfig = configuration.getAuthentication();
        this.enabled = authConfig.isEnabled() && authConfig.getReactService() != null
                && StringUtils.isNotBlank(authConfig.getReactService().url());
        this.reactServiceUri = enabled ? URI.create(authConfig.getReactService().url()) : null;
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected Result check() {
        if (!enabled) {
            return Result.healthy("Authentication disabled; shared HTTP client not exercised");
        }
        try (var response = client.target(reactServiceUri)
                .property(ClientProperties.CONNECT_TIMEOUT, PROBE_TIMEOUT_MILLIS)
                .property(ClientProperties.READ_TIMEOUT, PROBE_TIMEOUT_MILLIS)
                .request()
                .head()) {
            // Any reachable response (including 4xx/5xx) proves the shared connection pool can lease and connect.
            return Result.healthy();
        } catch (Exception exception) {
            if (isConnectionPoolShutDown(exception)) {
                log.error("Shared HTTP client connection pool is shut down; reporting unhealthy to trigger restart",
                        exception);
                return Result.unhealthy(exception);
            }
            // Upstream/transient failures (timeouts, connection refused, DNS, HTTP errors) must NOT flap liveness across
            // the fleet during an auth-service blip. Only a locally shut-down pool is fatal and restart-worthy.
            return Result.healthy("Shared HTTP client pool is alive; upstream probe error ignored: %s"
                    .formatted(exception.getMessage()));
        }
    }

    static boolean isConnectionPoolShutDown(Throwable throwable) {
        for (Throwable cause = throwable; cause != null && cause != cause.getCause(); cause = cause.getCause()) {
            // ConnectionShutdownException is the direct httpclient5 shutdown signal but carries a null message, so it
            // must be matched by type. The message match stays as a fallback for the pool-level
            // IllegalStateException("Connection pool shut down") thrown by StrictConnPool.lease().
            if (cause instanceof ConnectionShutdownException) {
                return true;
            }
            if (cause instanceof IllegalStateException && cause.getMessage() != null
                    && cause.getMessage().toLowerCase(Locale.ROOT).contains(DEAD_POOL_MARKER)) {
                return true;
            }
        }
        return false;
    }
}
