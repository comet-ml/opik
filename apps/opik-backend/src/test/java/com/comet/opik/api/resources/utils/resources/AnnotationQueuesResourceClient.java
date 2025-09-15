package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueBatch;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AnnotationQueuesResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/annotation-queues";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public void createAnnotationQueueBatch(List<AnnotationQueue> annotationQueues, String apiKey, String workspaceName,
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
}
