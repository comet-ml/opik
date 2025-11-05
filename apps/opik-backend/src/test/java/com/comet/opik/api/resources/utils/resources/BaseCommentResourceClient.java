package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.BatchDelete;
import com.comet.opik.api.Comment;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.podam.PodamFactoryUtils;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Map;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public abstract class BaseCommentResourceClient {
    protected final String RESOURCE_PATH;
    protected final ClientSupport client;
    protected final String baseURI;

    protected final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();

    public Comment generateAndCreateComment(UUID entityId, String apiKey, String workspaceName, int expectedStatus) {
        Comment comment = Comment.builder().text(podamFactory.manufacturePojo(String.class)).build();
        var commentId = createComment(comment, entityId, apiKey, workspaceName, expectedStatus);

        return comment.toBuilder().id(commentId).build();
    }

    public UUID createComment(Comment comment, UUID entityId, String apiKey, String workspaceName, int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(entityId.toString())
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

    public void updateComment(String updatedText, UUID commentId, String apiKey, String workspaceName,
            int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
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

    public Comment getCommentById(UUID commentId, UUID entityId, String apiKey, String workspaceName,
            int expectedStatus) {

        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(entityId.toString())
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

    /**
     * Helper method to add path segments to a WebTarget.
     * Splits the pathSuffix by "/" and adds each non-empty part as a path segment.
     */
    protected WebTarget addPathSegments(WebTarget target, String pathSuffix) {
        if (pathSuffix != null && !pathSuffix.isEmpty()) {
            String[] pathParts = pathSuffix.split("/");
            for (String part : pathParts) {
                if (!part.isEmpty()) {
                    target = target.path(part);
                }
            }
        }
        return target;
    }

    /**
     * Helper method to add query parameters to a WebTarget.
     * Iterates through the queryParams map and adds each entry as a query parameter.
     */
    protected WebTarget addQueryParameters(WebTarget target, Map<String, String> queryParams) {
        if (queryParams != null) {
            for (Map.Entry<String, String> entry : queryParams.entrySet()) {
                target = target.queryParam(entry.getKey(), entry.getValue());
            }
        }
        return target;
    }
}
