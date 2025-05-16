package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.google.common.base.Preconditions;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;

import java.util.Objects;
import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;
import static dev.langchain4j.model.googleai.GoogleAiGeminiChatModel.GoogleAiGeminiChatModelBuilder;

@RequiredArgsConstructor
public class GeminiClientGenerator implements LlmProviderClientGenerator<GoogleAiGeminiChatModel> {

    private static final int MAX_RETRIES = 1;
    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public GoogleAiGeminiChatModel newGeminiClient(@NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return GeminiChatModelMapper.INSTANCE.toGeminiChatModel(apiKey, request,
                llmProviderClientConfig.getCallTimeout().toJavaDuration(), MAX_RETRIES);
    }

    public GoogleAiGeminiStreamingChatModel newGeminiStreamingClient(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return GeminiChatModelMapper.INSTANCE.toGeminiStreamingChatModel(apiKey, request,
                llmProviderClientConfig.getCallTimeout().toJavaDuration(), MAX_RETRIES);
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
                .apiKey(config.apiKey());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> modelBuilder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(modelParameters.temperature()).ifPresent(modelBuilder::temperature);

        return modelBuilder.build();
    }
}
