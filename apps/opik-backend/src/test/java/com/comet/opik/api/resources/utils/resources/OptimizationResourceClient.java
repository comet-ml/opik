package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.DeleteIdsHolder;
import com.comet.opik.api.Optimization;
import com.comet.opik.api.OptimizationStatus;
import com.comet.opik.api.OptimizationUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import org.testcontainers.shaded.com.google.common.net.HttpHeaders;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class OptimizationResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/optimizations";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public Optimization.OptimizationBuilder createPartialOptimization() {
        return podamFactory.manufacturePojo(Optimization.class).toBuilder()
                .status(OptimizationStatus.RUNNING)
                .numTrials(0L)
                .feedbackScores(null);
    }

    public UUID create(Optimization optimization, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(optimization))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public UUID upsert(Optimization optimization, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(optimization))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }

    public UUID create(String apiKey, String workspaceName) {
        var optimization = createPartialOptimization().build();
        return create(optimization, apiKey, workspaceName);
    }

    public Optimization get(UUID id, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {
            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            if (expectedStatus == HttpStatus.SC_OK) {
                return response.readEntity(Optimization.class);
            }

            return null;
        }
    }

    public Optimization.OptimizationPage find(String apiKey, String workspaceName, int page, int size,
            UUID datasetId, String name, Boolean datasetDeleted, int expectedStatus) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size);

        if (datasetId != null) {
            webTarget = webTarget.queryParam("dataset_id", datasetId);
        }

        if (name != null) {
            webTarget = webTarget.queryParam("name", name);
        }

        if (datasetDeleted != null) {
            webTarget = webTarget.queryParam("dataset_deleted", datasetDeleted);
        }

        try (var response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            return response.readEntity(Optimization.OptimizationPage.class);
        }
    }

    public void delete(Set<UUID> ids, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new DeleteIdsHolder(ids)))) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }

    public void update(UUID id, OptimizationUpdate update, String apiKey, String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(update))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }
}
