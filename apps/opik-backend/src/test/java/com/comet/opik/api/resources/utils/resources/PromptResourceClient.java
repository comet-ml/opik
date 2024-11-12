package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

public interface PromptResourceClient {

    String PROMPT_PATH = "%s/v1/private/prompts";

    record PromptResourceClientParams(ClientSupport client, String baseURI, PodamFactory podamFactory) {
    }

    static PromptResourceClient create(ClientSupport client, String baseURI, PodamFactory podamFactory) {
        return () -> new PromptResourceClientParams(client, baseURI, podamFactory);
    }

    PromptResourceClientParams params();

    default PromptVersion createPromptVersion(Prompt prompt, String apiKey, String workspaceName) {

        var request = CreatePromptVersion.builder()
                .name(prompt.name())
                .version(params().podamFactory.manufacturePojo(PromptVersion.class))
                .build();

        try (var response = params().client.target(PROMPT_PATH.formatted(params().baseURI) + "/versions")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(PromptVersion.class);
        }
    }

    default UUID createPrompt(Prompt prompt, String apiKey, String workspaceName) {

        var request = params().podamFactory.manufacturePojo(Prompt.class);

        try (var response = params().client.target(PROMPT_PATH.formatted(params().baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);

            return TestUtils.getIdFromLocation(response.getLocation());
        }
    }
}
