package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.metrics.WorkspaceMetricRequest;
import com.comet.opik.api.metrics.WorkspaceMetricResponse;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryRequest;
import com.comet.opik.api.metrics.WorkspaceMetricsSummaryResponse;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class WorkspaceResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/workspaces";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public WorkspaceMetricsSummaryResponse getMetricsSummary(WorkspaceMetricsSummaryRequest request, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/metrics/summaries")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(WorkspaceMetricsSummaryResponse.class);
        }
    }

    public WorkspaceMetricResponse getMetricsDaily(WorkspaceMetricRequest request, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("/metrics")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(WorkspaceMetricResponse.class);
        }
    }
}
