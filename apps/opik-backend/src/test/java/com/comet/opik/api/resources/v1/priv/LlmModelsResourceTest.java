package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.EffortParam;
import com.comet.opik.api.ModelParams.FlagParam;
import com.comet.opik.api.ModelParams.NumberParam;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import com.comet.opik.utils.JsonUtils;
import io.dropwizard.testing.junit5.DropwizardExtensionsSupport;
import io.dropwizard.testing.junit5.ResourceExtension;
import jakarta.ws.rs.core.GenericType;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import java.math.BigDecimal;
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

    @Test
    void servesEachModelsParamsInSnakeCase() {
        var range = new NumberParam(true, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ONE);
        var refused = new NumberParam(false, null, null, null);
        when(REGISTRY_SERVICE.getRegistry()).thenReturn(Map.of(
                "anthropic", List.of(LlmModelDefinition.builder()
                        .id("claude-x")
                        .params(ModelParams.builder()
                                .temperature(range)
                                .topP(range)
                                .topK(refused)
                                .frequencyPenalty(refused)
                                .presencePenalty(refused)
                                .maxOutputTokens(new NumberParam(true, BigDecimal.ONE, BigDecimal.valueOf(64000),
                                        BigDecimal.valueOf(4000)))
                                .stop(new FlagParam(true))
                                .effort(new EffortParam(true, List.of("low", "high"), "high"))
                                .samplingExclusive(true)
                                .thinksByDefault(false)
                                .onlyRoute("some_route")
                                .routes(Map.of("some_route", ModelParams.builder()
                                        .stop(new FlagParam(false))
                                        .onlyRoute("some_route")
                                        .build()))
                                .build())
                        .build())));

        try (Response response = EXT.target("/v1/private/llm/models").request().get()) {
            assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());

            assertThat(JsonUtils.getJsonNodeFromString(response.readEntity(String.class)))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("""
                            {
                              "anthropic": [{
                                "id": "claude-x",
                                "structured_output": false,
                                "reasoning": false,
                                "params": {
                                  "temperature": {"accepted": true, "min": 0, "max": 1, "default": 1},
                                  "top_p": {"accepted": true, "min": 0, "max": 1, "default": 1},
                                  "top_k": {"accepted": false},
                                  "frequency_penalty": {"accepted": false},
                                  "presence_penalty": {"accepted": false},
                                  "max_output_tokens": {"accepted": true, "min": 1, "max": 64000, "default": 4000},
                                  "stop": {"accepted": true},
                                  "effort": {"accepted": true, "levels": ["low", "high"], "default": "high"},
                                  "sampling_exclusive": true,
                                  "thinks_by_default": false,
                                  "only_route": "some_route",
                                  "routes": {"some_route": {"stop": {"accepted": false}, "only_route": "some_route"}}
                                }
                              }]
                            }"""));
        }
    }

    @Test
    void servesProfilesByProviderInSnakeCase() {
        when(REGISTRY_SERVICE.getProfiles()).thenReturn(Map.of("anthropic", ModelParams.builder()
                .maxOutputTokens(new NumberParam(true, BigDecimal.ONE, BigDecimal.valueOf(64000),
                        BigDecimal.valueOf(4000)))
                .effort(new EffortParam(true, List.of("low", "high"), "high"))
                .samplingExclusive(true)
                .build()));

        try (Response response = EXT.target("/v1/private/llm/models/profiles").request().get()) {
            assertThat(response.getStatus()).isEqualTo(OK.getStatusCode());

            assertThat(JsonUtils.getJsonNodeFromString(response.readEntity(String.class)))
                    .isEqualTo(JsonUtils.getJsonNodeFromString("""
                            {
                              "anthropic": {
                                "max_output_tokens": {"accepted": true, "min": 1, "max": 64000, "default": 4000},
                                "effort": {"accepted": true, "levels": ["low", "high"], "default": "high"},
                                "sampling_exclusive": true
                              }
                            }"""));
        }
    }
}
