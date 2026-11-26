package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.DashboardSavedQuery;
import com.comet.opik.domain.DashboardSavedQueryService;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class DashboardSavedQueriesPublicResourceOffTest {

    private final DashboardSavedQueryService dashboardSavedQueryService = mock(DashboardSavedQueryService.class);

    @Test
    @DisplayName("with Custom Charts on but its public endpoints off, save and get return 501 without touching storage")
    void saveAndGet__whenPublicEndpointsOff__thenNotImplemented() {
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(true);
        var customCharts = new CustomChartsConfig();
        customCharts.setEnabled(true);
        var resource = new DashboardSavedQueriesPublicResource(dashboardSavedQueryService, serviceToggles,
                customCharts);

        var query = DashboardSavedQuery.builder().sql("SELECT toJSONString(map('ok', 1)) AS result").build();
        assertThatThrownBy(() -> resource.save(query, mock(UriInfo.class)))
                .isInstanceOfSatisfying(ServerErrorException.class, error -> assertThat(error.getResponse().getStatus())
                        .isEqualTo(Response.Status.NOT_IMPLEMENTED.getStatusCode()));
        assertThatThrownBy(() -> resource.get(UUID.randomUUID()))
                .isInstanceOfSatisfying(ServerErrorException.class, error -> assertThat(error.getResponse().getStatus())
                        .isEqualTo(Response.Status.NOT_IMPLEMENTED.getStatusCode()));
        verifyNoInteractions(dashboardSavedQueryService);
    }
}
