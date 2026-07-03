package dev.langchain4j.model.anthropic;

import dev.langchain4j.model.anthropic.internal.api.AnthropicCacheType;
import dev.langchain4j.model.anthropic.internal.api.AnthropicCreateMessageRequest;
import dev.langchain4j.model.chat.request.ChatRequest;

import java.util.List;
import java.util.Set;

/**
 * Lives in langchain4j's {@code dev.langchain4j.model.anthropic} package on purpose: it is the only
 * way to reuse the (package-private) {@link InternalAnthropicHelper#createAnthropicRequest} so the
 * Anthropic wire request — messages, system, tools, tool_choice, max_tokens, sampling params — is
 * built byte-for-byte the way the stock {@code AnthropicChatModel} would build it. On top of that it
 * adds the one thing langchain4j's high-level API refuses to do: a <b>rolling ephemeral cache
 * breakpoint on the last content block of the last message</b>, regardless of message type. Anthropic
 * caches the whole prefix up to that block, so across an agentic tool-call loop each round re-reads
 * the accumulated tool-result/assistant transcript instead of re-billing it.
 *
 * <p>This touches Anthropic-internal langchain4j classes ({@code internal.api.*}) and is therefore
 * pinned to the langchain4j version in the BOM; {@code OpikCachingAnthropicChatModel} wraps every call
 * in a fallback to the stock model, so an internal-API change degrades to "caching off", never a
 * failure.
 */
public final class OpikAnthropicCacheBridge {

    private OpikAnthropicCacheBridge() {
    }

    /**
     * Builds langchain4j's standard Anthropic request for {@code chatRequest} (optionally caching the
     * system prompt and tool definitions) and, when {@code cacheConversationTail} is set, marks the
     * last content block of the last message with an ephemeral cache breakpoint.
     */
    public static AnthropicCreateMessageRequest cachedRequest(ChatRequest chatRequest,
            boolean cacheSystemMessages, boolean cacheTools, boolean cacheConversationTail) {
        AnthropicCreateMessageRequest request = InternalAnthropicHelper.createAnthropicRequest(
                chatRequest,
                null, // thinking — the judge does not use extended thinking
                true, // sendThinking — no-op without thinking
                cacheSystemMessages ? AnthropicCacheType.EPHEMERAL : AnthropicCacheType.NO_CACHE,
                cacheTools ? AnthropicCacheType.EPHEMERAL : AnthropicCacheType.NO_CACHE,
                false, // stream — scoring is synchronous
                null, // toolChoiceName — AUTO/REQUIRED is read from chatRequest's parameters
                null, // disableParallelToolUse
                List.of(), // serverTools
                Set.of(), // toolMetadataKeysToSend
                null, // userId
                null, // customParameters
                null); // strictTools

        if (cacheConversationTail) {
            markLastContentEphemeral(request);
        }
        return request;
    }

    private static void markLastContentEphemeral(AnthropicCreateMessageRequest request) {
        if (request.messages == null || request.messages.isEmpty()) {
            return;
        }
        var lastMessage = request.messages.get(request.messages.size() - 1);
        if (lastMessage.content == null || lastMessage.content.isEmpty()) {
            return;
        }
        lastMessage.content.get(lastMessage.content.size() - 1).cacheControl = AnthropicCacheType.EPHEMERAL
                .cacheControl();
    }
}
