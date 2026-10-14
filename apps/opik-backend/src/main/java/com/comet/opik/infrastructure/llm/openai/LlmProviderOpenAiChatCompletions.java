package com.comet.opik.infrastructure.llm.openai;

import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.function.Consumer;

/**
 * OpenAI's own Chat Completions pipeline. Kept apart from {@link LlmProviderOpenAi}, which OpenRouter
 * also uses, because the effort rule below holds for OpenAI's endpoint only.
 */
@Slf4j
class LlmProviderOpenAiChatCompletions extends LlmProviderOpenAi {

    private static final String RESPONSES_API_ONLY_EFFORT = "max";
    private static final String CHAT_COMPLETIONS_EFFORT_FOR_MAX = "high";

    LlmProviderOpenAiChatCompletions(@NonNull OpenAiClient openAiClient) {
        super(openAiClient);
    }

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        return super.generate(withChatCompletionsEffort(request), workspaceId);
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        super.generateStream(withChatCompletionsEffort(request), workspaceId, handleMessage, handleClose, handleError);
    }

    /**
     * OpenAI accepts {@code max} only on the Responses API and answers 400 for it on Chat Completions,
     * for every model, so it runs at {@code high} here. The frontend already sends a stored max as high on
     * a Chat Completions key, so playground and test-suite runs never depend on this: it is for callers
     * that cannot see the key's mode, such as direct calls to the chat completions or experiment execute
     * endpoints.
     */
    static ChatCompletionRequest withChatCompletionsEffort(@NonNull ChatCompletionRequest request) {
        if (!RESPONSES_API_ONLY_EFFORT.equals(request.reasoningEffort())) {
            return request;
        }
        log.debug("Sending reasoning_effort '{}' as '{}' for model '{}' on OpenAI Chat Completions",
                RESPONSES_API_ONLY_EFFORT, CHAT_COMPLETIONS_EFFORT_FOR_MAX, request.model());
        return ChatCompletionRequest.builder()
                .from(request)
                .reasoningEffort(CHAT_COMPLETIONS_EFFORT_FOR_MAX)
                .build();
    }
}
