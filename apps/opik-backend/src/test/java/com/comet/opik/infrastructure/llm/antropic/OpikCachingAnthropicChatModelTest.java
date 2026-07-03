package com.comet.opik.infrastructure.llm.antropic;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.anthropic.OpikAnthropicCacheBridge;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import dev.langchain4j.model.chat.response.ChatResponse;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class OpikCachingAnthropicChatModelTest {

    private static final ToolSpecification READ_TOOL = ToolSpecification.builder()
            .name("read").parameters(JsonObjectSchema.builder().build()).build();

    private static ChatRequest agenticRequest() {
        var toolReq = ToolExecutionRequest.builder().id("t").name("read").arguments("{}").build();
        // A mid-loop transcript: user context, assistant tool_use, tool result. The last message is a
        // tool result — exactly the type langchain4j's high-level API refuses to cache.
        return ChatRequest.builder()
                .messages(List.of(
                        UserMessage.from("evaluate this trace"),
                        AiMessage.from(List.of(toolReq)),
                        ToolExecutionResultMessage.from(toolReq, "large fetched trace content")))
                // maxOutputTokens mirrors the merged request the model hands the bridge (Anthropic
                // requires max_tokens); toolSpecifications go through the same parameters object.
                .parameters(ChatRequestParameters.builder()
                        .toolSpecifications(READ_TOOL)
                        .maxOutputTokens(1024)
                        .build())
                .build();
    }

    // --- The bridge: verified against langchain4j's real request builder (pure mapping, no network) ---

    @Test
    void bridgePlacesRollingBreakpointOnLastContentOfLastMessage() {
        var request = OpikAnthropicCacheBridge.cachedRequest(agenticRequest(), false, false, true);

        var lastMessage = request.messages.get(request.messages.size() - 1);
        var lastContent = lastMessage.content.get(lastMessage.content.size() - 1);
        // The tool-result block now carries a cache breakpoint -> Anthropic caches the whole prefix.
        assertThat(lastContent.cacheControl).isNotNull();
    }

    @Test
    void bridgeLeavesTailUnmarkedWhenNotRequested() {
        var request = OpikAnthropicCacheBridge.cachedRequest(agenticRequest(), false, false, false);

        var lastMessage = request.messages.get(request.messages.size() - 1);
        var lastContent = lastMessage.content.get(lastMessage.content.size() - 1);
        assertThat(lastContent.cacheControl).isNull();
    }

    // --- The model: single-call requests bypass the cached path and go to the stock model ---

    @Test
    void delegatesSingleCallRequestsToTheStockModel() {
        ChatModel delegate = mock(ChatModel.class);
        AnthropicClient client = mock(AnthropicClient.class);
        var expected = ChatResponse.builder().aiMessage(AiMessage.from("ok")).build();
        when(delegate.chat(any(ChatRequest.class))).thenReturn(expected);

        var model = new OpikCachingAnthropicChatModel(delegate, client);
        // No tools, single message -> not an agentic multi-turn conversation.
        var single = ChatRequest.builder().messages(List.of(UserMessage.from("score this"))).build();

        var response = model.chat(single);

        assertThat(response).isSameAs(expected);
        verify(delegate).chat(single);
        verifyNoInteractions(client);
    }

    @Test
    void fallsBackToStockModelWhenTheCachedPathFails() {
        ChatModel delegate = mock(ChatModel.class);
        AnthropicClient client = mock(AnthropicClient.class);
        var expected = ChatResponse.builder().aiMessage(AiMessage.from("fallback")).build();
        when(delegate.chat(any(ChatRequest.class))).thenReturn(expected);
        // The agentic path calls client.createMessage; make it blow up to trigger the fallback.
        when(client.createMessage(any())).thenThrow(new RuntimeException("boom"));

        var model = new OpikCachingAnthropicChatModel(delegate, client);

        var response = model.chat(agenticRequest());

        assertThat(response).isSameAs(expected);
        verify(client).createMessage(any());
        verify(delegate).chat(any(ChatRequest.class));
    }

    @Test
    void nonAgenticRequestNeverTouchesTheClient() {
        ChatModel delegate = mock(ChatModel.class);
        AnthropicClient client = mock(AnthropicClient.class);
        when(delegate.chat(any(ChatRequest.class)))
                .thenReturn(ChatResponse.builder().aiMessage(AiMessage.from("ok")).build());

        var model = new OpikCachingAnthropicChatModel(delegate, client);
        model.chat(ChatRequest.builder().messages(List.of(UserMessage.from("x"))).build());

        verify(client, never()).createMessage(any());
    }
}
