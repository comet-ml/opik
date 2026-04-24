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
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        // Only wire our decorator when the provider actually uses an OPIK-4551
        // feature. Legacy providers (Ollama, vLLM, bare OpenAI-compat) fall back
        // to LangChain4j's default HTTP client so their path is byte-identical
        // to pre-OPIK-4551 behaviour.
        if (requiresInterceptingBuilder(config)) {
            openAiClientBuilder.httpClientBuilder(newInterceptingHttpClientBuilder(config));
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
                .modelName(actualModelName)
                .apiKey(config.apiKey())
                .logRequests(true)
                .logResponses(true);

        // See the comment in newCustomLlmClient — decorator only wired when needed.
        if (requiresInterceptingBuilder(config)) {
            builder.httpClientBuilder(newInterceptingHttpClientBuilder(config));
        }

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

    /// Builds the Custom LLM decorator over a fresh `JdkHttpClientBuilder`. Only
    /// invoked when the provider actually needs request mutation; keeps the HTTP/1.1
    /// pinning that vLLM (FastAPI) relies on in place for the mutated path.
    private InterceptingHttpClientBuilder newInterceptingHttpClientBuilder(LlmProviderClientApiConfig config) {
        // Force HTTP/1.1 to avoid upgrade. For example, vLLM is built on FastAPI and explicitly uses HTTP/1.1
        HttpClient.Builder httpClientBuilder = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1);

        JdkHttpClientBuilder jdkHttpClientBuilder = JdkHttpClient.builder()
                .httpClientBuilder(httpClientBuilder);

        return new InterceptingHttpClientBuilder(jdkHttpClientBuilder, config.configuration(), config.apiKey());
    }

    /// True when the request needs mutation by `InterceptingHttpClient`: the
    /// provider either declares one of the OPIK-4551 configuration keys or uses
    /// a `{model}` placeholder in its base URL.
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
