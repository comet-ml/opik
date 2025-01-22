package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.chat.ChatLanguageModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

@RequiredArgsConstructor
public class AnthropicClientGenerator implements LlmProviderClientGenerator<AnthropicClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    private AnthropicClient newAnthropicClient(@NonNull String apiKey) {
        var anthropicClientBuilder = AnthropicClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::url)
                .filter(StringUtils::isNotEmpty)
                .ifPresent(anthropicClientBuilder::baseUrl);
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::version)
                .filter(StringUtils::isNotBlank)
                .ifPresent(anthropicClientBuilder::version);
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

    private ChatLanguageModel newChatLanguageModel(String apiKey, LlmAsJudgeModelParameters modelParameters) {
        var builder = AnthropicChatModel.builder()
                .apiKey(apiKey)
                .modelName(modelParameters.name());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);

        return builder.build();
    }

    @Override
    public AnthropicClient generate(@NonNull String apiKey, Object... params) {
        return newAnthropicClient(apiKey);
    }

    @Override
    public ChatLanguageModel generateChat(@NonNull String apiKey, @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newChatLanguageModel(apiKey, modelParameters);
    }
}
