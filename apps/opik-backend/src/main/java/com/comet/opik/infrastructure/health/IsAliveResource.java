package com.comet.opik.infrastructure.health;

import com.codahale.metrics.health.HealthCheckRegistry;
import com.comet.opik.infrastructure.AppMetadataService;
import com.comet.opik.infrastructure.OpikConfiguration;
import io.dropwizard.health.DefaultHealthFactory;
import io.dropwizard.health.HealthCheckConfiguration;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.Builder;
import lombok.NonNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Path("/is-alive")
@Produces(MediaType.APPLICATION_JSON)
public class IsAliveResource {

    private final HealthCheckRegistry registry;
    private final AppMetadataService metadataService;
    // Names of health checks marked critical in config; only these gate /is-alive/ping. Empty => gate on all checks
    // (preserves the original behavior when no health config is present).
    private final Set<String> criticalHealthChecks;

    @Inject
    public IsAliveResource(@NonNull HealthCheckRegistry registry, @NonNull AppMetadataService metadataService,
            @NonNull OpikConfiguration configuration) {
        this.registry = registry;
        this.metadataService = metadataService;
        this.criticalHealthChecks = configuration.getHealthFactory()
                .filter(DefaultHealthFactory.class::isInstance)
                .map(DefaultHealthFactory.class::cast)
                .map(DefaultHealthFactory::getHealthCheckConfigurations)
                .orElseGet(List::of)
                .stream()
                .filter(HealthCheckConfiguration::isCritical)
                .map(HealthCheckConfiguration::getName)
                .collect(Collectors.toUnmodifiableSet());
    }

    @Builder(toBuilder = true)
    public record IsAliveResponse(String message, boolean healthy) {

        static IsAliveResponse healthy(String message) {
            return new IsAliveResponse(message, true);
        }

        static IsAliveResponse unhealthy(String message) {
            return new IsAliveResponse(message, false);
        }
    }

    @Builder(toBuilder = true)
    public record VersionResponse(String version) {
    }

    @GET
    @Path("/ping")
    public Response isAlive() {

        // Only critical checks gate liveness. Non-critical checks (e.g. the optional
        // clickhouse-readonly-freeform-sql Agent Insights check) are still reported via /health-check but must not
        // make the server look down to SDKs/FE.
        boolean anyCriticalUnhealthy = registry.runHealthChecks().entrySet().stream()
                .filter(entry -> criticalHealthChecks.isEmpty() || criticalHealthChecks.contains(entry.getKey()))
                .anyMatch(entry -> !entry.getValue().isHealthy());

        if (!anyCriticalUnhealthy) {
            return Response.ok(IsAliveResponse.healthy("Healthy Server")).build();
        } else {
            return Response.serverError().entity(IsAliveResponse.unhealthy("Not Healthy")).build();
        }
    }

    @GET
    @Path("/ver")
    public Response version() {
        return Response.ok(new VersionResponse(metadataService.getVersion())).build();
    }
}
