package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.dashboard.CreateDashboardRequest;
import com.comet.opik.api.dashboard.Dashboard;
import com.comet.opik.api.dashboard.DashboardUpdate;
import com.comet.opik.domain.DashboardService;
import com.comet.opik.infrastructure.auth.RequestContext;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mockito;
import reactor.core.publisher.Mono;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class DashboardResourceIntegrationTest {

    private static final DashboardService mockService = Mockito.mock(DashboardService.class);
    private static final String API_KEY = "test-api-key";
    private static final String WORKSPACE_ID = "test-workspace";

    private static final ResourceExtension RESOURCE = ResourceExtension.builder()
            .addResource(new DashboardResource(mockService, () -> RequestContext.builder()
                    .workspaceId(WORKSPACE_ID)
                    .userName("test-user")
                    .build()))
            .build();

    @Test
    void testCreateDashboard() {
        // Given
        var request = CreateDashboardRequest.builder()
                .name("Test Dashboard")
                .description("Test description")
                .build();

        var expectedDashboard = Dashboard.builder()
                .id(UUID.randomUUID())
                .name("Test Dashboard")
                .description("Test description")
                .build();

        when(mockService.create(any(CreateDashboardRequest.class)))
                .thenReturn(Mono.just(expectedDashboard));

        // When
        Response response = RESOURCE.target("/v1/private/dashboards")
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + API_KEY)
                .post(Entity.entity(request, MediaType.APPLICATION_JSON));

        // Then
        assertThat(response.getStatus()).isEqualTo(201);
        assertThat(response.getLocation().toString()).contains("/v1/private/dashboards/");
    }

    @Test
    void testGetDashboard() {
        // Given
        UUID dashboardId = UUID.randomUUID();
        var expectedDashboard = Dashboard.builder()
                .id(dashboardId)
                .name("Test Dashboard")
                .description("Test description")
                .build();

        when(mockService.findById(dashboardId))
                .thenReturn(Mono.just(expectedDashboard));

        // When
        Response response = RESOURCE.target("/v1/private/dashboards/" + dashboardId)
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + API_KEY)
                .get();

        // Then
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void testUpdateDashboard() {
        // Given
        UUID dashboardId = UUID.randomUUID();
        var update = DashboardUpdate.builder()
                .name("Updated Dashboard")
                .build();

        var expectedDashboard = Dashboard.builder()
                .id(dashboardId)
                .name("Updated Dashboard")
                .description("Test description")
                .build();

        when(mockService.update(any(UUID.class), any(DashboardUpdate.class)))
                .thenReturn(Mono.just(expectedDashboard));

        // When
        Response response = RESOURCE.target("/v1/private/dashboards/" + dashboardId)
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + API_KEY)
                .method("PATCH", Entity.entity(update, MediaType.APPLICATION_JSON));

        // Then
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void testDeleteDashboard() {
        // Given
        UUID dashboardId = UUID.randomUUID();

        when(mockService.delete(dashboardId))
                .thenReturn(Mono.empty());

        // When
        Response response = RESOURCE.target("/v1/private/dashboards/" + dashboardId)
                .request(MediaType.APPLICATION_JSON)
                .header("Authorization", "Bearer " + API_KEY)
                .delete();

        // Then
        assertThat(response.getStatus()).isEqualTo(204);
    }
}
