package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.langchain4j.OpikOpenAiChatModel;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.http.client.jdk.JdkHttpClient;
import dev.langchain4j.http.client.jdk.JdkHttpClientBuilder;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.internal.OpenAiClient;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.collections4.MapUtils;
import org.apache.commons.lang3.StringUtils;

import java.net.http.HttpClient;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class CustomLlmClientGenerator implements LlmProviderClientGenerator<OpenAiClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newCustomLlmClient(@NonNull LlmProviderClientApiConfig config) {
        // Force HTTP/1.1 to avoid upgrade. For example, vLLM is built on FastAPI and explicitly uses HTTP/1.1
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);

        JdkHttpClientBuilder jdkHttpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(httpClientBuilder);

        var baseUrl = Optional.ofNullable(config.baseUrl())
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException(
                        "custom provider client not configured properly, missing url"));

        var openAiClientBuilder = OpenAiClient.builder()
                .baseUrl(baseUrl)
                .httpClientBuilder(jdkHttpClientBuilder)
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

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

    public ChatModel newCustomProviderChatLanguageModel(
            @NonNull LlmProviderClientApiConfig config, @NonNull LlmAsJudgeModelParameters modelParameters) {
        var baseUrl = Optional.ofNullable(config.baseUrl())
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException(
                        "custom provider client not configured properly, missing url"));

        // Force HTTP/1.1 to avoid upgrade. For example, vLLM is built on FastAPI and explicitly uses HTTP/1.1
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);

        JdkHttpClientBuilder jdkHttpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(httpClientBuilder);

        // Extract provider_name from configuration (null for legacy providers)
        String providerName = Optional.ofNullable(config.configuration())
                .map(configuration -> configuration.get("provider_name"))
                .orElse(null);

        // Extract the actual model name using the provider name
        String actualModelName = CustomLlmModelNameChecker.extractModelName(modelParameters.name(), providerName);

        log.debug("Cleaned model name from '{}' to '{}' (providerName='{}') for ChatModel",
                modelParameters.name(), actualModelName, providerName);

        var builder = OpikOpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .httpClientBuilder(jdkHttpClientBuilder)
                .modelName(actualModelName)
                .apiKey(config.apiKey())
                .logRequests(true)
                .logResponses(true);

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(config.headers())
                .filter(MapUtils::isNotEmpty)
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(modelParameters.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(modelParameters.seed()).ifPresent(builder::seed);

        // Pass custom parameters directly to constructor since builder inheritance
        // doesn't allow us to chain our custom methods cleanly
        return new OpikOpenAiChatModel(builder, modelParameters.customParameters());
    }

    @Override
    public OpenAiClient generate(@NonNull LlmProviderClientApiConfig config, Object... params) {
        return newCustomLlmClient(config);
    }

    @Override
    public ChatModel generateChat(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        return newCustomProviderChatLanguageModel(config, modelParameters);
    }
}
