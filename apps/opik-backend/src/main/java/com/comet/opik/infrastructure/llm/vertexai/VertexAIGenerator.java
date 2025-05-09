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
import dev.langchain4j.model.vertexai.VertexAiGeminiChatModel;
import lombok.NonNull;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

public class VertexAIGenerator implements LlmProviderClientGenerator<ChatLanguageModel> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public VertexAIGenerator(@NonNull LlmProviderClientConfig llmProviderClientConfig) {
        this.llmProviderClientConfig = llmProviderClientConfig;
    }
    public ChatLanguageModel newVertexAIClient(@NonNull String apiKey, @NonNull ChatCompletionRequest request) {

        try {
            ServiceAccountCredentials credentials = ServiceAccountCredentials.fromStream(new ByteArrayInputStream(
                apiKey.getBytes(StandardCharsets.UTF_8)
            ));

            VertexAI vertexAI = new VertexAI.Builder()
                    .setProjectId(credentials.getProjectId())
                    .setLocation("us-central1") // Set the location as needed
                    .setCredentials(credentials.createScoped("https://www.googleapis.com/auth/cloud-platform"))
                    .build();

            var generationConfig = GenerationConfig.newBuilder();

            Optional.ofNullable(request.temperature())
                    .map(Double::floatValue)
                    .ifPresent(generationConfig::setTemperature);

            Optional.ofNullable(request.maxTokens())
                    .ifPresent(generationConfig::setMaxOutputTokens);


            GenerativeModel generativeModel = new GenerativeModel(request.model(), vertexAI)
                    .withGenerationConfig(generationConfig.build());

            return new VertexAiGeminiChatModel(generativeModel, generationConfig.build());

        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create GoogleCredentials", e);
        }
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
