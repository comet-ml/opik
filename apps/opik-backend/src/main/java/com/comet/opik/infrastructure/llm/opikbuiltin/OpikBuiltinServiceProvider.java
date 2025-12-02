package com.comet.opik.infrastructure.llm.opikbuiltin;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.BuiltinLlmProviderConfig;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.Optional;

/**
 * Service provider for the Opik Built-in LLM provider.
 * This provider transforms the model name from "opik-builtin-model" to the actual model.
 */
@Slf4j
public class OpikBuiltinServiceProvider implements LlmServiceProvider {

    private final OpenAIClientGenerator clientGenerator;
    private final BuiltinLlmProviderConfig builtinConfig;
    private final LlmProviderClientConfig llmProviderClientConfig;

    public OpikBuiltinServiceProvider(
            @NonNull OpenAIClientGenerator clientGenerator,
            @NonNull LlmProviderFactory factory,
            @NonNull BuiltinLlmProviderConfig builtinConfig,
            @NonNull LlmProviderClientConfig llmProviderClientConfig) {
        this.clientGenerator = clientGenerator;
        this.builtinConfig = builtinConfig;
        this.llmProviderClientConfig = llmProviderClientConfig;

        if (builtinConfig.isEnabled()) {
            factory.register(LlmProvider.OPIK_BUILTIN, this);
            log.info("Registered OPIK_DEFAULT provider with actual model '{}'", builtinConfig.getActualModel());
        }
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig config) {
        return new OpikBuiltinLlmProvider(
                clientGenerator.newOpenAiClient(config),
                builtinConfig.getActualModel());
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters) {
        // Transform the model name for LLM as Judge
        var transformedParameters = LlmAsJudgeModelParameters.builder()
                .name(builtinConfig.getActualModel())
                .temperature(modelParameters.temperature())
                .seed(modelParameters.seed())
                .build();

        var builder = OpenAiChatModel.builder()
                .modelName(transformedParameters.name())
                .apiKey(config.apiKey())
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
                .filter(headers -> !headers.isEmpty())
                .ifPresent(builder::customHeaders);

        Optional.ofNullable(transformedParameters.temperature()).ifPresent(builder::temperature);
        Optional.ofNullable(transformedParameters.seed()).ifPresent(builder::seed);

        return builder.build();
    }
}
