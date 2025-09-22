package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.metrics.ProjectMetricRequest;
import com.comet.opik.api.metrics.ProjectMetricResponse;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.reflect.TypeUtils;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.lang.reflect.Type;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class ProjectMetricsResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/projects/%s/metrics";

    private final ClientSupport client;
    private final String baseURI;

    public <T extends Number> ProjectMetricResponse<T> getProjectMetrics(
            UUID projectId, ProjectMetricRequest request, Class<T> aClass, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI, projectId))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.hasEntity()).isTrue();

            Type parameterize = TypeUtils.parameterize(ProjectMetricResponse.class, aClass);
            return response.readEntity(new GenericType<>(parameterize));
        }
    }
}
