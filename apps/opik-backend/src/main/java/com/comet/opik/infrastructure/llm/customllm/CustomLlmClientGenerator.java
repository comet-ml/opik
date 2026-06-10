package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.langchain4j.OpikOpenAiChatModel;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientGenerator;
import dev.langchain4j.http.client.HttpClientBuilder;
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
import java.util.Map;
import java.util.Optional;

@RequiredArgsConstructor
@Slf4j
public class CustomLlmClientGenerator implements LlmProviderClientGenerator<OpenAiClient> {

    private final @NonNull LlmProviderClientConfig llmProviderClientConfig;

    public OpenAiClient newCustomLlmClient(@NonNull LlmProviderClientApiConfig config) {
        var baseUrl = Optional.ofNullable(config.baseUrl())
                .filter(StringUtils::isNotBlank)
                .orElseThrow(() -> new IllegalArgumentException(
                        "custom provider client not configured properly, missing url"));

        var openAiClientBuilder = OpenAiClient.builder()
                .baseUrl(baseUrl)
                .httpClientBuilder(newHttpClientBuilder(config))
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
                .httpClientBuilder(newHttpClientBuilder(config))
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

    /**
     * Builds the HTTP client builder passed to LangChain4j. The underlying
     * {@code JdkHttpClientBuilder} is always returned (HTTP/1.1 pinning for
     * vLLM / FastAPI servers is pre-OPIK-4551 behaviour that must be preserved
     * for every Custom LLM provider). Only the
     * {@link InterceptingHttpClientBuilder} wrapper is conditional — applied
     * when the provider actually needs request mutation (OPIK-4551 config keys
     * or {@code {model}} placeholder). Legacy providers therefore see a plain
     * {@code JdkHttpClientBuilder} — the same object LangChain4j received
     * before OPIK-4551.
     */
    private HttpClientBuilder newHttpClientBuilder(LlmProviderClientApiConfig config) {
        // Force HTTP/1.1 to avoid upgrade. For example, vLLM is built on FastAPI and explicitly uses HTTP/1.1
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);

        JdkHttpClientBuilder jdkHttpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(httpClientBuilder);

        if (!requiresInterceptingBuilder(config)) {
            return jdkHttpClientBuilder;
        }
        return new InterceptingHttpClientBuilder(jdkHttpClientBuilder, config.configuration(), config.apiKey());
    }

    /**
     * True when the request needs mutation by {@link InterceptingHttpClient}:
     * the provider either declares one of the OPIK-4551 configuration keys or
     * uses a {@code {model}} placeholder in its base URL.
     */
    private static boolean requiresInterceptingBuilder(LlmProviderClientApiConfig config) {
        Map<String, String> configuration = config.configuration();
        boolean hasNewConfigKeys = configuration != null
                && (StringUtils.isNotBlank(configuration.get(InterceptingHttpClient.URL_QUERY_PARAMS_CONFIG_KEY))
                        || StringUtils.isNotBlank(configuration.get(InterceptingHttpClient.AUTH_HEADER_NAME_CONFIG_KEY))
                        || Boolean.TRUE.toString().equalsIgnoreCase(
                                StringUtils.trimToNull(
                                        configuration.get(InterceptingHttpClient.SUPPRESS_DEFAULT_AUTH_CONFIG_KEY))));
        boolean hasModelPlaceholder = config.baseUrl() != null
                && config.baseUrl().contains(InterceptingHttpClient.MODEL_PLACEHOLDER);
        return hasNewConfigKeys || hasModelPlaceholder;
    }
}
