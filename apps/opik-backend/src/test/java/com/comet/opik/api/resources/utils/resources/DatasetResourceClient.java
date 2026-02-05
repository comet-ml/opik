package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.CreateDatasetItemsFromSpansRequest;
import com.comet.opik.api.CreateDatasetItemsFromTracesRequest;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetIdentifier;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemChanges;
import com.comet.opik.api.DatasetItemStreamRequest;
import com.comet.opik.api.DatasetItemsDelete;
import com.comet.opik.api.DatasetVersion;
import com.comet.opik.api.DatasetVersionDiff;
import com.comet.opik.api.DatasetVersionRetrieveRequest;
import com.comet.opik.api.DatasetVersionTag;
import com.comet.opik.api.DatasetVersionUpdate;
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

import java.util.HashSet;
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

    public Dataset getDatasetById(UUID datasetId, String apiKey, String workspaceName) {
        try (var actualResponse = callGetDatasetById(datasetId, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            return actualResponse.readEntity(Dataset.class);
        }
    }

    public Response callGetDatasetById(UUID datasetId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public DatasetPage getDatasets(String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", 1)
                .queryParam("size", 100)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);
            return actualResponse.readEntity(DatasetPage.class);
        }
    }

    public void createDatasetItems(DatasetItemBatch batch, String workspaceName, String apiKey) {
        try (var actualResponse = callCreateDatasetItems(batch, workspaceName, apiKey)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callCreateDatasetItems(DatasetItemBatch batch, String workspaceName, String apiKey) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(jakarta.ws.rs.core.HttpHeaders.AUTHORIZATION, apiKey)
                .header(jakarta.ws.rs.core.HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(batch));
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

    public DatasetItemPage getDatasetItems(UUID datasetId, int page, int size, String version, String apiKey,
            String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .queryParam("page", page)
                .queryParam("size", size);

        if (version != null && !version.isBlank()) {
            target = target.queryParam("version", version);
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

    public Response callGetDatasetItems(UUID datasetId, int page, int size, String version, String apiKey,
            String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .queryParam("page", page)
                .queryParam("size", size);

        if (version != null && !version.isBlank()) {
            target = target.queryParam("version", version);
        }

        return target
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public void deleteDatasets(List<Dataset> datasets, String apiKey, String workspaceName) {
        datasets.parallelStream()
                .forEach(dataset -> deleteDataset(dataset.id(), apiKey, workspaceName));
    }

    public void deleteDataset(UUID id, String apiKey, String workspaceName) {
        try (var actualResponse = callDeleteDataset(id, apiKey, workspaceName)) {
            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Response callDeleteDataset(UUID id, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
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
        return getDatasetItemsWithExperimentItems(datasetId, experimentIds, null, apiKey, workspaceName);
    }

    public DatasetItemPage getDatasetItemsWithExperimentItems(UUID datasetId, List<UUID> experimentIds, String search,
            String apiKey, String workspaceName) {
        var experimentIdsQueryParam = JsonUtils.writeValueAsString(experimentIds);

        var webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .path("experiments")
                .path("items")
                .queryParam("experiment_ids", experimentIdsQueryParam);

        if (search != null && !search.isBlank()) {
            webTarget = webTarget.queryParam("search", search);
        }

        try (var response = webTarget
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

    public DatasetVersion.DatasetVersionPage listVersions(UUID datasetId, String apiKey, String workspaceName) {
        return listVersions(datasetId, apiKey, workspaceName, null, null);
    }

    public DatasetVersion.DatasetVersionPage listVersions(UUID datasetId, String apiKey, String workspaceName,
            Integer page, Integer size) {
        var webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions");

        if (page != null) {
            webTarget = webTarget.queryParam("page", page);
        }
        if (size != null) {
            webTarget = webTarget.queryParam("size", size);
        }

        try (var response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(DatasetVersion.DatasetVersionPage.class);
        }
    }

    public void createVersionTag(UUID datasetId, String versionHash, DatasetVersionTag tag, String apiKey,
            String workspaceName) {

        try (var response = callCreateVersionTag(datasetId, versionHash, tag, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void deleteVersionTag(UUID datasetId, String versionHash, String tag, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path(versionHash)
                .path("tags")
                .path(tag)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callCreateVersionTag(UUID datasetId, String versionHash, DatasetVersionTag tag, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path("hash")
                .path(versionHash)
                .path("tags")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(tag));
    }

    public Response callDeleteVersionTag(UUID datasetId, String versionHash, String tag, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path(versionHash)
                .path("tags")
                .path(tag)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete();
    }

    public DatasetVersion updateVersion(UUID datasetId, String versionHash, DatasetVersionUpdate update, String apiKey,
            String workspaceName) {
        try (var response = callUpdateVersion(datasetId, versionHash, update, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(DatasetVersion.class);
        }
    }

    public Response callUpdateVersion(UUID datasetId, String versionHash, DatasetVersionUpdate update, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path("hash")
                .path(versionHash)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.json(update));
    }

    public DatasetVersionDiff compareVersions(UUID datasetId, String fromHashOrTag,
            String toHashOrTag, String apiKey, String workspaceName) {
        try (var response = callCompareVersions(datasetId, fromHashOrTag, toHashOrTag, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(DatasetVersionDiff.class);
        }
    }

    public Response callCompareVersions(UUID datasetId, String fromHashOrTag, String toHashOrTag, String apiKey,
            String workspaceName) {
        WebTarget target = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path("diff");

        // Both parameters are now optional - when null, endpoint compares latest with draft
        if (fromHashOrTag != null) {
            target = target.queryParam("from", fromHashOrTag);
        }

        if (toHashOrTag != null) {
            target = target.queryParam("to", toHashOrTag);
        }

        return target
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public DatasetVersion restoreVersion(UUID datasetId, String versionRef, String apiKey, String workspaceName) {
        try (var response = callRestoreVersion(datasetId, versionRef, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(DatasetVersion.class);
        }
    }

    public Response callRestoreVersion(UUID datasetId, String versionRef, String apiKey, String workspaceName) {
        var restoreRequest = com.comet.opik.api.DatasetVersionRestore.builder()
                .versionRef(versionRef)
                .build();

        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path("restore")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(restoreRequest));
    }

    public DatasetVersion retrieveVersion(UUID datasetId, String versionName, String apiKey, String workspaceName) {
        try (var response = callRetrieveVersion(datasetId, versionName, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(DatasetVersion.class);
        }
    }

    public Response callRetrieveVersion(UUID datasetId, String versionName, String apiKey, String workspaceName) {
        var retrieveRequest = new DatasetVersionRetrieveRequest(versionName);

        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("versions")
                .path("retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(retrieveRequest));
    }

    public void deleteDatasetItems(List<UUID> itemIds, String apiKey, String workspaceName) {
        try (var response = callDeleteDatasetItems(itemIds, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDeleteDatasetItems(List<UUID> itemIds, String apiKey, String workspaceName) {
        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(new HashSet<>(itemIds))
                .build();
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(deleteRequest));
    }

    public void deleteDatasetItems(DatasetItemsDelete deleteRequest, String workspaceName, String apiKey) {
        try (var response = callDeleteDatasetItems(deleteRequest, workspaceName, apiKey)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDeleteDatasetItems(DatasetItemsDelete deleteRequest, String workspaceName, String apiKey) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(deleteRequest));
    }

    public void deleteDatasetItem(UUID itemId, String apiKey, String workspaceName) {
        try (var response = callDeleteDatasetItem(itemId, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDeleteDatasetItem(UUID itemId, String apiKey, String workspaceName) {
        var deleteRequest = DatasetItemsDelete.builder()
                .itemIds(Set.of(itemId))
                .build();
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(deleteRequest));
    }

    public void deleteDatasetItemsByFilters(UUID datasetId, List<com.comet.opik.api.filter.DatasetItemFilter> filters,
            String apiKey, String workspaceName) {
        try (var response = callDeleteDatasetItemsByFilters(datasetId, filters, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callDeleteDatasetItemsByFilters(UUID datasetId,
            List<com.comet.opik.api.filter.DatasetItemFilter> filters, String apiKey, String workspaceName) {
        var deleteRequest = DatasetItemsDelete.builder()
                .datasetId(datasetId)
                .filters(filters)
                .build();
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(deleteRequest));
    }

    public DatasetVersion applyDatasetItemChanges(UUID datasetId, DatasetItemChanges changes, boolean override,
            String apiKey, String workspaceName) {
        try (var response = callApplyDatasetItemChanges(datasetId, changes, override, apiKey, workspaceName)) {
            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_CREATED);
            return response.readEntity(DatasetVersion.class);
        }
    }

    public Response callApplyDatasetItemChanges(UUID datasetId, DatasetItemChanges changes, boolean override,
            String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(datasetId.toString())
                .path("items")
                .path("changes")
                .queryParam("override", override)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(changes));
    }

    public List<DatasetItem> streamDatasetItems(DatasetItemStreamRequest request, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("stream")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);

            // Read the chunked output as a string and parse each line as a DatasetItem
            String responseBody = response.readEntity(String.class);
            if (responseBody == null || responseBody.isBlank()) {
                return List.of();
            }

            return responseBody.lines()
                    .filter(line -> !line.isBlank())
                    .map(line -> JsonUtils.readValue(line, DatasetItem.class))
                    .toList();
        }
    }
}
