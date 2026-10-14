package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.util.List;
import java.util.Map;

import static jakarta.ws.rs.core.Response.Status.OK;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(DropwizardExtensionsSupport.class)
class LlmModelsResourceTest {

    private static final LlmModelRegistryService REGISTRY_SERVICE = mock(LlmModelRegistryService.class);

    private static final ResourceExtension EXT = ResourceExtension.builder()
            .setMapper(JsonUtils.getMapper())
            .addResource(new LlmModelsResource(REGISTRY_SERVICE))
            .build();

    @Test
    void servesModelFieldsInSnakeCase() {
        when(REGISTRY_SERVICE.getRegistry()).thenReturn(Map.of(
                "vertex-ai", List.of(LlmModelDefinition.builder()
                        .id("gemini-2.5-pro")
                        .qualifiedName("vertex_ai/gemini-2.5-pro")
                        .label("Gemini 2.5 Pro")
                        .structuredOutput(true)
                        .reasoning(true)
                        .build())));

        try (Response response = EXT.target("/v1/private/llm/models").request().get()) {
            assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());

            var registry = response.readEntity(new GenericType<Map<String, List<Map<String, Object>>>>() {
            });

            assertThat(registry.get("vertex-ai")).containsExactly(Map.of(
                    "id", "gemini-2.5-pro",
                    "qualified_name", "vertex_ai/gemini-2.5-pro",
                    "label", "Gemini 2.5 Pro",
                    "structured_output", true,
                    "reasoning", true));
        }
    }

    @Test
    void servesFlagsTheRegistryLeavesOutAsFalse() {
        when(REGISTRY_SERVICE.getRegistry()).thenReturn(Map.of(
                "openai", List.of(LlmModelDefinition.builder().id("gpt-4.1").build())));

        try (Response response = EXT.target("/v1/private/llm/models").request().get()) {
            assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());

            var registry = response.readEntity(new GenericType<Map<String, List<Map<String, Object>>>>() {
            });

            assertThat(registry.get("openai")).containsExactly(Map.of(
                    "id", "gpt-4.1",
                    "structured_output", false,
                    "reasoning", false));
        }
    }
}
