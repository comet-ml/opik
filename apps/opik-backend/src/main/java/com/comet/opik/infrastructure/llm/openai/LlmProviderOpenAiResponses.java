package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import io.dropwizard.jersey.errors.ErrorMessage;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * Proxy-path LlmProviderService backed by OpenAI's Responses API via
 * {@link dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatModel}.
 * <br/>
 * The inbound/outbound contract is OpenAI Chat-Completions wire format (same as
 * {@link LlmProviderOpenAi}); translation to/from langchain4j's provider-agnostic
 * ChatRequest/ChatResponse is delegated to {@link LlmProviderOpenAiResponsesMapper}.
 * <br/>
 * Streaming is not yet implemented and throws UnsupportedOperationException — to be added in a
 * follow-up using {@link dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel}.
 */
@RequiredArgsConstructor
@Slf4j
public class LlmProviderOpenAiResponses implements LlmProviderService {

    private final @NonNull ChatModel chatModel;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var chatRequest = LlmProviderOpenAiResponsesMapper.toChatRequest(request);
        var chatResponse = chatModel.chat(chatRequest);
        return LlmProviderOpenAiResponsesMapper.toChatCompletionResponse(chatResponse, request);
    }

    @Override
    public void generateStream(
            @NonNull ChatCompletionRequest request,
            @NonNull String workspaceId,
            @NonNull Consumer<ChatCompletionResponse> handleMessage,
            @NonNull Runnable handleClose,
            @NonNull Consumer<Throwable> handleError) {
        throw new UnsupportedOperationException(
                "Streaming via OpenAI Responses API is not yet implemented; "
                        + "use pipeline_mode=CHAT_COMPLETIONS_API for streaming or wait for the streaming follow-up.");
    }

    @Override
    public void validateRequest(@NonNull ChatCompletionRequest request) {
        // No additional validation beyond what the Responses-API ChatModel enforces.
    }

    @Override
    public Optional<ErrorMessage> getLlmProviderError(@NonNull Throwable throwable) {
        return LlmProviderLangChainMapper.INSTANCE.getErrorObject(throwable, log);
    }
}
