package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;

import static dev.langchain4j.model.openai.internal.OpenAiUtils.DEFAULT_OPENAI_URL;

@RequiredArgsConstructor
@Slf4j
public class OpenAIClientGenerator implements LlmProviderClientGenerator<OpenAiClient> {

    private static final String CONFIG_KEY_PIPELINE_MODE = "pipeline_mode";

    public enum ApiPipelineMode {
        CHAT_COMPLETIONS_API, // Uses traditional /v1/chat/completions
        RESPONSES_API // Uses modern /v1/responses
    }

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newOpenAiClient(@NonNull LlmProviderClientApiConfig config) {
        var openAiClientBuilder = OpenAiClient.builder()
                .baseUrl(DEFAULT_OPENAI_URL)
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(openAiClientBuilder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            openAiClientBuilder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(openAiClientBuilder::customHeaders);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));

        return openAiClientBuilder
                .apiKey(config.apiKey())
                .build();
    }

    public ChatModel newOpenAiChatLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return switch (extractApiPipelineMode(config)) {
            case CHAT_COMPLETIONS_API -> newCompletionsApiChatModel(config, modelParameters);
            case RESPONSES_API -> newResponsesApiChatModel(config, modelParameters);
        };
    }

    @Override
    public OpenAiClient generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        return newOpenAiClient(config);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newOpenAiChatLanguageModel(config, modelParameters);
    }

    ApiPipelineMode extractApiPipelineMode(@NonNull LlmProviderClientApiConfig config) {
        String pipelineMode = Optional.ofNullable(config.configuration())
                .orElse(Map.of())
                .get(CONFIG_KEY_PIPELINE_MODE);
        if (StringUtils.isBlank(pipelineMode)) {
            return ApiPipelineMode.CHAT_COMPLETIONS_API;
        }
        try {
            return ApiPipelineMode.valueOf(pipelineMode.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unknown OpenAI '{}' value '{}', falling back to {}",
                    CONFIG_KEY_PIPELINE_MODE, pipelineMode, ApiPipelineMode.CHAT_COMPLETIONS_API);
            return ApiPipelineMode.CHAT_COMPLETIONS_API;
        }
    }

    ChatModel newCompletionsApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var builder = OpenAiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                .logRequests(true)
                .logResponses(true);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(builder::seed);

        return builder.build();
    }

    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var builder = OpenAiOfficialResponsesChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);

        return builder.build();
    }
}
