package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.DatasetEvaluator;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorBatchDeleteRequest;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorBatchRequest;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorCreate;
import com.comet.opik.api.DatasetEvaluator.DatasetEvaluatorPage;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class DatasetEvaluatorResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/datasets/%s/evaluators";

    private final ClientSupport client;
    private final String baseURI;

    public List<DatasetEvaluator> createBatch(UUID datasetId, DatasetEvaluatorBatchRequest request,
            String apiKey, String workspaceName) {
        return createBatch(datasetId, request, apiKey, workspaceName, HttpStatus.SC_OK);
    }

    public List<DatasetEvaluator> createBatch(UUID datasetId, DatasetEvaluatorBatchRequest request,
            String apiKey, String workspaceName, int expectedStatus) {
        try (var response = callCreateBatch(datasetId, request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(new GenericType<List<DatasetEvaluator>>() {
                });
            }
            return null;
        }
    }

    public Response callCreateBatch(UUID datasetId, DatasetEvaluatorBatchRequest request,
            String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, datasetId))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request));
    }

    public DatasetEvaluatorPage get(UUID datasetId, String apiKey, String workspaceName, int page, int size) {
        return get(datasetId, apiKey, workspaceName, page, size, HttpStatus.SC_OK);
    }

    public DatasetEvaluatorPage get(UUID datasetId, String apiKey, String workspaceName,
            int page, int size, int expectedStatus) {
        try (var response = callGet(datasetId, apiKey, workspaceName, page, size)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(DatasetEvaluatorPage.class);
            }
            return null;
        }
    }

    public Response callGet(UUID datasetId, String apiKey, String workspaceName, int page, int size) {
        return client.target(RESOURCE_PATH.formatted(baseURI, datasetId))
                .queryParam("page", page)
                .queryParam("size", size)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public void deleteBatch(UUID datasetId, Set<UUID> ids, String apiKey, String workspaceName) {
        deleteBatch(datasetId, ids, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
    }

    public void deleteBatch(UUID datasetId, Set<UUID> ids, String apiKey, String workspaceName, int expectedStatus) {
        var request = DatasetEvaluatorBatchDeleteRequest.builder()
                .ids(List.copyOf(ids))
                .build();
        try (var response = callDeleteBatch(datasetId, request, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Response callDeleteBatch(UUID datasetId, DatasetEvaluatorBatchDeleteRequest request,
            String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI, datasetId))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("DELETE", Entity.json(request));
    }

    public DatasetEvaluatorBatchRequest createBatchRequest(int count) {
        var evaluators = java.util.stream.IntStream.range(0, count)
                .mapToObj(i -> createEvaluator("evaluator-" + i, "metric_type_" + i))
                .toList();
        return DatasetEvaluatorBatchRequest.builder()
                .evaluators(evaluators)
                .build();
    }

    public DatasetEvaluatorCreate createEvaluator(String name, String metricType) {
        return DatasetEvaluatorCreate.builder()
                .name(name)
                .metricType(metricType)
                .metricConfig(createValidMetricConfig())
                .build();
    }

    public JsonNode createValidMetricConfig() {
        String configJson = """
                {
                    "threshold": 0.8,
                    "parameters": {
                        "model": "gpt-4",
                        "temperature": 0.7
                    }
                }
                """;
        return JsonUtils.getJsonNodeFromString(configJson);
    }
}
