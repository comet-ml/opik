package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderLangChainMapper;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
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
 * {@link dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatModel} (blocking) and
 * {@link dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel} (streaming).
 * <br/>
 * The inbound/outbound contract is OpenAI Chat-Completions wire format (same as
 * {@link LlmProviderOpenAi}); translation to/from langchain4j's provider-agnostic
 * ChatRequest/ChatResponse is delegated to {@link LlmProviderOpenAiResponsesMapper}.
 * <br/>
 * ChatModel instances are built per request rather than cached on the service, because the
 * per-request {@code response_format.json_schema.strict} flag has to be set at langchain4j model
 * build time ({@code OpenAiOfficialResponsesChatModel.Builder.strictJsonSchema(...)}); no per-call
 * slot exists. The factory call is the same cost the existing CC proxy already pays through
 * {@code getService} per request, just relocated.
 */
@RequiredArgsConstructor
@Slf4j
public class LlmProviderOpenAiResponses implements LlmProviderService {

    private final @NonNull OpenAIClientGenerator clientGenerator;
    private final @NonNull LlmProviderClientApiConfig config;

    @Override
    public ChatCompletionResponse generate(@NonNull ChatCompletionRequest request, @NonNull String workspaceId) {
        var strictJsonSchema = LlmProviderOpenAiResponsesMapper.extractRequestedStrictJsonSchema(request);
        var chatModel = clientGenerator.newResponsesApiChatModel(config, strictJsonSchema);
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
        var strictJsonSchema = LlmProviderOpenAiResponsesMapper.extractRequestedStrictJsonSchema(request);
        var streamingChatModel = clientGenerator.newResponsesApiStreamingChatModel(config, strictJsonSchema);
        var chatRequest = LlmProviderOpenAiResponsesMapper.toChatRequest(request);
        streamingChatModel.chat(chatRequest, new StreamingChatResponseHandler() {
            @Override
            public void onPartialResponse(String partial) {
                handleMessage.accept(LlmProviderOpenAiResponsesMapper.toPartialChunk(partial, request));
            }

            @Override
            public void onCompleteResponse(ChatResponse response) {
                handleMessage.accept(LlmProviderOpenAiResponsesMapper.toFinalChunk(response, request));
                handleClose.run();
            }

            @Override
            public void onError(Throwable error) {
                handleError.accept(error);
            }
        });
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