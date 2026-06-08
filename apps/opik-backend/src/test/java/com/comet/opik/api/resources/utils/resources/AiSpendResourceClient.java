package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.api.spend.SpendCompositionResponse;
import com.comet.opik.api.spend.SpendMetricRequest;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AiSpendResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/ai-spend";

    private final ClientSupport client;
    private final String baseURI;

    public WorkspaceMetricsSummaryResponse getSummary(SpendMetricRequest request, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/summary")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(WorkspaceMetricsSummaryResponse.class);
        }
    }

    public SpendCompositionResponse getComposition(SpendMetricRequest request, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/composition")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(SpendCompositionResponse.class);
        }
    }
}
