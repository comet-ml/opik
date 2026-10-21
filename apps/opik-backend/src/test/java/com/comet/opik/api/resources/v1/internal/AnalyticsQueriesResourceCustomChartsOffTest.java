package com.comet.opik.api.resources.v1.internal;

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

class AnalyticsQueriesResourceCustomChartsOffTest {

    private final FreeFormSqlQueryService freeFormSqlQueryService = mock(FreeFormSqlQueryService.class);
    @SuppressWarnings("unchecked")
    private final Provider<RequestContext> requestContext = mock(Provider.class);

    @Test
    @DisplayName("scoped: with Ollie on but Custom Charts off, returns 501 without running the query")
    void executeScopedQuery__whenCustomChartsOff__thenNotImplemented() {
        var serviceToggles = new ServiceTogglesConfig();
        serviceToggles.setOllieEnabled(true);
        var resource = new AnalyticsQueriesResource(freeFormSqlQueryService, requestContext, serviceToggles,
                new CustomChartsConfig());

        try (Response response = resource.executeScopedQuery(
                new ScopedAnalyticsQueryRequest("SELECT toJSONString(map('ok', 1)) AS result", null))) {
            assertThat(response.getStatus()).isEqualTo(Response.Status.NOT_IMPLEMENTED.getStatusCode());
        }
        verifyNoInteractions(freeFormSqlQueryService);
    }
}
