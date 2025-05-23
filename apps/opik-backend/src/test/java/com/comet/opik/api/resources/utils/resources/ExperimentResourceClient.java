package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemBulkUpload;
import com.comet.opik.api.ExperimentItemStreamRequest;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.ExperimentStreamRequest;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.glassfish.jersey.client.ChunkedInput;
import org.testcontainers.shaded.com.google.common.net.HttpHeaders;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

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
                .type(ExperimentType.REGULAR)
                .optimizationId(null)
                .usage(null);
    }

    public List<Experiment> generateExperimentList() {
        return PodamFactoryUtils.manufacturePojoList(podamFactory, Experiment.class).stream()
                .map(experiment -> experiment.toBuilder()
                        .promptVersion(null)
                        .promptVersions(null)
                        .duration(null)
                        .totalEstimatedCost(null)
                        .usage(null)
                        .type(ExperimentType.REGULAR)
                        .optimizationId(null)
                        .build())
                .toList();
    }

    public UUID create(Experiment experiment, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(experiment))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public UUID create(String apiKey, String workspaceName) {
        var experiment = createPartialExperiment().build();
        return create(experiment, apiKey, workspaceName);
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

}
