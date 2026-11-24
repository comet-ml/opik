package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ScopedAnalyticsQueryRequest;
import com.comet.opik.domain.FreeFormSqlQueryService;
import com.comet.opik.infrastructure.CustomChartsConfig;
import com.comet.opik.infrastructure.ServiceTogglesConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.inject.Provider;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class AnalyticsQueriesResourcePublicEndpointsOffTest {

    private final FreeFormSqlQueryService freeFormSqlQueryService = mock(FreeFormSqlQueryService.class);
    @SuppressWarnings("unchecked")
    private final Provider<RequestContext> requestContext = mock(Provider.class);

    @Test
    @DisplayName("with Custom Charts on but its public endpoints off, returns 501 without running the query")
    void runQuery__whenPublicEndpointsOff__thenNotImplemented() {
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(true);
        var customCharts = new CustomChartsConfig();
        customCharts.setEnabled(true);
        var resource = new AnalyticsQueriesResource(freeFormSqlQueryService, requestContext, serviceToggles,
                customCharts);

        try (Response response = resource.runQuery(
                new ScopedAnalyticsQueryRequest("SELECT toJSONString(map('ok', 1)) AS result", null))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_IMPLEMENTED.getStatusCode());
        }
        verifyNoInteractions(freeFormSqlQueryService);
    }
}
