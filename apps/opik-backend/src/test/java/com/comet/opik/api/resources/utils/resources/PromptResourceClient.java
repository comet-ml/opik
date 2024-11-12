package com.comet.opik.api.resources.utils.resources;

import com.comet.opik.api.CreatePromptVersion;
import com.comet.opik.api.Prompt;
import com.comet.opik.api.PromptVersion;
import com.comet.opik.infrastructure.auth.RequestContext;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.RequiredArgsConstructor;
import org.apache.hc.core5.http.HttpStatus;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import uk.co.jemos.podam.api.PodamFactory;

import static org.assertj.core.api.Assertions.assertThat;

@RequiredArgsConstructor
public class PromptResourceClient {

    private static final String PROMPT_PATH = "%s/v1/private/prompts";

    private final ClientSupport client;
    private final String baseURI;
    private final PodamFactory podamFactory;

    public PromptVersion createPromptVersion(Prompt prompt, String apiKey, String workspaceName) {

        var request = CreatePromptVersion.builder()
                .name(prompt.name())
                .version(podamFactory.manufacturePojo(PromptVersion.class))
                .build();

        try (var response = client.target(PROMPT_PATH.formatted(baseURI) + "/versions")
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(RequestContext.WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(request))) {

            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);

            return response.readEntity(PromptVersion.class);
        }
    }
}
