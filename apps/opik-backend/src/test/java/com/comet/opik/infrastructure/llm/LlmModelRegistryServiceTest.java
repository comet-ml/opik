package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService.ModelEntry;
import com.comet.opik.utils.JsonUtils;
import jakarta.ws.rs.client.Client;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.client.WebTarget;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmModelRegistryServiceTest {

    @Test
    void loadFromClasspath() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");

        var service = new LlmModelRegistryService(config);
        var registry = service.getRegistry();

        assertThat(registry).containsKeys("openai", "anthropic", "vertex-ai");
        assertThat(registry.get("openai")).hasSize(3);
        assertThat(registry.get("openai").get(0).id()).isEqualTo("gpt-4o");
        assertThat(registry.get("openai").get(0).structuredOutput()).isTrue();
        assertThat(registry.get("openai").get(0).reasoning()).isFalse();
        assertThat(registry.get("openai").get(1).id()).isEqualTo("gpt-3.5-turbo");
        assertThat(registry.get("openai").get(1).structuredOutput()).isFalse();
        assertThat(registry.get("openai").get(2).id()).isEqualTo("o3");
        assertThat(registry.get("openai").get(2).reasoning()).isTrue();
        assertThat(registry.get("openai").get(2).structuredOutput()).isTrue();
    }

    @Test
    void loadParsesLabelWhenPresentAndNullWhenAbsent() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");

        var service = new LlmModelRegistryService(config);
        var openai = service.getRegistry().get("openai");

        assertThat(openai.get(0).id()).isEqualTo("gpt-4o");
        assertThat(openai.get(0).label()).isEqualTo("GPT 4o");
        assertThat(openai.get(1).label()).isNull();
    }

    @Test
    void loadWithQualifiedName() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");

        var service = new LlmModelRegistryService(config);
        var vertexModels = service.getRegistry().get("vertex-ai");

        assertThat(vertexModels).hasSize(1);
        assertThat(vertexModels.get(0).id()).isEqualTo("gemini-2.0-flash-001");
        assertThat(vertexModels.get(0).qualifiedName()).isEqualTo("vertex_ai/gemini-2.0-flash-001");
        assertThat(vertexModels.get(0).structuredOutput()).isTrue();
    }

    @Test
    void loadWithLocalOverride(@TempDir Path tempDir) throws IOException {
        var overridePath = tempDir.resolve("override.yaml");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("llm-models-override.yaml")) {
            Files.copy(is, overridePath);
        }

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(overridePath.toString());

        var service = new LlmModelRegistryService(config);
        var registry = service.getRegistry();

        // Override replaces gpt-4o (structuredOutput flipped to false) and adds custom-openai-model
        var openai = registry.get("openai");
        assertThat(openai).hasSize(4);
        assertThat(openai.stream().filter(m -> m.id().equals("gpt-4o")).findFirst().get().structuredOutput())
                .isFalse();
        assertThat(openai.stream().anyMatch(m -> m.id().equals("custom-openai-model"))).isTrue();

        // Gemini added by override (not in defaults)
        assertThat(registry.get("gemini")).hasSize(1);
        assertThat(registry.get("gemini").get(0).id()).isEqualTo("gemini-custom");

        // Anthropic unchanged from defaults
        assertThat(registry.get("anthropic")).hasSize(1);
    }

    @Test
    void loadWithMissingOverrideFileIgnored(@TempDir Path tempDir) {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(tempDir.resolve("nonexistent.yaml").toString());

        var service = new LlmModelRegistryService(config);

        assertThat(service.getRegistry()).containsKeys("openai", "anthropic", "vertex-ai");
    }

    @Test
    void malformedOverrideFileFallsBackToDefaults(@TempDir Path tempDir) throws IOException {
        var overridePath = tempDir.resolve("malformed.yaml");
        Files.writeString(overridePath, "not: valid: yaml: [[[");

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(overridePath.toString());

        // Service must not throw: bad override should log and fall back to defaults
        var service = new LlmModelRegistryService(config);

        assertThat(service.getRegistry()).containsKeys("openai", "anthropic", "vertex-ai");
        assertThat(service.getRegistry().get("openai")).hasSize(3);
    }

    @Test
    void loadDefaultResourceFromMainResources() {
        var config = new LlmModelRegistryConfig();

        var service = new LlmModelRegistryService(config);
        var registry = service.getRegistry();

        assertThat(registry).containsKeys("openai", "anthropic", "gemini", "vertex-ai", "openrouter");
        assertThat(registry.get("openai")).isNotEmpty();
        assertThat(registry.get("openrouter")).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "gpt-5-mini, true",
            "gpt-5.4, true",
            "gpt-5-codex, true",
            "o3, true",
            "gpt-5-chat-latest, false",
            "gpt-4o, false"
    })
    void defaultResourceFlagsOpenAiReasoningModels(String model, boolean reasoning) {
        var service = new LlmModelRegistryService(new LlmModelRegistryConfig());

        var result = service.findModel(model);

        assertThat(result).isPresent();
        assertThat(result.get().provider()).isEqualTo(LlmProvider.OPEN_AI);
        assertThat(result.get().model().reasoning()).isEqualTo(reasoning);
    }

    @ParameterizedTest
    @CsvSource({
            "claude-mythos-preview, ANTHROPIC, claude-mythos-preview,",
            "claude-3-7-sonnet-20250219, ANTHROPIC, claude-3-7-sonnet-20250219,",
            "claude-opus-4-20250514, ANTHROPIC, claude-opus-4-20250514,",
            "claude-opus-4-1-20250805, ANTHROPIC, claude-opus-4-1-20250805,",
            "claude-sonnet-4-20250514, ANTHROPIC, claude-sonnet-4-20250514,",
            "gemini-flash-latest-high-res-exp, GEMINI, gemini-flash-latest-high-res-exp,",
            "gemini-3-pro-preview, GEMINI, gemini-3-pro-preview,",
            "vertex_ai/gemini-robotics-er-2, VERTEX_AI, gemini-robotics-er-2, vertex_ai/gemini-robotics-er-2",
            "vertex_ai/gemini-2.0-flash-001, VERTEX_AI, gemini-2.0-flash-001, vertex_ai/gemini-2.0-flash-001",
            "vertex_ai/gemini-2.0-flash-lite-001, VERTEX_AI, gemini-2.0-flash-lite-001, vertex_ai/gemini-2.0-flash-lite-001",
            "vertex_ai/gemini-3-pro-preview, VERTEX_AI, gemini-3-pro-preview, vertex_ai/gemini-3-pro-preview",
            "vertex_ai/gemini-3.1-flash-lite-preview, VERTEX_AI, gemini-3.1-flash-lite-preview, vertex_ai/gemini-3.1-flash-lite-preview"
    })
    void defaultResourceHidesModelsThatCannotRunButStillResolvesThem(String model, LlmProvider provider, String id,
            String qualifiedName) {
        var service = new LlmModelRegistryService(new LlmModelRegistryConfig());

        var result = service.findModel(model);

        var expected = new LlmModelRegistryService.ModelLookupResult(provider,
                LlmModelDefinition.builder().id(id).qualifiedName(qualifiedName).build());
        // The capability flags come from the sync and have their own tests; this one is about hiding the model.
        assertThat(result).get()
                .usingRecursiveComparison()
                .ignoringFields("model.structuredOutput", "model.reasoning")
                .isEqualTo(expected);
    }

    @Test
    void missingClasspathResourceThrows() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("nonexistent.yaml");

        assertThatThrownBy(() -> new LlmModelRegistryService(config))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("nonexistent.yaml");
    }

    @Test
    void mergeOverridesExistingAndAddsNew() {
        var defaults = Map.of(
                "openai", List.of(
                        ModelEntry.builder().id("gpt-4o").structuredOutput(true).build(),
                        ModelEntry.builder().id("gpt-3.5-turbo").build()));

        var overrides = Map.of(
                "openai", List.of(
                        ModelEntry.builder().id("gpt-4o").structuredOutput(false).build(),
                        ModelEntry.builder().id("new-model").structuredOutput(true).build()),
                "anthropic", List.of(
                        ModelEntry.builder().id("claude-4").build()));

        var merged = LlmModelRegistryService.merge(defaults, overrides);

        assertThat(merged.get("openai")).hasSize(3);
        assertThat(merged.get("openai").get(0).id()).isEqualTo("gpt-4o");
        assertThat(merged.get("openai").get(0).structuredOutput()).isFalse();
        assertThat(merged.get("openai").get(1).id()).isEqualTo("gpt-3.5-turbo");
        assertThat(merged.get("openai").get(2).id()).isEqualTo("new-model");

        assertThat(merged.get("anthropic")).hasSize(1);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "o3     | label: O3 from override | true",
            "o3     | reasoning: false        | false",
            "gpt-4o | reasoning: true         | true",
            "gpt-4o | label: GPT 4o           | false"
    })
    void localOverrideChangesReasoningFlagOnlyWhenItSetsIt(String model, String overrideField,
            boolean expectedReasoning, @TempDir Path tempDir) throws IOException {
        var overridePath = tempDir.resolve("override.yaml");
        Files.writeString(overridePath, """
                openai:
                  - id: "%s"
                    %s
                """.formatted(model, overrideField));

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(overridePath.toString());

        var service = new LlmModelRegistryService(config);

        assertThat(service.findModel(model)).get()
                .extracting(result -> result.model().reasoning())
                .isEqualTo(expectedReasoning);
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', value = {
            "gpt-4o        | label: GPT 4o from override | true",
            "gpt-4o        | structuredOutput: false     | false",
            "gpt-3.5-turbo | structuredOutput: true      | true",
            "gpt-3.5-turbo | label: GPT 3.5              | false"
    })
    void localOverrideChangesStructuredOutputFlagOnlyWhenItSetsIt(String model, String overrideField,
            boolean expectedStructuredOutput, @TempDir Path tempDir) throws IOException {
        var overridePath = tempDir.resolve("override.yaml");
        Files.writeString(overridePath, """
                openai:
                  - id: "%s"
                    %s
                """.formatted(model, overrideField));

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(overridePath.toString());

        var service = new LlmModelRegistryService(config);

        assertThat(service.findModel(model)).get()
                .extracting(result -> result.model().structuredOutput())
                .isEqualTo(expectedStructuredOutput);
    }

    @Test
    void mergeKeepsTheLabelAndQualifiedNameAnOverrideLeavesOut() {
        var defaults = Map.of(
                "vertex-ai", List.of(ModelEntry.builder()
                        .id("gemini-2.0-flash-001")
                        .qualifiedName("vertex_ai/gemini-2.0-flash-001")
                        .label("Gemini 2.0 Flash")
                        .build()));

        var overrides = Map.of(
                "vertex-ai", List.of(ModelEntry.builder()
                        .id("gemini-2.0-flash-001")
                        .structuredOutput(true)
                        .build()));

        var merged = LlmModelRegistryService.merge(defaults, overrides);

        assertThat(merged.get("vertex-ai")).containsExactly(ModelEntry.builder()
                .id("gemini-2.0-flash-001")
                .qualifiedName("vertex_ai/gemini-2.0-flash-001")
                .label("Gemini 2.0 Flash")
                .structuredOutput(true)
                .build());
    }

    @Test
    void registryKeepsTheClasspathProviderOrderAndAppendsOverrideProviders(@TempDir Path tempDir)
            throws IOException {
        var overridePath = tempDir.resolve("override.yaml");
        try (InputStream is = getClass().getClassLoader().getResourceAsStream("llm-models-override.yaml")) {
            Files.copy(is, overridePath);
        }

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(overridePath.toString());

        var service = new LlmModelRegistryService(config);

        assertThat(service.getRegistry().keySet()).containsExactly("openai", "anthropic", "vertex-ai", "gemini");
    }

    @Test
    void registryJsonHasBothFlagsWhenTheYamlLeavesThemOut() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");

        var service = new LlmModelRegistryService(config);

        assertThat(JsonUtils.valueToTree(service.getRegistry().get("openai").get(1)))
                .isEqualTo(JsonUtils.getJsonNodeFromString("""
                        {"id": "gpt-3.5-turbo", "structured_output": false, "reasoning": false}"""));
    }

    @Test
    void registryIsImmutable() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");

        var service = new LlmModelRegistryService(config);
        var registry = service.getRegistry();

        assertThatThrownBy(() -> registry.put("new-provider", List.of()))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void findModelByQualifiedName() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        var service = new LlmModelRegistryService(config);

        var result = service.findModel("vertex_ai/gemini-2.0-flash-001");

        assertThat(result).isPresent();
        assertThat(result.get().provider()).isEqualTo(LlmProvider.VERTEX_AI);
        assertThat(result.get().model().id()).isEqualTo("gemini-2.0-flash-001");
        assertThat(result.get().model().structuredOutput()).isTrue();
    }

    @Test
    void findModelByIdSkipsModelsWithQualifiedName() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        var service = new LlmModelRegistryService(config);

        // "gemini-2.0-flash-001" is the id of a vertex-ai model that has a qualifiedName,
        // so bare id lookup should NOT match it
        var result = service.findModel("gemini-2.0-flash-001");

        assertThat(result).isEmpty();
    }

    @Test
    void findModelById() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        var service = new LlmModelRegistryService(config);

        var result = service.findModel("gpt-4o");

        assertThat(result).isPresent();
        assertThat(result.get().provider()).isEqualTo(LlmProvider.OPEN_AI);
        assertThat(result.get().model().structuredOutput()).isTrue();
    }

    @Test
    void findModelReturnsEmptyForUnknownModel() {
        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        var service = new LlmModelRegistryService(config);

        assertThat(service.findModel("nonexistent-model")).isEmpty();
    }

    @Test
    void reloadUpdatesRegistry(@TempDir Path tempDir) throws IOException {
        var overridePath = tempDir.resolve("override.yaml");
        Files.writeString(overridePath, "openai:\n  - id: \"first-model\"\n");

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setLocalOverridePath(overridePath.toString());

        var service = new LlmModelRegistryService(config);
        assertThat(service.getRegistry().get("openai").stream().anyMatch(m -> m.id().equals("first-model"))).isTrue();

        Files.writeString(overridePath, "openai:\n  - id: \"second-model\"\n");
        service.reload();

        assertThat(service.getRegistry().get("openai").stream().anyMatch(m -> m.id().equals("second-model"))).isTrue();
    }

    @Test
    void remoteFetchMergesOnTopOfClasspathDefaults() {
        var remoteYaml = "openai:\n  - id: \"remote-new-model\"\n    structuredOutput: true\n";
        var client = mockHttpClient(200, remoteYaml);

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setRemoteEnabled(true);
        config.setRemoteUrl("https://cdn.example.com/models.yaml");

        var service = new LlmModelRegistryService(config, client);
        var openai = service.getRegistry().get("openai");

        // Classpath default models + remote model merged
        assertThat(openai.stream().anyMatch(m -> m.id().equals("gpt-4o"))).isTrue();
        assertThat(openai.stream().anyMatch(m -> m.id().equals("remote-new-model"))).isTrue();
    }

    @Test
    void remoteFetchKeepsClasspathReasoningFlagWhenRemoteEntryLacksIt() {
        var remoteYaml = """
                openai:
                  - id: "o3"
                    label: "O3 from remote"
                  - id: "gpt-4o"
                    structuredOutput: true
                    reasoning: true
                """;
        var client = mockHttpClient(200, remoteYaml);

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setRemoteEnabled(true);
        config.setRemoteUrl("https://cdn.example.com/models.yaml");

        var service = new LlmModelRegistryService(config, client);

        assertThat(service.findModel("o3")).get()
                .extracting(LlmModelRegistryService.ModelLookupResult::model)
                .isEqualTo(LlmModelDefinition.builder().id("o3").label("O3 from remote").structuredOutput(true)
                        .reasoning(true).build());
        assertThat(service.findModel("gpt-4o")).get()
                .extracting(result -> result.model().reasoning())
                .isEqualTo(true);
    }

    @Test
    void remoteFetchNon200FallsBackToClasspathDefaults() {
        var client = mockHttpClient(503, "Service Unavailable");

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setRemoteEnabled(true);
        config.setRemoteUrl("https://cdn.example.com/models.yaml");

        var service = new LlmModelRegistryService(config, client);

        // Should still have classpath defaults
        assertThat(service.getRegistry()).containsKeys("openai", "anthropic", "vertex-ai");
        assertThat(service.getRegistry().get("openai")).hasSize(3);
    }

    @Test
    void failedRemoteFetchKeepsTheLastGoodRemoteCopy() {
        var client = mockHttpClient(
                mockResponse(200, "openai:\n  - id: \"remote-new-model\"\n"),
                mockResponse(503, "Service Unavailable"));

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setRemoteEnabled(true);
        config.setRemoteUrl("https://cdn.example.com/models.yaml");

        var service = new LlmModelRegistryService(config, client);
        service.reload();

        verify(client, times(2)).target(any(java.net.URI.class));
        assertThat(service.findModel("remote-new-model")).isPresent();
        assertThat(service.getRegistry().get("openai")).hasSize(4);
    }

    @Test
    void remoteFetchMalformedYamlFallsBackToClasspathDefaults() {
        var client = mockHttpClient(200, "not: valid: yaml: [[[");

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setRemoteEnabled(true);
        config.setRemoteUrl("https://cdn.example.com/models.yaml");

        var service = new LlmModelRegistryService(config, client);

        assertThat(service.getRegistry()).containsKeys("openai", "anthropic", "vertex-ai");
    }

    @Test
    void remoteFetchDisabledDoesNotCallRemote() {
        var client = mockHttpClient(200, "openai:\n  - id: \"should-not-appear\"\n");

        var config = new LlmModelRegistryConfig();
        config.setDefaultResource("llm-models-test.yaml");
        config.setRemoteEnabled(false);
        config.setRemoteUrl("https://cdn.example.com/models.yaml");

        var service = new LlmModelRegistryService(config, client);

        assertThat(service.getRegistry()).containsKeys("openai", "anthropic", "vertex-ai");
        assertThat(service.getRegistry().get("openai")).hasSize(3);
        org.mockito.Mockito.verifyNoInteractions(client);
    }

    private static Client mockHttpClient(int statusCode, String responseBody) {
        return mockHttpClient(mockResponse(statusCode, responseBody));
    }

    private static Client mockHttpClient(Response first, Response... next) {
        var client = mock(Client.class);
        var webTarget = mock(WebTarget.class);
        var builder = mock(Invocation.Builder.class);

        when(client.target(any(java.net.URI.class))).thenReturn(webTarget);
        when(webTarget.request()).thenReturn(builder);
        when(builder.get()).thenReturn(first, next);

        return client;
    }

    private static Response mockResponse(int statusCode, String responseBody) {
        var response = mock(Response.class);
        when(response.getStatus()).thenReturn(statusCode);
        when(response.readEntity(InputStream.class))
                .thenReturn(new ByteArrayInputStream(responseBody.getBytes(StandardCharsets.UTF_8)));
        return response;
    }
}
