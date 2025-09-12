package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AnnotationQueuesResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/annotation-queues";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public UUID createAnnotationQueue(AnnotationQueue annotationQueue, String apiKey, String workspaceName,
            int expectedStatus) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(annotationQueue))) {

            assertThat(response.getStatus()).isEqualTo(expectedStatus);

            if (expectedStatus != HttpStatus.SC_CREATED) {
                return null;
            }

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }
}
