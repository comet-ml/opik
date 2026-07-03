package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.internal.client.AnthropicClient;
import dev.langchain4j.model.chat.ChatModel;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

@RequiredArgsConstructor
public class AnthropicClientGenerator implements LlmProviderClientGenerator<AnthropicClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    private AnthropicClient newAnthropicClient(@NonNull LlmProviderClientApiConfig config) {
        var anthropicClientBuilder = AnthropicClient.builder();
        Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::url)
                .filter(StringUtils::isNotEmpty)
                .ifPresent(anthropicClientBuilder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            anthropicClientBuilder.baseUrl(config.baseUrl());
        }

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
                .apiKey(config.apiKey())
                .build();
    }

    private ChatModel newChatLanguageModel(LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        var builder = AnthropicChatModel.builder()
                .apiKey(config.apiKey())
                .modelName(modelParameters.name())
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(LlmProviderClientConfig.OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);

        ChatModel model = builder.build();

        // Rolling-breakpoint prompt caching for the agentic judge loop (default on): wrap the stock
        // model so multi-turn tool conversations cache the whole re-sent transcript, which the
        // high-level Anthropic API can't. The wrapper delegates single-call requests (and any failure)
        // straight to the stock model, so non-agentic scoring is unchanged.
        boolean rollingCaching = Optional.ofNullable(llmProviderClientConfig.getAnthropicClient())
                .map(LlmProviderClientConfig.AnthropicClientConfig::rollingPromptCaching)
                .orElse(true);
        if (rollingCaching) {
            return new OpikCachingAnthropicChatModel(model, newAnthropicClient(config));
        }
        return model;
    }

    @Override
    public AnthropicClient generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        return newAnthropicClient(config);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newChatLanguageModel(config, modelParameters);
    }
}
