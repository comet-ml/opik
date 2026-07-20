package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import com.fasterxml.jackson.databind.JsonNode;
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

        var customParameters = modelParameters.customParameters();

        // Anthropic rejects temperature with a 400 in two cases: (1) adaptive-thinking models
        // (claude-sonnet-5, claude-opus-4-7/4-8) that report no sampling-param support, and (2) any model
        // once extended thinking is enabled per-rule via custom_parameters. Gate on both server-side so
        // API-created rules (which bypass the FE sanitizer) don't fail.
        if (AnthropicModelName.supportsSamplingParams(modelParameters.name()) && !isThinkingEnabled(customParameters)) {
            Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);
        }

        applyCustomParameters(builder, customParameters);

        return builder.build();
    }

    private boolean isThinkingEnabled(JsonNode customParameters) {
        if (customParameters == null || customParameters.isNull()) {
            return false;
        }
        var thinkingNode = customParameters.get("thinking");
        if (thinkingNode == null || !thinkingNode.isObject()) {
            return false;
        }
        var typeNode = thinkingNode.get("type");
        return typeNode != null && typeNode.isTextual() && "enabled".equals(typeNode.asText());
    }

    /**
     * Forwards the rule's {@code custom_parameters} (thinking, max_tokens) onto the judge-path builder and
     * guarantees a {@code max_tokens} is always sent. Anthropic requires max_tokens, and without an explicit
     * cap adaptive thinking can consume the whole budget, yielding an empty response (finishReason=LENGTH).
     */
    private void applyCustomParameters(AnthropicChatModel.AnthropicChatModelBuilder builder,
            JsonNode customParameters) {
        Integer maxTokens = null;
        Integer thinkingBudgetTokens = null;

        if (customParameters != null && !customParameters.isNull()) {
            var maxTokensNode = customParameters.get("max_tokens");
            if (maxTokensNode != null && maxTokensNode.canConvertToInt() && maxTokensNode.asInt() > 0) {
                maxTokens = maxTokensNode.asInt();
            }

            var thinkingNode = customParameters.get("thinking");
            if (thinkingNode != null && thinkingNode.isObject()) {
                var typeNode = thinkingNode.get("type");
                if (typeNode != null && typeNode.isTextual()) {
                    builder.thinkingType(typeNode.asText());
                }
                var budgetNode = thinkingNode.get("budget_tokens");
                if (budgetNode != null && budgetNode.canConvertToInt() && budgetNode.asInt() > 0) {
                    thinkingBudgetTokens = budgetNode.asInt();
                    builder.thinkingBudgetTokens(thinkingBudgetTokens);
                }
            }
        }

        builder.maxTokens(resolveMaxTokens(maxTokens, thinkingBudgetTokens));
    }

    /**
     * Resolves the {@code max_tokens} sent to Anthropic. An explicit rule value is honored as-is (the caller
     * owns the constraint). Otherwise the default applies, but when a thinking budget is set the default must
     * clear it: Anthropic requires {@code max_tokens > thinking.budget_tokens} (max_tokens covers thinking +
     * output), so a bare 4096 default would 400 for any budget >= 4096. Leave headroom for the output on top.
     */
    private int resolveMaxTokens(Integer maxTokens, Integer thinkingBudgetTokens) {
        if (maxTokens != null) {
            return maxTokens;
        }
        if (thinkingBudgetTokens != null) {
            return thinkingBudgetTokens + LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS;
        }
        return LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS;
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
