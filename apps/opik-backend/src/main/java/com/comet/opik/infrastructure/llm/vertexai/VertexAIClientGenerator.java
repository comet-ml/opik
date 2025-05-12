package com.comet.opik.infrastructure.llm.vertexai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.vertexai.VertexAI;
import com.google.cloud.vertexai.api.GenerationConfig;
import com.google.cloud.vertexai.generativeai.GenerativeModel;
import com.google.common.base.Preconditions;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.chat.StreamingChatLanguageModel;
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;
import dev.langchain4j.model.vertexai.VertexAiGeminiStreamingChatModel;
import jakarta.ws.rs.InternalServerErrorException;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jetbrains.annotations.NotNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

@RequiredArgsConstructor
@Slf4j
public class VertexAIClientGenerator implements LlmProviderClientGenerator<ChatLanguageModel> {

    private final @NonNull LlmProviderClientConfig clientConfig;

    private ChatLanguageModel newVertexAIClient(@NonNull String apiKey, @NonNull ChatCompletionRequest request) {

        try {
            VertexAI vertexAI = getVertexAI(apiKey);

            GenerationConfig generationConfig = getGenerationConfig(request);

            GenerativeModel generativeModel = new GenerativeModel(request.model(), vertexAI)
                    .withGenerationConfig(generationConfig);

            return new VertexAiGeminiChatModel(generativeModel, generationConfig);

        } catch (IOException e) {
            throw failWithError(e);
        }
    }

    public StreamingChatLanguageModel newVertexAIStreamingClient(@NonNull String apiKey,
            @NonNull ChatCompletionRequest request) {

        try {
            VertexAI vertexAI = getVertexAI(apiKey);

            GenerationConfig generationConfig = getGenerationConfig(request);

            GenerativeModel generativeModel = new GenerativeModel(request.model(), vertexAI)
                    .withGenerationConfig(generationConfig);

            return new VertexAiGeminiStreamingChatModel(generativeModel, generationConfig);

        } catch (IOException e) {
            throw failWithError(e);
        }
    }

    private InternalServerErrorException failWithError(IOException e) {
        return new InternalServerErrorException("Failed to create GoogleCredentials", e);
    }

    private GenerationConfig getGenerationConfig(@NotNull ChatCompletionRequest request) {
        var generationConfig = GenerationConfig.newBuilder();

        Optional.ofNullable(request.temperature())
                .map(Double::floatValue)
                .ifPresent(generationConfig::setTemperature);

        Optional.ofNullable(request.maxTokens())
                .ifPresent(generationConfig::setMaxOutputTokens);

        return generationConfig.build();
    }

    private VertexAI getVertexAI(@NotNull String apiKey) throws IOException {
        var credentials = ServiceAccountCredentials.fromStream(
                new ByteArrayInputStream(apiKey.getBytes(StandardCharsets.UTF_8)));

        return new VertexAI.Builder()
                .setProjectId(credentials.getProjectId())
                .setLocation("us-central1") // Set the location as needed
                .setCredentials(credentials.createScoped(clientConfig.getVertexAIClient().scope()))
                .build();
    }

    @Override
    public ChatLanguageModel generate(String apiKey, Object... params) {
        Preconditions.checkArgument(params.length >= 1, "Expected at least 1 parameter, got " + params.length);
        ChatCompletionRequest request = (ChatCompletionRequest) Objects.requireNonNull(params[0],
                "ChatCompletionRequest is required");

        if (request.model().contains("gemini")) {
            return newVertexAIClient(apiKey, request);
        }

        throw new IllegalArgumentException("Unsupported model: " + request.model());
    }

    @Override
    public ChatLanguageModel generateChat(String apiKey, LlmAsJudgeModelParameters modelParameters) {
        return newVertexAIClient(apiKey, ChatCompletionRequest.builder()
                .model(modelParameters.name())
                .temperature(modelParameters.temperature())
                .build());
    }
}
