package com.comet.opik.infrastructure.llm.vllm;

import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.http.HttpClient;
import java.util.Optional;

import static com.comet.opik.api.AutomationRuleEvaluatorLlmAsJudge.LlmAsJudgeModelParameters;
import static dev.langchain4j.model.openai.internal.OpenAiUtils.DEFAULT_OPENAI_URL;

@RequiredArgsConstructor
@Slf4j
public class VllmClientGenerator implements LlmProviderClientGenerator<OpenAiClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newVllmClient(@NonNull LlmProviderClientApiConfig config) {
        // Force HTTP/1.1 to avoid upgrade. vLLM is built on FastAPI and explicitly uses HTTP/1.1.
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);

        JdkHttpClientBuilder jdkHttpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(httpClientBuilder);

        var openAiClientBuilder = OpenAiClient.builder()
                .baseUrl(DEFAULT_OPENAI_URL)
                .httpClientBuilder(jdkHttpClientBuilder)
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        String finalBaseUrl = DEFAULT_OPENAI_URL;
        Optional.ofNullable(llmProviderClientConfig.getVllmClient())
                .map(LlmProviderClientConfig.VllmClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(url -> {
                    openAiClientBuilder.baseUrl(url);
                });

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            log.info("Using vLLM provider-specific baseUrl: {}", config.baseUrl());
            finalBaseUrl = config.baseUrl();
            openAiClientBuilder.baseUrl(config.baseUrl());
        }

        if (finalBaseUrl.equals(DEFAULT_OPENAI_URL)) {
            log.warn(
                    "vLLM client is using default OpenAI URL '{}' - this may cause issues. Ensure baseUrl is properly configured.",
                    DEFAULT_OPENAI_URL);
        }

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(openAiClientBuilder::customHeaders);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> openAiClientBuilder.connectTimeout(connectTimeout.toJavaDuration()));
        Optional.ofNullable(llmProviderClientConfig.getReadTimeout())
                .ifPresent(readTimeout -> openAiClientBuilder.readTimeout(readTimeout.toJavaDuration()));

        var client = openAiClientBuilder
                .apiKey(config.apiKey())
                .build();

        return client;
    }

    public ChatModel newVllmChatLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        var builder = OpenAiChatModel.builder()
                .modelName(modelParameters.name())
                .apiKey(config.apiKey())
                .logRequests(true)
                .logResponses(true);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getVllmClient())
                .map(LlmProviderClientConfig.VllmClientConfig::url)
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

    @Override
    public OpenAiClient generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        return newVllmClient(config);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newVllmChatLanguageModel(config, modelParameters);
    }
}
