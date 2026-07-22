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
        var thinking = parseThinking(customParameters);

        // Anthropic rejects temperature with a 400 in two cases: (1) adaptive-thinking models
        // (claude-sonnet-5, claude-opus-4-7/4-8) that report no sampling-param support, and (2) any model
        // once extended thinking is enabled per-rule via custom_parameters. Gate on both server-side so
        // API-created rules (which bypass the FE sanitizer) don't fail.
        if (AnthropicModelName.supportsSamplingParams(modelParameters.name()) && !thinking.enabled()) {
            Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);
        }

        applyCustomParameters(builder, customParameters, thinking);

        return builder.build();
    }

    /**
     * Single decode of the {@code thinking} block from a rule's {@code custom_parameters}. Thinking counts as
     * enabled only when {@code type} is an explicit, non-blank value other than {@code "disabled"} — so
     * {@code "enabled"}, {@code "adaptive"}, and any future type gate temperature off, while a missing/blank
     * {@code type} (or absent block) is not enabled and must not gate temperature or shape max_tokens.
     */
    private ThinkingParams parseThinking(JsonNode customParameters) {
        if (customParameters == null || customParameters.isNull()) {
            return ThinkingParams.ABSENT;
        }
        var thinkingNode = customParameters.get("thinking");
        if (thinkingNode == null || !thinkingNode.isObject()) {
            return ThinkingParams.ABSENT;
        }

        String type = null;
        var typeNode = thinkingNode.get("type");
        if (typeNode != null && typeNode.isTextual() && StringUtils.isNotBlank(typeNode.asText())) {
            type = typeNode.asText();
        }

        Integer budgetTokens = null;
        var budgetNode = thinkingNode.get("budget_tokens");
        if (budgetNode != null && budgetNode.canConvertToInt() && budgetNode.asInt() > 0) {
            budgetTokens = budgetNode.asInt();
        }

        return new ThinkingParams(type != null && !"disabled".equals(type), type, budgetTokens);
    }

    /**
     * Forwards the rule's {@code custom_parameters} (thinking, max_tokens) onto the judge-path builder and
     * guarantees a {@code max_tokens} is always sent. Anthropic requires max_tokens, and without an explicit
     * cap adaptive thinking can consume the whole budget, yielding an empty response (finishReason=LENGTH).
     */
    private void applyCustomParameters(AnthropicChatModel.AnthropicChatModelBuilder builder,
            JsonNode customParameters, ThinkingParams thinking) {
        Optional.ofNullable(thinking.type()).ifPresent(builder::thinkingType);

        // budget_tokens is only valid alongside enabled thinking; forwarding it with an absent or "disabled"
        // type produces a partial config that Anthropic rejects with a 400.
        Integer thinkingBudgetTokens = thinking.enabled() ? thinking.budgetTokens() : null;
        Optional.ofNullable(thinkingBudgetTokens).ifPresent(builder::thinkingBudgetTokens);

        builder.maxTokens(resolveMaxTokens(parseMaxTokens(customParameters), thinkingBudgetTokens));
    }

    private Integer parseMaxTokens(JsonNode customParameters) {
        if (customParameters == null || customParameters.isNull()) {
            return null;
        }
        var maxTokensNode = customParameters.get("max_tokens");
        if (maxTokensNode != null && maxTokensNode.canConvertToInt() && maxTokensNode.asInt() > 0) {
            return maxTokensNode.asInt();
        }
        return null;
    }

    /**
     * Resolves the {@code max_tokens} sent to Anthropic, guaranteeing {@code max_tokens > thinking.budget_tokens}
     * (Anthropic rejects otherwise, since max_tokens covers thinking + output). An explicit rule value is honored
     * when it already clears the budget; otherwise it is raised to leave output headroom above the budget.
     */
    private int resolveMaxTokens(Integer maxTokens, Integer thinkingBudgetTokens) {
        int resolved = maxTokens != null ? maxTokens : LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS;
        if (thinkingBudgetTokens != null && resolved <= thinkingBudgetTokens) {
            // Widen to long before adding headroom so an extreme budget can't overflow to a negative int.
            return (int) Math.min(Integer.MAX_VALUE,
                    (long) thinkingBudgetTokens + LlmProviderAnthropicMapper.DEFAULT_MAX_COMPLETION_TOKENS);
        }
        return resolved;
    }

    private record ThinkingParams(boolean enabled, String type, Integer budgetTokens) {
        private static final ThinkingParams ABSENT = new ThinkingParams(false, null, null);
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
