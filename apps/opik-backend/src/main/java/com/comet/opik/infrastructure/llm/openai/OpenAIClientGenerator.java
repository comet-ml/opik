package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;
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

    private static final String CONFIG_KEY_PIPELINE_MODE = "openai_pipeline_mode";

    // langchain4j's OpenAiOfficialResponsesChatModel constructor requires a non-null modelName at
    // build time. On the proxy path the real model is supplied per request via
    // ChatRequest.parameters().modelName(...), so this placeholder never reaches OpenAI.
    private static final String PROXY_MODEL_NAME_PLACEHOLDER = "placeholder-model-value";

    public enum ApiPipelineMode {
        CHAT_COMPLETIONS_API, // Uses traditional /v1/chat/completions
        RESPONSES_API // Uses modern /v1/responses
    }

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newOpenAiClient(@NonNull LlmProviderClientApiConfig config) {
        var openAiClientBuilder = OpenAiClient.builder()
                .baseUrl(DEFAULT_OPENAI_URL)
                // Treat insufficient_quota (429, out-of-credits) as non-retryable, so the outer retry
                // policy in ChatCompletionService does not keep hitting an exhausted key.
                .httpClientBuilder(QuotaAwareHttpClient.builder())
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
            log.warn("Unknown OpenAI '{}' value '{}', falling back to '{}'",
                    CONFIG_KEY_PIPELINE_MODE, pipelineMode, ApiPipelineMode.CHAT_COMPLETIONS_API);
            return ApiPipelineMode.CHAT_COMPLETIONS_API;
        }
    }

    ChatModel newCompletionsApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var builder = OpenAiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                // Treat insufficient_quota (429, out-of-credits) as non-retryable so neither the model's
                // internal retry nor the outer retry policy keeps hitting an exhausted key.
                .httpClientBuilder(QuotaAwareHttpClient.builder())
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

    /**
     * Proxy-path overload — synthesizes placeholder judge parameters. The real model name is
     * supplied per request via {@code ChatRequest.parameters().modelName(...)}, so the placeholder
     * never reaches OpenAI; it only satisfies langchain4j's required-field validation at build time.
     * <p>
     * {@code strictJsonSchema} controls langchain4j's build-time strict mode for {@code json_schema}
     * response formats. The Responses-API proxy peeks at the inbound
     * {@code response_format.json_schema.strict} per request and picks the right variant here, since
     * langchain4j has no per-request strict slot.
     */
    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config, boolean strictJsonSchema) {
        return newResponsesApiChatModel(
                config,
                LlmAsJudgeModelParameters.builder().name(PROXY_MODEL_NAME_PLACEHOLDER).build(),
                strictJsonSchema);
    }

    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newResponsesApiChatModel(config, modelParameters, false);
    }

    ChatModel newResponsesApiChatModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters, boolean strictJsonSchema) {
        var builder = OpenAiOfficialResponsesChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                .strictJsonSchema(strictJsonSchema);

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

    /**
     * Proxy-path streaming counterpart to {@link #newResponsesApiChatModel(LlmProviderClientApiConfig, boolean)}.
     * Like the non-streaming variant, the real model name is supplied per request via
     * {@code ChatRequest.parameters().modelName(...)}; a placeholder is used at build time only to
     * satisfy langchain4j's required-field validation. {@code strictJsonSchema} is a build-time
     * setting on the langchain4j model — the proxy passes the per-request {@code strict} bit here.
     */
    StreamingChatModel newResponsesApiStreamingChatModel(@NonNull LlmProviderClientApiConfig config,
            boolean strictJsonSchema) {
        var builder = OpenAiOfficialResponsesStreamingChatModel.builder()
                .modelName(PROXY_MODEL_NAME_PLACEHOLDER)
                .apiKey(config.apiKey())
                .strictJsonSchema(strictJsonSchema);

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

        return builder.build();
    }
}
