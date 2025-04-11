package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.GuardrailBatch;
import com.comet.opik.api.GuardrailBatchItem;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;

import java.util.List;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

public class GuardrailsResourceClient extends BaseCommentResourceClient {
    public GuardrailsResourceClient(ClientSupport client, String baseURI) {
        super("%s/v1/private/guardrails", client, baseURI);
    }

    public void addBatch(List<GuardrailBatchItem> guardrails, String apiKey, String workspaceName) {
        try (var response = client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new GuardrailBatch(guardrails)))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }
}
