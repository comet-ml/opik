package com.comet.opik.infrastructure.llm.freemodel;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.infrastructure.llm.ProviderHeadersHttpClient;
import com.comet.opik.infrastructure.llm.customllm.LlmHttpClientBuilders;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.comet.opik.infrastructure.llm.openai.QuotaAwareHttpClient;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * Service provider for the Opik Free Model LLM provider.
 * This provider transforms the model name from "opik-free-model" to the actual model.
 */
@Slf4j
public class FreeModelServiceProvider implements LlmServiceProvider {

    private final OpenAIClientGenerator clientGenerator;
    private final FreeModelConfig freeModelConfig;
    private final LlmProviderClientConfig llmProviderClientConfig;
    private final LlmHttpClientBuilders httpClientBuilders;

    public FreeModelServiceProvider(
            @NonNull OpenAIClientGenerator clientGenerator,
            @NonNull LlmProviderFactory factory,
            @NonNull FreeModelConfig freeModelConfig,
            @NonNull LlmProviderClientConfig llmProviderClientConfig,
            @NonNull LlmHttpClientBuilders httpClientBuilders) {
        this.clientGenerator = clientGenerator;
        this.httpClientBuilders = httpClientBuilders;
        this.freeModelConfig = freeModelConfig;
        this.llmProviderClientConfig = llmProviderClientConfig;

        if (freeModelConfig.isEnabled()) {
            factory.register(LlmProvider.OPIK_FREE, this);
            log.info("Registered OPIK_FREE provider with actual model '{}'", freeModelConfig.getActualModel());
        }
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig config) {
        return new FreeModelLlmProvider(
                // the free model's URL is the operator's, which may be internal
                clientGenerator.newOpenAiClient(config, httpClientBuilders.builder()),
                freeModelConfig.getActualModel(),
                freeModelConfig.isReasoningModel());
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        Double temperature = modelParameters.temperature();

        if (freeModelConfig.isReasoningModel() && temperature != null
                && temperature < FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE) {
            log.debug("Clamping temperature from '{}' to '{}' for reasoning model '{}'",
                    temperature, FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE,
                    freeModelConfig.getActualModel());
            temperature = FreeModelConfig.OPENAI_REASONING_MODEL_MIN_TEMPERATURE;
        }

        var transformedParameters = LlmAsJudgeModelParameters.builder()
                .name(freeModelConfig.getActualModel())
                .temperature(temperature)
                .seed(modelParameters.seed())
                .build();

        var builder = OpenAiChatModel.builder()
                .modelName(transformedParameters.name())
                .apiKey(config.apiKey())
                // Treat insufficient_quota (429, out-of-credits) as non-retryable so the model's
                // internal retry does not keep hitting an exhausted key.
                .httpClientBuilder(QuotaAwareHttpClient.builder(
                        ProviderHeadersHttpClient.builder(httpClientBuilders.builder(), config.headers())))
                .logRequests(llmProviderClientConfig.getLogRequests())
                .logResponses(llmProviderClientConfig.getLogResponses());

        Optional.ofNullable(llmProviderClientConfig.getConnectTimeout())
                .ifPresent(connectTimeout -> builder.timeout(connectTimeout.toJavaDuration()));

        Optional.ofNullable(llmProviderClientConfig.getOpenAiClient())
                .map(OpenAiClientConfig::url)
                .filter(StringUtils::isNotBlank)
                .ifPresent(builder::baseUrl);

        if (StringUtils.isNotEmpty(config.baseUrl())) {
            builder.baseUrl(config.baseUrl());
        }

        Optional.ofNullable(transformedParameters.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(transformedParameters.seed()).ifPresent(builder::seed);
        // Same reason as FreeModelLlmProvider: gpt-5-nano defaults to medium effort, which makes a judge slow.
        if (freeModelConfig.isReasoningModel()) {
            builder.reasoningEffort(FreeModelConfig.OPENAI_REASONING_MODEL_EFFORT);
        }

        return builder.build();
    }
}
