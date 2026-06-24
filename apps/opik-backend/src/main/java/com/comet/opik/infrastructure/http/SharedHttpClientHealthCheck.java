package com.comet.opik.infrastructure.http;

import com.comet.opik.infrastructure.SharedHttpClientHealthCheckConfig;
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
 * Detects a shut-down connection pool on the shared outbound {@link Client}. That client is shared by every outbound
 * HTTP caller (auth via {@code RemoteAuthService}, online scoring, and others), so once its Apache pool is closed
 * (e.g. an aborted graceful shutdown or an OOM survivor) the pod keeps serving but fails every outbound call with
 * {@code IllegalStateException: Connection pool shut down}. A shut-down pool is unrecoverable without a process
 * restart, so this is wired to liveness: K8s restarts the poisoned pod and recreates the pool instead of leaving it
 * {@code Ready} and poisoning its share of traffic.
 *
 * <p>Detection is target-independent — the pool throws on {@code lease()} before any I/O — so the probe hits a fixed
 * loopback target and never depends on a reachable upstream or on authentication being enabled.
 */
@Singleton
@Slf4j
public class SharedHttpClientHealthCheck extends NamedHealthCheck {

    public static final String NAME = "shared_http_client";

    /**
     * The {@code lease()} failure on a closed pool surfaces with this message; matched case-insensitively across the
     * cause chain.
     */
    private static final String DEAD_POOL_MARKER = "connection pool shut down";

    /**
     * Dead-pool detection is target-independent: the Apache pool throws on {@code lease()} — before any DNS, connect,
     * or headers — when it is shut down, so the probe only needs a syntactically valid route, not a reachable service.
     * A fixed loopback target avoids any config dependency; nothing listens on port 1, so a healthy pool leases fine
     * and the immediate connection refusal is treated as healthy.
     */
    private static final URI POOL_PROBE_TARGET = URI.create("http://localhost:1");

    private final Client client;
    private final int probeTimeoutMillis;

    @Inject
    public SharedHttpClientHealthCheck(@NonNull Client client,
            @NonNull @Config("sharedHttpClientHealthCheck") SharedHttpClientHealthCheckConfig config) {
        this.client = client;
        // Bounded by @MaxDuration on the config field, so the cast cannot overflow int millis.
        this.probeTimeoutMillis = (int) config.getTimeout().toMilliseconds();
    }

    @Override
    public String getName() {
        return NAME;
    }

    @Override
    protected Result check() {
        try (var response = client.target(POOL_PROBE_TARGET)
                .property(ClientProperties.CONNECT_TIMEOUT, probeTimeoutMillis)
                .property(ClientProperties.READ_TIMEOUT, probeTimeoutMillis)
                .request()
                .head()) {
            // Any reachable response proves the shared connection pool can lease and connect.
            return Result.healthy();
        } catch (Exception exception) {
            if (isConnectionPoolShutDown(exception)) {
                log.error("Shared HTTP client connection pool is shut down; reporting unhealthy to trigger restart",
                        exception);
                return Result.unhealthy(exception);
            }
            // Transient/upstream failures (timeouts, connection refused, DNS, HTTP errors) must NOT flap liveness
            // across the fleet. Only a locally shut-down pool is fatal and restart-worthy.
            return Result.healthy("Shared HTTP client pool is alive; probe error ignored: %s"
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
