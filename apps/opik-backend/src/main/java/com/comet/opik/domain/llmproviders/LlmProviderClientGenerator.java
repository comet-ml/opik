package com.comet.opik.domain.llmproviders;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import dev.ai4j.openai4j.OpenAiClient;
import dev.ai4j.openai4j.chat.ChatCompletionRequest;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

@RequiredArgsConstructor
public class LlmProviderClientGenerator {
    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public AnthropicClient newAnthropicClient(@NonNull String apiKey) {
        var anthropicClientBuilder = AnthropicClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::url)
                .ifPresent(url -> {
                    if (StringUtils.isNotEmpty(url)) {
                        anthropicClientBuilder.baseUrl(url);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::version)
                .ifPresent(version -> {
                    if (StringUtils.isNotBlank(version)) {
                        anthropicClientBuilder.version(version);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getLogRequests())
                .ifPresent(anthropicClientBuilder::logRequests);
        Optional.ofNullable(llmProviderClientConfig.getLogResponses())
                .ifPresent(anthropicClientBuilder::logResponses);
        // anthropic client builder only receives one timeout variant
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> anthropicClientBuilder.timeout(callTimeout.toJavaDuration()));
        return anthropicClientBuilder
                .apiKey(apiKey)
                .build();
    }

    public OpenAiClient newOpenAiClient(@NonNull String apiKey) {
        var openAiClientBuilder = OpenAiClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .ifPresent(baseUrl -> {
                    if (StringUtils.isNotBlank(baseUrl)) {
                        openAiClientBuilder.baseUrl(baseUrl);
                    }
                });
        Optional.ofNullable(llmProviderClientConfig.getCallTimeout())
                .ifPresent(callTimeout -> openAiClientBuilder.callTimeout(callTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getWriteTimeout())
                .ifPresent(writeTimeout -> openAiClientBuilder.writeTimeout(writeTimeout.toJavaDuration()));
        return openAiClientBuilder
                .openAiApiKey(apiKey)
                .build();
    }

    // TODO: mapper
    public GoogleAiGeminiChatModel newGeminiClient(@NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return GoogleAiGeminiChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.model())
                .maxOutputTokens(request.maxCompletionTokens())
                .maxRetries(1)
                .stopSequences(request.stop())
                .temperature(request.temperature())
                .topP(request.topP())
                .timeout(llmProviderClientConfig.getCallTimeout().toJavaDuration())
                .build();
    }

    // TODO: mapper
    public GoogleAiGeminiStreamingChatModel newGeminiStreamingClient(
            @NonNull String apiKey, @NonNull ChatCompletionRequest request) {
        return GoogleAiGeminiStreamingChatModel.builder()
                .apiKey(apiKey)
                .modelName(request.model())
                .maxOutputTokens(request.maxCompletionTokens())
                .maxRetries(1)
                .stopSequences(request.stop())
                .temperature(request.temperature())
                .topP(request.topP())
                .timeout(llmProviderClientConfig.getCallTimeout().toJavaDuration())
                .build();
    }
}