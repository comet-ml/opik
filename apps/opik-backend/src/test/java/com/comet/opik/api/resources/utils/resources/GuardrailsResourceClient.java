package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.Guardrail;
import com.comet.opik.api.GuardrailBatch;
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
public class GuardrailsResourceClient {
    private static final String RESOURCE_PATH = "%s/v1/private/guardrails";

    private final ClientSupport client;
    private final String baseURI;

    public void addBatch(List<Guardrail> guardrails, String apiKey, String workspaceName) {
        try (var response = sendAddBatch(guardrails, apiKey, workspaceName)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_NO_CONTENT);
        }
    }

    public Response sendAddBatch(List<Guardrail> guardrails, String apiKey, String workspaceName) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new GuardrailBatch(guardrails)));
    }
}
