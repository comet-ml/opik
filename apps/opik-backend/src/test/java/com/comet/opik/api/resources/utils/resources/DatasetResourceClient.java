package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.testcontainers.shaded.com.google.common.net.HttpHeaders;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class DatasetResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/datasets";

    private final ClientSupport client;
    private final String baseURI;

    public UUID createDataset(Dataset dataset, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(dataset))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();

            var id = TestUtils.getIdFromLocation(actualResponse.getLocation());

            assertThat(id).isNotNull();
            assertThat(id.version()).isEqualTo(7);

            return id;
        }
    }

    public void deleteDatasets(List<Dataset> datasets, String apiKey, String workspaceName) {
        datasets.parallelStream()
                .forEach(dataset -> {
                    try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                            .path(dataset.id().toString())
                            .request()
                            .accept(MediaType.APPLICATION_JSON_TYPE)
                            .header(HttpHeaders.AUTHORIZATION, apiKey)
                            .header(WORKSPACE_HEADER, workspaceName)
                            .delete()) {

                        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
                        assertThat(actualResponse.hasEntity()).isFalse();
                    }
                });
    }
}
