package com.comet.opik.infrastructure.llm;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.infrastructure.LlmModelRegistryConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    void loadDefaultResourceFromMainResources() {
        var config = new LlmModelRegistryConfig();

        var service = new LlmModelRegistryService(config);
        var registry = service.getRegistry();

        assertThat(registry).containsKeys("openai", "anthropic", "gemini", "vertex-ai", "openrouter");
        assertThat(registry.get("openai")).isNotEmpty();
        assertThat(registry.get("openrouter")).isNotEmpty();
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
                        LlmModelDefinition.builder().id("gpt-4o").structuredOutput(true).build(),
                        LlmModelDefinition.builder().id("gpt-3.5-turbo").build()));

        var overrides = Map.of(
                "openai", List.of(
                        LlmModelDefinition.builder().id("gpt-4o").structuredOutput(false).build(),
                        LlmModelDefinition.builder().id("new-model").structuredOutput(true).build()),
                "anthropic", List.of(
                        LlmModelDefinition.builder().id("claude-4").build()));

        var merged = LlmModelRegistryService.merge(defaults, overrides);

        assertThat(merged.get("openai")).hasSize(3);
        assertThat(merged.get("openai").get(0).id()).isEqualTo("gpt-4o");
        assertThat(merged.get("openai").get(0).structuredOutput()).isFalse();
        assertThat(merged.get("openai").get(1).id()).isEqualTo("gpt-3.5-turbo");
        assertThat(merged.get("openai").get(2).id()).isEqualTo("new-model");

        assertThat(merged.get("anthropic")).hasSize(1);
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
}
