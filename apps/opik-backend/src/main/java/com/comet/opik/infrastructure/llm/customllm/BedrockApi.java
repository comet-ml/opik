package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Locale;
import java.util.Optional;

/**
 * The API a Bedrock provider sends its requests through. Bedrock serves Claude only through the Anthropic Messages
 * API and Amazon Nova only through Converse, neither through Chat Completions, so their models need a provider set to
 * {@link #ANTHROPIC_MESSAGES} or {@link #CONVERSE}.
 */
@Slf4j
enum BedrockApi {
    CHAT_COMPLETIONS,
    ANTHROPIC_MESSAGES,
    CONVERSE;

    static final String CONFIG_KEY = "bedrock_api";

    private static final String MESSAGES_PATH = "/messages";
    private static final String VERSION_PATH = "/v1";

    static BedrockApi of(@NonNull LlmProviderClientApiConfig config) {
        if (config.provider() != LlmProvider.BEDROCK) {
            return CHAT_COMPLETIONS;
        }
        var value = Optional.ofNullable(config.configuration())
                .map(configuration -> configuration.get(CONFIG_KEY))
                .orElse(null);
        if (StringUtils.isBlank(value)) {
            return CHAT_COMPLETIONS;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            log.warn("Unknown '{}' value '{}', falling back to '{}'", CONFIG_KEY, value, CHAT_COMPLETIONS);
            return CHAT_COMPLETIONS;
        }
    }

    /**
     * AWS documents the Anthropic route as {@code …/anthropic} for bedrock-runtime and as the full
     * {@code …/anthropic/v1/messages} for bedrock-mantle, and people paste either. The Anthropic client appends
     * {@code messages} itself, so both become {@code …/anthropic/v1}.
     */
    static String messagesBaseUrl(@NonNull String url) {
        var base = StringUtils.stripEnd(url.trim(), "/");
        base = StringUtils.removeEnd(base, MESSAGES_PATH);
        return base.endsWith(VERSION_PATH) ? base : base + VERSION_PATH;
    }

    static String converseBaseUrl(@NonNull String url) {
        return StringUtils.stripEnd(url.trim(), "/");
    }

    static String modelId(@NonNull String model, String providerName) {
        return CustomLlmModelNameChecker.isCustomLlmModel(model)
                ? CustomLlmModelNameChecker.extractModelName(model, providerName)
                : model;
    }
}
