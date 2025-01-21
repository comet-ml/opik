package com.comet.opik.infrastructure.llm.gemini;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
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
        return LlmProviderGeminiMapper.INSTANCE.toGeminiChatModel(apiKey, request,
                llmProviderClientConfig.getCallTimeout().toJavaDuration(), MAX_RETRIES);
    }

    public GoogleAiGeminiStreamingChatModel newGeminiStreamingClient(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return LlmProviderGeminiMapper.INSTANCE.toGeminiStreamingChatModel(apiKey, request,
                llmProviderClientConfig.getCallTimeout().toJavaDuration(), MAX_RETRIES);
    }

    @Override
    public GoogleAiGeminiChatModel generate(String apiKey, Object... args) {
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(args[0],
                "ChatCompletionRequest is required");
        return newGeminiClient(apiKey, request);
    }

    @Override
    public ChatLanguageModel generateChat(String apiKey, LlmAsJudgeModelParameters modelParameters) {
        GoogleAiGeminiChatModelBuilder modelBuilder = GoogleAiGeminiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(apiKey);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> modelBuilder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(modelParameters.temperature()).ifPresent(modelBuilder::temperature);

        return modelBuilder.build();
    }
}
