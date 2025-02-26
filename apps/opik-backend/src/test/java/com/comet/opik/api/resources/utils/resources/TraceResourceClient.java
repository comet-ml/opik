package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.DeleteTraceThreads;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceThread;
import com.comet.opik.api.TraceThreadIdentifier;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class TraceResourceClient extends BaseCommentResourceClient {

    public TraceResourceClient(ClientSupport client, String baseURI) {
        super("%s/v1/private/traces", client, baseURI);
    }

    public UUID createTrace(Trace trace, String apiKey, String workspaceName) {
        try (var response = createTrace(trace, apiKey, workspaceName, HttpStatus.SC_CREATED)) {
            UUID actualId = TestUtils.getIdFromLocation(response.getLocation());

            if (trace.id() != null) {
                assertThat(actualId).isEqualTo(trace.id());
            }

            return actualId;
        }
    }

    public Response createTrace(Trace trace, String apiKey, String workspaceName, int expectedStatus) {
        var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(trace));

        assertThat(response.getStatus()).isEqualTo(expectedStatus);

        return response;
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

    public List<List<FeedbackScoreBatchItem>> createMultiValueScores(List<String> multipleValuesFeedbackScores,
            Project project, String apiKey, String workspaceName) {
        return IntStream.range(0, multipleValuesFeedbackScores.size())
                .mapToObj(i -> {

                    Trace trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                            .name(project.name())
                            .build();

                    createTrace(trace, apiKey, workspaceName);

                    List<FeedbackScoreBatchItem> scores = multipleValuesFeedbackScores.stream()
                            .map(name -> podamFactory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                                    .name(name)
                                    .projectName(project.name())
                                    .id(trace.id())
                                    .build())
                            .toList();

                    feedbackScores(scores, apiKey, workspaceName);

                    return scores;
                }).toList();
    }

    public List<Trace> getByProjectName(String projectName, String apiKey, String workspace) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("project_name", projectName)
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspace)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(Trace.TracePage.class).content();
        }
    }

    public void deleteTraceThreads(List<String> threadId, String projectName, UUID projectId, String apiKey,
            String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(DeleteTraceThreads.builder().threadIds(threadId).projectName(projectName)
                        .projectId(projectId).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public TraceThread getTraceThread(String threadId, UUID projectId, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(TraceThreadIdentifier.builder().projectId(projectId).threadId(threadId).build()))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            return response.readEntity(TraceThread.class);
        }
    }

    public void assertGetTraceThreadNotFound(String threadId, UUID projectId, String apiKey, String workspace) {

        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("threads")
                .path("retrieve")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspace)
                .post(Entity
                        .json(TraceThreadIdentifier.builder().threadId(threadId).projectId(projectId).build()))) {

            String message = "Trace Thread id: %s not found".formatted(threadId);

            assertErrorResponse(actualResponse, message, HttpStatus.SC_NOT_FOUND);
        }
    }

    private static void assertErrorResponse(Response actualResponse, String message, int expectedStatus) {
        assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedStatus);
        assertThat(actualResponse.hasEntity()).isTrue();
        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class).getMessage())
                .isEqualTo(message);
    }

}
