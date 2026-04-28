package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AnthropicClientGeneratorTest {

    private AnthropicClientGenerator newGenerator(LlmModelRegistryService registry) {
        return new AnthropicClientGenerator(mock(LlmProviderClientConfig.class), registry);
    }

    @Test
    void supportsSamplingParamsReturnsFalseWhenRegistryFlagIsFalse() {
        var registry = mock(LlmModelRegistryService.class);
        when(registry.findModel("claude-opus-4-7")).thenReturn(Optional.of(
                new LlmModelRegistryService.ModelLookupResult(
                        LlmProvider.ANTHROPIC,
                        LlmModelDefinition.builder()
                                .id("claude-opus-4-7")
                                .supportsSamplingParams(false)
                                .build())));

        assertThat(newGenerator(registry).supportsSamplingParams("claude-opus-4-7")).isFalse();
    }

    @Test
    void supportsSamplingParamsReturnsTrueWhenRegistryFlagIsTrue() {
        var registry = mock(LlmModelRegistryService.class);
        when(registry.findModel("claude-opus-4-6")).thenReturn(Optional.of(
                new LlmModelRegistryService.ModelLookupResult(
                        LlmProvider.ANTHROPIC,
                        LlmModelDefinition.builder()
                                .id("claude-opus-4-6")
                                .supportsSamplingParams(true)
                                .build())));

        assertThat(newGenerator(registry).supportsSamplingParams("claude-opus-4-6")).isTrue();
    }

    @Test
    void supportsSamplingParamsReturnsTrueWhenModelNotInRegistry() {
        var registry = mock(LlmModelRegistryService.class);
        when(registry.findModel("custom-claude")).thenReturn(Optional.empty());

        assertThat(newGenerator(registry).supportsSamplingParams("custom-claude")).isTrue();
    }

    @Test
    void supportsSamplingParamsReturnsTrueWhenModelNameIsNull() {
        var registry = mock(LlmModelRegistryService.class);

        assertThat(newGenerator(registry).supportsSamplingParams(null)).isTrue();
    }
}
