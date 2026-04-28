package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.LlmModelDefinition;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.LlmModelRegistryService;
import dev.langchain4j.model.anthropic.internal.api.AnthropicContent;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageResponse;
import dev.langchain4j.model.anthropic.internal.api.AnthropicUsage;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class LlmProviderAnthropicTest {

    private AnthropicCreateMessageResponse stubResponse() {
        var response = new AnthropicCreateMessageResponse();
        response.id = "msg_x";
        var content = new AnthropicContent();
        content.text = "ok";
        response.content = List.of(content);
        var usage = new AnthropicUsage();
        usage.inputTokens = 1;
        usage.outputTokens = 1;
        response.usage = usage;
        response.stopReason = "end_turn";
        return response;
    }

    private ChatCompletionRequest requestFor(String model, Double temperature, Double topP) {
        var builder = ChatCompletionRequest.builder()
                .model(model)
                .addUserMessage("hi")
                .stream(false)
                .maxCompletionTokens(64);
        if (temperature != null) builder.temperature(temperature);
        if (topP != null) builder.topP(topP);
        return builder.build();
    }

    @Test
    void stripsSamplingParamsWhenRegistryFlagIsFalse() {
        var anthropicClient = mock(AnthropicClient.class);
        var registry = mock(LlmModelRegistryService.class);
        when(registry.findModel("claude-opus-4-7")).thenReturn(Optional.of(
                new LlmModelRegistryService.ModelLookupResult(
                        LlmProvider.ANTHROPIC,
                        LlmModelDefinition.builder()
                                .id("claude-opus-4-7")
                                .supportsSamplingParams(false)
                                .build())));
        when(anthropicClient.createMessage(any())).thenReturn(stubResponse());

        var provider = new LlmProviderAnthropic(anthropicClient, registry);
        provider.generate(requestFor("claude-opus-4-7", 0.7, 0.9), "ws");

        var captor = ArgumentCaptor.forClass(AnthropicCreateMessageRequest.class);
        verify(anthropicClient).createMessage(captor.capture());
        var sent = captor.getValue();
        assertThat(sent.temperature).isNull();
        assertThat(sent.topP).isNull();
    }

    @Test
    void leavesSamplingParamsWhenRegistryFlagIsTrue() {
        var anthropicClient = mock(AnthropicClient.class);
        var registry = mock(LlmModelRegistryService.class);
        when(registry.findModel("claude-opus-4-6")).thenReturn(Optional.of(
                new LlmModelRegistryService.ModelLookupResult(
                        LlmProvider.ANTHROPIC,
                        LlmModelDefinition.builder()
                                .id("claude-opus-4-6")
                                .supportsSamplingParams(true)
                                .build())));
        when(anthropicClient.createMessage(any())).thenReturn(stubResponse());

        var provider = new LlmProviderAnthropic(anthropicClient, registry);
        provider.generate(requestFor("claude-opus-4-6", 0.7, null), "ws");

        var captor = ArgumentCaptor.forClass(AnthropicCreateMessageRequest.class);
        verify(anthropicClient).createMessage(captor.capture());
        assertThat(captor.getValue().temperature).isEqualTo(0.7);
    }

    @Test
    void leavesSamplingParamsWhenModelNotInRegistry() {
        var anthropicClient = mock(AnthropicClient.class);
        var registry = mock(LlmModelRegistryService.class);
        when(registry.findModel("custom-claude")).thenReturn(Optional.empty());
        when(anthropicClient.createMessage(any())).thenReturn(stubResponse());

        var provider = new LlmProviderAnthropic(anthropicClient, registry);
        provider.generate(requestFor("custom-claude", 0.5, null), "ws");

        var captor = ArgumentCaptor.forClass(AnthropicCreateMessageRequest.class);
        verify(anthropicClient).createMessage(captor.capture());
        assertThat(captor.getValue().temperature).isEqualTo(0.5);
    }
}
