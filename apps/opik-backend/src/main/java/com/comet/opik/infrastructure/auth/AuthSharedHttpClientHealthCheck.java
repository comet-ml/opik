package com.comet.opik.infrastructure.auth;

import com.comet.opik.infrastructure.AuthenticationConfig;
import com.google.common.base.Throwables;
import jakarta.inject.Inject;
import jakarta.inject.Singleton;
import jakarta.ws.rs.client.Client;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.ConnectionShutdownException;
import org.glassfish.jersey.client.ClientProperties;
import ru.vyarus.dropwizard.guice.module.installer.feature.health.NamedHealthCheck;
import ru.vyarus.dropwizard.guice.module.yaml.bind.Config;

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
public class AuthSharedHttpClientHealthCheck extends NamedHealthCheck {

    public static final String NAME = "auth_shared_http_client";

    /**
     * The {@code lease()} failure on a closed pool surfaces with this message; matched case-insensitively across the
     * cause chain.
     */
    private static final String DEAD_POOL_MARKER = "connection pool shut down";

    private final Client client;
    private final boolean enabled;
    private final URI reactServiceUri;
    private final int probeTimeoutMillis;

    @Inject
    public AuthSharedHttpClientHealthCheck(@NonNull Client client,
            @NonNull @Config("authentication") AuthenticationConfig authConfig) {
        this.client = client;
        this.enabled = authConfig.isEnabled() && authConfig.isReactServiceConfigured();
        this.reactServiceUri = enabled ? URI.create(authConfig.getReactService().url()) : null;
        this.probeTimeoutMillis = Math.toIntExact(authConfig.getHealthCheckTimeout().toMilliseconds());
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
                .property(ClientProperties.CONNECT_TIMEOUT, probeTimeoutMillis)
                .property(ClientProperties.READ_TIMEOUT, probeTimeoutMillis)
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

    private static boolean isConnectionPoolShutDown(Throwable throwable) {
        // ConnectionShutdownException is the direct httpclient5 shutdown signal but carries a null message, so it must
        // be matched by type. The message match stays as a fallback for the pool-level
        // IllegalStateException("Connection pool shut down") thrown by StrictConnPool.lease(). Throwables.getCausalChain
        // walks the full cause chain and guards against cyclic causes.
        return Throwables.getCausalChain(throwable).stream()
                .anyMatch(cause -> cause instanceof ConnectionShutdownException
                        || (cause instanceof IllegalStateException && cause.getMessage() != null
                                && cause.getMessage().toLowerCase(Locale.ROOT).contains(DEAD_POOL_MARKER)));
    }
}
