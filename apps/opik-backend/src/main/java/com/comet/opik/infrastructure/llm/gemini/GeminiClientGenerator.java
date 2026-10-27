package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.langchain4j.OpikGeminiChatModel;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.GeminiThinkingParams;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.comet.opik.infrastructure.llm.RemoteMediaInliner;
import com.google.common.base.Preconditions;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static dev.langchain4j.model.googleai.GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder;

@RequiredArgsConstructor
public class GeminiClientGenerator implements LlmProviderClientGenerator<GoogleAiGeminiChatModel> {

    private static final int MAX_RETRIES = 1;
    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;
    private final @NonNull RemoteMediaInliner remoteMediaInliner;

    /** The request's messages for Gemini, with media URLs downloaded here rather than by the SDK. */
    public List<ChatMessage> mapMessages(@NonNull ChatCompletionRequest request) {
        return remoteMediaInliner.inline(GeminiLangChainMapper.INSTANCE.mapMessagesForGemini(request));
    }

    public GoogleAiGeminiChatModel newGeminiClient(@NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return GeminiChatModelMapper.INSTANCE.toGeminiChatModel(apiKey, request,
                llmProviderClientConfig.getCallTimeout().toJavaDuration(), MAX_RETRIES,
                llmProviderClientConfig.getLogRequests(),
                llmProviderClientConfig.getLogResponses());
    }

    public GoogleAiGeminiStreamingChatModel newGeminiStreamingClient(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return GeminiChatModelMapper.INSTANCE.toGeminiStreamingChatModel(apiKey, request,
                llmProviderClientConfig.getCallTimeout().toJavaDuration(), MAX_RETRIES,
                llmProviderClientConfig.getLogRequests(),
                llmProviderClientConfig.getLogResponses());
    }

    @Override
    public GoogleAiGeminiChatModel generate(LlmProviderClientApiConfig config, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got " + params.length);
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(params[0],
                "ChatCompletionRequest is required");
        return newGeminiClient(config.apiKey(), request);
    }

    @Override
    public ChatModel generateChat(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        GoogleAiGeminiChatModelBuilder modelBuilder = GoogleAiGeminiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses())
                // Gemini 3 rejects a tool loop's next round unless each earlier function call carries the thought
                // signature it came with, and the module keeps that signature only when it returns thinking. Thought
                // text still stays out of the answer: it lands in AiMessage.thinking(), not text().
                .returnThinking(true)
                .sendThinking(true);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> modelBuilder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(modelParameters.temperature()).ifPresent(modelBuilder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(modelBuilder::seed);

        GeminiThinkingConfigMapper
                .toThinkingConfig(modelParameters.name(), GeminiThinkingParams.from(modelParameters.customParameters()))
                .ifPresent(modelBuilder::thinkingConfig);

        GoogleAiGeminiChatModel geminiModel = modelBuilder.build();

        // Wrap in OpikGeminiChatModel to convert VideoContent -> ImageContent
        return new OpikGeminiChatModel(geminiModel, remoteMediaInliner);
    }
}
