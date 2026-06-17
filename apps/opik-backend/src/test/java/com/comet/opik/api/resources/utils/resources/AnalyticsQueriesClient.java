package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AnalyticsQueryRequest;
import com.comet.opik.api.AnalyticsQueryResponse;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AnalyticsQueriesClient {

    private static final String RESOURCE_PATH = "%s/v1/internal/analytics-queries";

    private final ClientSupport client;
    private final String baseURI;

    public AnalyticsQueryResponse execute(UUID projectId, String query, String apiKey, String workspaceName) {
        try (var response = callExecute(projectId, query, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(AnalyticsQueryResponse.class);
        }
    }

    public Response callExecute(UUID projectId, String query, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.entity(AnalyticsQueryRequest.builder().projectId(projectId).query(query).build(),
                        MediaType.APPLICATION_JSON_TYPE));
    }
}
