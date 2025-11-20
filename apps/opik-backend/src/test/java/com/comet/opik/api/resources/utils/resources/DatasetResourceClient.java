package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreateDatasetItemsFromSpansRequest;
import com.comet.opik.api.CreateDatasetItemsFromTracesRequest;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.utils.JsonUtils;
import com.google.common.net.HttpHeaders;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.Dataset.DatasetPage;
import static com.comet.opik.api.DatasetItem.DatasetItemPage;
import static com.comet.opik.api.resources.utils.TestUtils.getIdFromLocation;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
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

            var id = getIdFromLocation(actualResponse.getLocation());

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

    public void createDatasetItemsFromTraces(UUID datasetId, CreateDatasetItemsFromTracesRequest request,
            String apiKey, String workspaceName) {
        try (var actualResponse = callCreateDatasetItemsFromTraces(datasetId, request, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callCreateDatasetItemsFromTraces(UUID datasetId, CreateDatasetItemsFromTracesRequest request,
            String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .path("from-traces")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public void createDatasetItemsFromSpans(UUID datasetId, CreateDatasetItemsFromSpansRequest request,
            String apiKey, String workspaceName) {
        try (var actualResponse = callCreateDatasetItemsFromSpans(datasetId, request, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callCreateDatasetItemsFromSpans(UUID datasetId, CreateDatasetItemsFromSpansRequest request,
            String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .path("from-spans")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public DatasetItem getDatasetItem(UUID itemId, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path(itemId.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            return actualResponse.readEntity(DatasetItem.class);
        }
    }

    public void patchDatasetItem(UUID itemId, DatasetItem patchItem, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path(itemId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.entity(patchItem, MediaType.APPLICATION_JSON_TYPE))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callPatchDatasetItem(UUID itemId, DatasetItem patchItem, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path(itemId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.entity(patchItem, MediaType.APPLICATION_JSON_TYPE));
    }

    public void batchUpdateDatasetItems(com.comet.opik.api.DatasetItemBatchUpdate batchUpdate, String apiKey,
            String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.entity(batchUpdate, MediaType.APPLICATION_JSON_TYPE))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callBatchUpdateDatasetItems(com.comet.opik.api.DatasetItemBatchUpdate batchUpdate, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.entity(batchUpdate, MediaType.APPLICATION_JSON_TYPE));
    }

    public DatasetItemPage getDatasetItems(UUID datasetId, Map<String, Object> queryParams, String apiKey,
            String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items");

        for (Map.Entry<String, Object> entry : queryParams.entrySet()) {
            target = target.queryParam(entry.getKey(), entry.getValue());
        }

        try (var actualResponse = target
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            return actualResponse.readEntity(DatasetItemPage.class);
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

    public DatasetPage getDatasetPage(String apiKey, String workspaceName, String name, Integer size) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI));

        if (name != null) {
            webTarget = webTarget.queryParam("name", name);
        }

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

    public DatasetItemPage getDatasetItemsWithExperimentItems(UUID datasetId, List<UUID> experimentIds, String apiKey,
            String workspaceName) {
        var experimentIdsQueryParam = JsonUtils.writeValueAsString(experimentIds);

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .path("experiments")
                .path("items")
                .queryParam("experiment_ids", experimentIdsQueryParam)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(DatasetItemPage.class);
        }
    }

    public com.comet.opik.api.ProjectStats getDatasetExperimentItemsStats(UUID datasetId, List<UUID> experimentIds,
            String apiKey, String workspaceName, List<com.comet.opik.api.filter.ExperimentsComparisonFilter> filters) {
        var experimentIdsQueryParam = JsonUtils.writeValueAsString(experimentIds);

        var webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .path("experiments")
                .path("items")
                .path("stats")
                .queryParam("experiment_ids", experimentIdsQueryParam);

        if (CollectionUtils.isNotEmpty(filters)) {
            webTarget = webTarget.queryParam("filters",
                    toURLEncodedQueryParam(filters));
        }

        try (var response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(com.comet.opik.api.ProjectStats.class);
        }
    }
}
