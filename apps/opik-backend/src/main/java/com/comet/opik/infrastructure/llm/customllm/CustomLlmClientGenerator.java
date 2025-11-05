package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
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

import static com.comet.opik.infrastructure.llm.customllm.CustomLlmModelNameChecker.CUSTOM_LLM_MODEL_PREFIX;

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

        var builder = OpenAiChatModel.builder()
                .baseUrl(baseUrl)
                .httpClientBuilder(jdkHttpClientBuilder)
                .modelName(modelParameters.name().replace(CUSTOM_LLM_MODEL_PREFIX, ""))
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

        return builder.build();
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
