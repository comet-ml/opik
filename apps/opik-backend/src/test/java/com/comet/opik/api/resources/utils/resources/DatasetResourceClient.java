package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.testcontainers.shaded.com.google.common.net.HttpHeaders;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.Dataset.DatasetPage;
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

    public void createDatasetItems(DatasetItemBatch batch, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION, apiKey)
                .header(jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(batch))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void deleteDatasets(List<Dataset> datasets, String apiKey, String workspaceName) {
        datasets.parallelStream()
                .forEach(dataset -> deleteDataset(dataset.id(), apiKey, workspaceName));
    }

    public void deleteDataset(UUID id, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void deleteDatasetByName(String name, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new DatasetIdentifier(name)))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void deleteDatasetsBatch(Set<UUID> ids, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete-batch")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new BatchDelete(ids)))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public DatasetPage getDatasetPage(String apiKey, String workspaceName, Integer size, PromptVersion promptVersion) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("with_experiments_only", true)
                .queryParam("prompt_id", promptVersion.promptId());

        if (size != null && size > 0) {
            webTarget = webTarget.queryParam("size", size);
        }

        var actualResponse = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        return actualResponse.readEntity(DatasetPage.class);
    }
}
