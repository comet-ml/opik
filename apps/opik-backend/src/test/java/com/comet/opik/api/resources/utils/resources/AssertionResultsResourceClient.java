package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.AssertionResultBatch;
import com.comet.opik.api.AssertionResultBatchItem;
import com.comet.opik.domain.EntityType;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.RequiredArgsConstructor;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class AssertionResultsResourceClient {

    private static final String RESOURCE_PATH = "%s/v1/private/assertion-results";

    private final ClientSupport client;
    private final String baseURI;

    public void store(EntityType entityType, List<AssertionResultBatchItem> assertionResults, String apiKey,
            String workspaceName) {
        try (var response = callStore(entityType, assertionResults, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response callStore(EntityType entityType, List<AssertionResultBatchItem> assertionResults, String apiKey,
            String workspaceName) {
        var batch = AssertionResultBatch.builder()
                .entityType(entityType)
                .assertionResults(assertionResults)
                .build();
        return callStore(batch, apiKey, workspaceName);
    }

    public Response callStore(AssertionResultBatch batch, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .put(Entity.json(batch));
    }
}
