package com.comet.opik.infrastructure.health;

import com.codahale.metrics.health.HealthCheck;
import com.codahale.metrics.health.HealthCheckRegistry;
import com.comet.opik.infrastructure.AppMetadataService;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;

import java.util.SortedMap;
import java.util.TreeMap;

import static org.junit.jupiter.api.Assertions.assertEquals;

@ExtendWith(DropwizardExtensionsSupport.class)
class IsAliveResourceTest {

    private static final HealthCheckRegistry checkRegistry = Mockito.mock(HealthCheckRegistry.class);
    private static final AppMetadataService metadataService = Mockito.mock(AppMetadataService.class);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .addResource(new IsAliveResource(checkRegistry, metadataService))
            .build();

    @Test
    void testIsAlive__whenHealthCheckIsUnhealthy() {

        SortedMap<String, HealthCheck.Result> sortedMap = new TreeMap<>();

        sortedMap.put("test", HealthCheck.Result.healthy("test"));
        sortedMap.put("test2", HealthCheck.Result.unhealthy("test2"));

        Mockito.when(checkRegistry.runHealthChecks())
                .thenReturn(sortedMap);

        var response = EXT.target("/is-alive/ping").request().get();
        assertEquals(500, response.getStatus());
    }

}