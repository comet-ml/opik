package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.ai4j.openai4j.OpenAiClient;
import dev.langchain4j.model.chat.ChatLanguageModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;

@RequiredArgsConstructor
public class OpenAIClientGenerator implements LlmProviderClientGenerator<OpenAiClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newOpenAiClient(@NonNull String apiKey) {
        var openAiClientBuilder = OpenAiClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(openAiClientBuilder::baseUrl);
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

    public ChatLanguageModel newOpenAiChatLanguageModel(String apiKey, LlmAsJudgeModelParameters modelParameters) {
        var builder = OpenAiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(apiKey)
                .logRequests(true)
                .logResponses(true);

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
    public OpenAiClient generate(@NonNull String apiKey, Object... args) {
        return newOpenAiClient(apiKey);
    }

    @Override
    public ChatLanguageModel generateChat(@NonNull String apiKey, @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newOpenAiChatLanguageModel(apiKey, modelParameters);
    }
}
