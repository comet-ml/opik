package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.api.AnnotationQueueItemIds;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.SequencedSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AnnotationQueuesResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/annotation-queues";

    private final ClientSupport client;
    private final String baseURI;

    public void createAnnotationQueueBatch(SequencedSet<AnnotationQueue> annotationQueues, String apiKey,
            String workspaceName,
            int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path("batch")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(AnnotationQueueBatch.builder().annotationQueues(annotationQueues).build()))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public void addItemsToAnnotationQueue(UUID queueId, Set<UUID> itemIds, String apiKey,
            String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(queueId.toString())
                .path("items")
                .path("add")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(AnnotationQueueItemIds.builder().ids(itemIds).build()))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public void removeItemsFromAnnotationQueue(UUID queueId, Set<UUID> itemIds, String apiKey,
            String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(queueId.toString())
                .path("items")
                .path("delete")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(AnnotationQueueItemIds.builder().ids(itemIds).build()))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);
        }
    }

    public AnnotationQueue getAnnotationQueueById(UUID queueId, String apiKey,
            String workspaceName, int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .path(queueId.toString())
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            if (expectedStatus == 200) {
                return response.readEntity(AnnotationQueue.class);
            }
            return null;
        }
    }

    public AnnotationQueue.AnnotationQueuePage findAnnotationQueues(int page, int size, String name,
            String filters, String sorting, String apiKey, String workspaceName, int expectedStatus) {
        WebTarget webTarget = client.target(RESOURCE_PATH.formatted(baseURI))
                .queryParam("page", page)
                .queryParam("size", size);

        if (name != null) {
            webTarget = webTarget.queryParam("name", name);
        }

        if (filters != null) {
            webTarget = webTarget.queryParam("filters", filters);
        }

        if (sorting != null) {
            webTarget = webTarget.queryParam("sorting", sorting);
        }

        try (var response = webTarget
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            if (expectedStatus == 200) {
                return response.readEntity(AnnotationQueue.AnnotationQueuePage.class);
            }
            return null;
        }
    }
}
