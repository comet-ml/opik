package com.comet.opik.infrastructure.llm.antropic;

import dev.langchain4j.model.ModelProvider;
import dev.langchain4j.model.anthropic.OpikAnthropicCacheBridge;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.anthropic.internal.mapper.AnthropicMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ChatRequestParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.ChatResponseMetadata;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link ChatModel} for the Anthropic LLM-as-judge path that adds prompt caching langchain4j's
 * high-level API cannot: a rolling ephemeral cache breakpoint on the whole conversation each agentic
 * round, so the re-sent tool-result/assistant tail is cached (and re-read ~90% cheaper) instead of
 * re-billed every round. The request is built via {@link OpikAnthropicCacheBridge} (langchain4j's own
 * request builder + the breakpoint) and sent through the raw {@link AnthropicClient}.
 *
 * <p>Scope + safety: only multi-turn tool conversations take the cached path — single-call requests
 * (span evals, the inline path, the first agentic turn) have nothing to re-read and are delegated to
 * the stock {@code delegate} model unchanged. Any failure in the cached path (e.g. an Anthropic
 * internal-API change) is caught and falls back to {@code delegate}, so caching degrades silently
 * rather than breaking scoring.
 */
@Slf4j
class OpikCachingAnthropicChatModel implements ChatModel {

    private final ChatModel delegate;
    private final AnthropicClient client;

    OpikCachingAnthropicChatModel(@NonNull ChatModel delegate, @NonNull AnthropicClient client) {
        this.delegate = delegate;
        this.client = client;
    }

    @Override
    public ChatResponse chat(@NonNull ChatRequest chatRequest) {
        if (!isToolCallingRequest(chatRequest)) {
            return delegate.chat(chatRequest);
        }
        try {
            // Merge the model's default parameters (maxTokens, etc.) under the request's own, exactly
            // as ChatModel#chat would before doChat — createAnthropicRequest reads maxOutputTokens and
            // NPEs if it's unset, so we cannot hand it the raw request.
            var defaults = delegate.defaultRequestParameters();
            var parameters = defaults != null
                    ? defaults.overrideWith(chatRequest.parameters())
                    : chatRequest.parameters();
            var effectiveRequest = chatRequest.toBuilder().parameters(parameters).build();
            // System + tools are already covered by the rolling tail breakpoint (it caches the whole
            // prefix before it), so only the tail is marked here; a stable per-rule static breakpoint
            // is still added upstream by OnlineScoringEngine#markPromptForCaching on the context message.
            var request = OpikAnthropicCacheBridge.cachedRequest(effectiveRequest, false, false, true);
            var response = client.createMessage(request);
            return ChatResponse.builder()
                    .aiMessage(AnthropicMapper.toAiMessage(response.content, false, false))
                    .metadata(ChatResponseMetadata.builder()
                            .id(response.id)
                            .modelName(response.model)
                            .tokenUsage(AnthropicMapper.toTokenUsage(response.usage))
                            .finishReason(AnthropicMapper.toFinishReason(response.stopReason))
                            .build())
                    .build();
        } catch (Exception exception) {
            log.warn("Cached Anthropic request failed; falling back to the standard model", exception);
            return delegate.chat(chatRequest);
        }
    }

    // The agentic tool-calling loop: every round that carries tool specs goes through the cached path,
    // including the first (single-message) call, so all rounds build the request identically and each
    // round re-reads the prefix the previous one cached. Requests without tools — inline/span scoring
    // and the tools-stripped wrap-up — have no re-read to gain and are delegated to the stock model.
    private static boolean isToolCallingRequest(ChatRequest chatRequest) {
        return chatRequest.toolSpecifications() != null && !chatRequest.toolSpecifications().isEmpty();
    }

    @Override
    public ChatRequestParameters defaultRequestParameters() {
        return delegate.defaultRequestParameters();
    }

    @Override
    public ModelProvider provider() {
        return delegate.provider();
    }
}
