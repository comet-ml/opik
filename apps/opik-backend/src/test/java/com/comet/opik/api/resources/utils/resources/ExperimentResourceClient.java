package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.ExperimentUpdate;
import com.comet.opik.api.IdsHolder;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.net.HttpHeaders;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.commons.collections4.CollectionUtils;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class ExperimentResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/experiments";

    private static final GenericType<ChunkedInput<String>> CHUNKED_INPUT_STRING_GENERIC_TYPE = new GenericType<>() {
    };

    private static final TypeReference<Experiment> EXPERIMENT_TYPE_REFERENCE = new TypeReference<>() {
    };

    private static final TypeReference<ExperimentItem> ITEM_TYPE_REFERENCE = new TypeReference<>() {

        @Override
        public Type getType() {
            return ExperimentItem.class;
        }

    };

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public Experiment.ExperimentBuilder createPartialExperiment() {
        return podamFactory.manufacturePojo(Experiment.class).toBuilder()
                .promptVersion(null)
                .promptVersions(null)
                .duration(null)
                .totalEstimatedCost(null)
                .totalEstimatedCostAvg(null)
                .type(ExperimentType.REGULAR)
                .optimizationId(null)
                .usage(null)
                .projectId(null)
                .datasetVersionId(null)
                .datasetVersionSummary(null);
    }

    public List<Experiment> generateExperimentList() {
        return PodamFactoryUtils.manufacturePojoList(podamFactory, Integer.class).stream()
                .map(i -> createPartialExperiment().build())
                .toList();
    }

    public UUID create(Experiment experiment, String apiKey, String workspaceName) {
        try (var response = callCreate(experiment, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public Response callCreate(Experiment experiment, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(experiment));
    }

    public UUID create(String apiKey, String workspaceName) {
        var experiment = createPartialExperiment().build();
        return create(experiment, apiKey, workspaceName);
    }

    public Experiment getExperiment(UUID experimentId, String apiKey, String workspaceName) {
        try (var response = callGetExperiment(experimentId, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(Experiment.class);
        }
    }

    public Response callGetExperiment(UUID experimentId, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(experimentId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();
    }

    public List<Experiment> streamExperiments(ExperimentStreamRequest experimentStreamRequest, String apiKey,
            String workspaceName) {
        try (var actualResponse = streamExperiments(experimentStreamRequest, apiKey, workspaceName, HttpStatus.SC_OK)) {
            return getStreamed(actualResponse, EXPERIMENT_TYPE_REFERENCE);
        }
    }

    public Response streamExperiments(ExperimentStreamRequest experimentStreamRequest, String apiKey,
            String workspaceName, int expectedStatus) {
        var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("stream")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(experimentStreamRequest));
        assertThat(response.getStatus()).isEqualTo(expectedStatus);
        return response;
    }

    public void createExperimentItem(Set<ExperimentItem> experimentItems, String apiKey, String workspaceName) {
        try (var response = callCreateExperimentItem(experimentItems, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callCreateExperimentItem(Set<ExperimentItem> experimentItems, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new ExperimentItemsBatch(experimentItems)));
    }

    public <T> List<T> getStreamed(Response response, TypeReference<T> valueTypeRef) {
        var items = new ArrayList<T>();
        try (var inputStream = response.readEntity(CHUNKED_INPUT_STRING_GENERIC_TYPE)) {
            String stringItem;
            while ((stringItem = inputStream.read()) != null) {
                items.add(JsonUtils.readValue(stringItem, valueTypeRef));
            }
        }
        return items;
    }

    public List<ExperimentItem> getExperimentItems(String experimentName, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("stream")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new ExperimentItemStreamRequest(experimentName, null, null, false)))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return getStreamed(response, ITEM_TYPE_REFERENCE);
        }
    }

    public List<ExperimentItem> streamExperimentItems(ExperimentItemStreamRequest request, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("stream")
                .request()
                .accept(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return getStreamed(response, ITEM_TYPE_REFERENCE);
        }
    }

    public void bulkUploadExperimentItem(ExperimentItemBulkUpload bulkUpload, String apiKey, String workspaceName) {
        try (var response = callExperimentItemBulkUpload(bulkUpload, apiKey, workspaceName)) {
            assertThat(response.hasEntity()).isFalse();
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callExperimentItemBulkUpload(ExperimentItemBulkUpload bulkUpload, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path("items")
                .path("bulk")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(bulkUpload));
    }

    public ExperimentGroupResponse findGroups(List<GroupBy> groups, Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters, String name, String apiKey,
            String workspaceName, int expectedStatus) {
        return findGroups(groups, types, filters, name, null, apiKey, workspaceName, expectedStatus);
    }

    public ExperimentGroupResponse findGroups(List<GroupBy> groups, Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters, String name, UUID projectId, String apiKey,
            String workspaceName, int expectedStatus) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("groups")
                .queryParam("name", name);

        if (CollectionUtils.isNotEmpty(types)) {
            webTarget = webTarget.queryParam("types", JsonUtils.writeValueAsString(types));
        }

        if (CollectionUtils.isNotEmpty(filters)) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        if (CollectionUtils.isNotEmpty(groups)) {
            webTarget = webTarget.queryParam("groups", toURLEncodedQueryParam(groups));
        }

        if (projectId != null) {
            webTarget = webTarget.queryParam("project_id", projectId);
        }

        try (Response response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(ExperimentGroupResponse.class);
            }
            return null;
        }
    }

    public ExperimentGroupAggregationsResponse findGroupsAggregations(List<GroupBy> groups, Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters, String name, String apiKey,
            String workspaceName, int expectedStatus) {
        return findGroupsAggregations(groups, types, filters, name, null, apiKey, workspaceName, expectedStatus);
    }

    public ExperimentGroupAggregationsResponse findGroupsAggregations(List<GroupBy> groups, Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters, String name, UUID projectId, String apiKey,
            String workspaceName, int expectedStatus) {
        return findGroupsAggregations(groups, types, filters, name, projectId, false, apiKey, workspaceName,
                expectedStatus);
    }

    public ExperimentGroupAggregationsResponse findGroupsAggregations(List<GroupBy> groups, Set<ExperimentType> types,
            List<? extends ExperimentFilter> filters, String name, UUID projectId, boolean projectDeleted,
            String apiKey, String workspaceName, int expectedStatus) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("groups")
                .path("aggregations")
                .queryParam("name", name);

        if (CollectionUtils.isNotEmpty(types)) {
            webTarget = webTarget.queryParam("types", JsonUtils.writeValueAsString(types));
        }

        if (CollectionUtils.isNotEmpty(filters)) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
        }

        if (CollectionUtils.isNotEmpty(groups)) {
            webTarget = webTarget.queryParam("groups", toURLEncodedQueryParam(groups));
        }

        if (projectId != null) {
            webTarget = webTarget.queryParam("project_id", projectId);
        }

        if (projectDeleted) {
            webTarget = webTarget.queryParam("project_deleted", projectDeleted);
        }

        try (Response response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(ExperimentGroupAggregationsResponse.class);
            }
            return null;
        }
    }

    public Experiment.ExperimentPage findExperiments(
            int page, int size, boolean forceSorting, String apiKey, String workspaceName) {
        return findExperiments(
                page,
                size,
                null,
                null,
                null,
                null,
                false,
                null,
                null,
                forceSorting,
                null,
                null,
                false,
                apiKey,
                workspaceName,
                HttpStatus.SC_OK);
    }

    public Experiment.ExperimentPage findExperiments(
            int page, int size, String name, String apiKey, String workspaceName) {
        return findExperiments(page, size, null, null, null, name, false, null, null, null, apiKey, workspaceName,
                HttpStatus.SC_OK);
    }

    public Experiment.ExperimentPage findExperiments(
            int page, int size, UUID datasetId, UUID optimizationId, Set<ExperimentType> types, String name,
            boolean datasetDeleted, UUID promptId, String sorting, List<? extends ExperimentFilter> filters,
            String apiKey, String workspaceName, int expectedStatus) {
        return findExperiments(page, size, datasetId, optimizationId, types, name, datasetDeleted, promptId, sorting,
                false, filters, null, false, apiKey, workspaceName, expectedStatus);
    }

    public Experiment.ExperimentPage findExperiments(
            int page, int size, UUID datasetId, UUID optimizationId, Set<ExperimentType> types, String name,
            boolean datasetDeleted, UUID promptId, String sorting, boolean forceSorting,
            List<? extends ExperimentFilter> filters, UUID projectId, boolean projectDeleted,
            String apiKey, String workspaceName, int expectedStatus) {

        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size);

        if (datasetId != null) {
            webTarget = webTarget.queryParam("datasetId", datasetId);
        }
        if (optimizationId != null) {
            webTarget = webTarget.queryParam("optimization_id", optimizationId);
        }
        if (CollectionUtils.isNotEmpty(types)) {
            webTarget = webTarget.queryParam("types", JsonUtils.writeValueAsString(types));
        }
        if (name != null) {
            webTarget = webTarget.queryParam("name", name);
        }
        if (datasetDeleted) {
            webTarget = webTarget.queryParam("dataset_deleted", true);
        }
        if (promptId != null) {
            webTarget = webTarget.queryParam("prompt_id", promptId);
        }
        if (sorting != null) {
            webTarget = webTarget.queryParam("sorting", sorting);
        }
        if (CollectionUtils.isNotEmpty(filters)) {
            webTarget = webTarget.queryParam("filters", toURLEncodedQueryParam(filters));
        }
        if (projectId != null) {
            webTarget = webTarget.queryParam("project_id", projectId);
        }
        if (projectDeleted) {
            webTarget = webTarget.queryParam("project_deleted", projectDeleted);
        }
        if (forceSorting) {
            webTarget = webTarget.queryParam("force_sorting", true);
        }

        try (Response response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(Experiment.ExperimentPage.class);
            }
            return null;
        }
    }

    public Response updateExperiment(UUID experimentId, ExperimentUpdate experimentUpdate, String apiKey,
            String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .path(experimentId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method("PATCH", Entity.json(experimentUpdate));
    }

    public void updateExperiment(UUID experimentId, ExperimentUpdate experimentUpdate, String apiKey,
            String workspaceName, int expectedStatus) {
        try (Response response = updateExperiment(experimentId, experimentUpdate, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public void finishExperiments(Set<UUID> ids, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("finish")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new IdsHolder(ids)))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }
}
