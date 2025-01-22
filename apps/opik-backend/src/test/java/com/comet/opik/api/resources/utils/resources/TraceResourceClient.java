package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.FeedbackScore;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class TraceResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/traces";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

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

    public Comment generateAndCreateComment(UUID traceId, String apiKey, String workspaceName, int expectedStatus) {
        Comment comment = Comment.builder().text(podamFactory.manufacturePojo(String.class)).build();
        var commentId = createComment(comment, traceId, apiKey, workspaceName, expectedStatus);

        return comment.toBuilder().id(commentId).build();
    }

    public UUID createComment(Comment comment, UUID traceId, String apiKey, String workspaceName, int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(traceId.toString())
                .path("comments")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(comment))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            if (expectedStatus == 201) {
                return TestUtils.getIdFromLocation(response.getLocation());
            }

            return null;
        }
    }

    public void updateComment(String updatedText, UUID commentId, UUID traceId, String apiKey, String workspaceName,
            int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(traceId.toString())
                .path("comments")
                .path(commentId.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .method(HttpMethod.PATCH, Entity.json(Comment.builder().text(updatedText).build()))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public Comment getCommentById(UUID commentId, UUID traceId, String apiKey, String workspaceName,
            int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(traceId.toString())
                .path("comments")
                .path(commentId.toString())
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            if (expectedStatus == 200) {
                return response.readEntity(Comment.class);
            }

            return null;
        }
    }

    public void deleteComments(BatchDelete request, String apiKey, String workspaceName) {
        try (var actualResponse = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("comments")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(204);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }
}
