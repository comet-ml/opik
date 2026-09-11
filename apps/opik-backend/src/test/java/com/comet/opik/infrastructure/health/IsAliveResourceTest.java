package com.comet.opik.infrastructure.health;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.comet.opik.infrastructure.AppMetadataService;
import com.comet.opik.infrastructure.OpikConfiguration;
import io.dropwizard.health.DefaultHealthFactory;
import io.dropwizard.health.HealthCheckConfiguration;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.util.Arrays;
import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(DropwizardExtensionsSupport.class)
class IsAliveResourceTest {

    private static final HealthCheckRegistry checkRegistry = Mockito.mock(HealthCheckRegistry.class);
    private static final AppMetadataService metadataService = Mockito.mock(AppMetadataService.class);

    private static OpikConfiguration configWithCritical(String... criticalNames) {
        var healthFactory = new DefaultHealthFactory();
        healthFactory.setHealthCheckConfigurations(Arrays.stream(criticalNames).map(name -> {
            var healthCheck = new HealthCheckConfiguration();
            healthCheck.setName(name);
            healthCheck.setCritical(true);
            return healthCheck;
        }).toList());
        var configuration = new OpikConfiguration();
        configuration.setHealthFactory(healthFactory);
        return configuration;
    }

    // "test2" is critical → its failure must gate liveness.
    private static final ResourceExtension EXT_CRITICAL = ResourceExtension.builder()
            .addResource(new IsAliveResource(checkRegistry, metadataService, configWithCritical("test", "test2")))
            .build();

    // only "test" is critical → "test2" is non-critical and must NOT gate liveness.
    private static final ResourceExtension EXT_NON_CRITICAL = ResourceExtension.builder()
            .addResource(new IsAliveResource(checkRegistry, metadataService, configWithCritical("test")))
            .build();

    private static SortedMap<String, HealthCheck.Result> testHealthyAndTest2Unhealthy() {
        SortedMap<String, HealthCheck.Result> sortedMap = new TreeMap<>();
        sortedMap.put("test", HealthCheck.Result.healthy("test"));
        sortedMap.put("test2", HealthCheck.Result.unhealthy("test2"));
        return sortedMap;
    }

    @Test
    void testIsAlive__whenCriticalHealthCheckIsUnhealthy__thenReturn500() {
        Mockito.when(checkRegistry.runHealthChecks()).thenReturn(testHealthyAndTest2Unhealthy());

        var response = EXT_CRITICAL.target("/is-alive/ping").request().get();
        assertEquals(500, response.getStatus());
    }

    @Test
    void testIsAlive__whenOnlyNonCriticalHealthCheckIsUnhealthy__thenReturn200() {
        Mockito.when(checkRegistry.runHealthChecks()).thenReturn(testHealthyAndTest2Unhealthy());

        var response = EXT_NON_CRITICAL.target("/is-alive/ping").request().get();
        assertEquals(200, response.getStatus());
    }
}
