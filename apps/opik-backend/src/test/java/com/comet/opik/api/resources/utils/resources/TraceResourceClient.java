package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class TraceResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/traces";

    private final ClientSupport client;
    private final String baseURI;

    public UUID createTrace(Trace trace, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);

            var actualId = TestUtils.getIdFromLocation(response.getLocation());

            if (trace.id() != null) {
                assertThat(actualId).isEqualTo(trace.id());
            }

            return actualId;
        }
    }

    public void feedbackScores(List<FeedbackScoreBatchItem> score, String apiKey, String workspaceName) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(new FeedbackScoreBatch(score)))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public void feedbackScore(UUID entityId, FeedbackScore score, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(entityId.toString())
                .path("feedback-scores")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(score))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void batchCreateTraces(List<Trace> traces, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(TraceBatch.builder().traces(traces).build()))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public Trace getById(UUID id, String workspaceName, String apiKey) {
        var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get();

        assertThat(response.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_OK);
        return response.readEntity(Trace.class);
    }

    public void deleteTrace(UUID id, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .delete()) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(HttpStatus.SC_NO_CONTENT);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void deleteTraces(BatchDelete request, String workspaceName, String apiKey) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    public void updateTrace(UUID id, TraceUpdate traceUpdate, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(id.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(traceUpdate))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }
}
