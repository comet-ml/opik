package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.domain.llm.ParamResolver;
import com.comet.opik.domain.llm.ParamResolver.ParamChange;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Nullable;
import jakarta.inject.Named;
import lombok.NonNull;
import lombok.extern.slf4j.Slf4j;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator.ApiPipelineMode.RESPONSES_API;

@Slf4j
class OpenAILlmServiceProvider implements LlmServiceProvider {

    private static final String REASONING_EFFORT = "reasoning_effort";
    private static final String NO_REASONING = "none";

    private enum ToolRoute {
        AS_CONFIGURED,
        RESPONSES_API,
        CHAT_COMPLETIONS_WITHOUT_REASONING
    }

    private final OpenAIClientGenerator clientGenerator;

    OpenAILlmServiceProvider(
            @Named("openaiGenerator") OpenAIClientGenerator clientGenerator,
            LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        factory.register(LlmProvider.OPEN_AI, this);
    }

    @Override
    public LlmProviderService getService(@NonNull LlmProviderClientApiConfig apiKey) {
        return switch (clientGenerator.extractApiPipelineMode(apiKey)) {
            case CHAT_COMPLETIONS_API -> new LlmProviderOpenAiChatCompletions(clientGenerator.newOpenAiClient(apiKey));
            // LlmProviderOpenAiResponses builds its ChatModels per request — the per-request
            // response_format.json_schema.strict flag has to be set at langchain4j build time.
            case RESPONSES_API -> new LlmProviderOpenAiResponses(clientGenerator, apiKey);
        };
    }

    @Override
    public ChatModel getLanguageModel(@NonNull LlmProviderClientApiConfig config,
            LlmAsJudgeModelParameters modelParameters) {
        return clientGenerator.newOpenAiChatLanguageModel(config, modelParameters);
    }

    @Override
    public ChatModel getToolCallingLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters, @Nullable ModelParams.ToolsParam tools,
            @NonNull UnaryOperator<LlmAsJudgeModelParameters> resolve) {
        return switch (toolRoute(config, modelParameters, tools)) {
            case AS_CONFIGURED -> clientGenerator.newOpenAiChatLanguageModel(config, resolve.apply(modelParameters));
            case RESPONSES_API -> clientGenerator.newResponsesApiChatModel(config, resolve.apply(modelParameters));
            case CHAT_COMPLETIONS_WITHOUT_REASONING ->
                clientGenerator.newCompletionsApiChatModel(config, resolve.apply(withoutReasoning(modelParameters)));
        };
    }

    // OpenAI's own advice for reasoning with tools is the Responses API. A custom base URL, the key's own or the
    // installation-wide one, can front a server that has no /responses, so where the model also takes tools on Chat
    // Completions without reasoning, that endpoint stays and the call gives up reasoning instead. A rule that asks for
    // no reasoning has nothing to give up and stays too. A model that takes no tools there goes to Responses
    // regardless: on Chat Completions it can only fail.
    private ToolRoute toolRoute(LlmProviderClientApiConfig config, LlmAsJudgeModelParameters modelParameters,
            ModelParams.ToolsParam tools) {
        var chatCompletions = tools == null ? null : tools.chatCompletions();
        if (chatCompletions == null || clientGenerator.extractApiPipelineMode(config) == RESPONSES_API) {
            return ToolRoute.AS_CONFIGURED;
        }
        return chatCompletions == ModelParams.ChatCompletionsTools.EFFORT_NONE_ONLY
                && (NO_REASONING.equals(effortOf(modelParameters)) || clientGenerator.hasCustomBaseUrl(config))
                        ? ToolRoute.CHAT_COMPLETIONS_WITHOUT_REASONING
                        : ToolRoute.RESPONSES_API;
    }

    // Written into custom_parameters rather than the typed field, because an extra body key replaces the typed field
    // of the same name, so a rule's own reasoning_effort would otherwise win.
    private static LlmAsJudgeModelParameters withoutReasoning(LlmAsJudgeModelParameters modelParameters) {
        var customParameters = modelParameters.customParameters() instanceof ObjectNode object
                ? object.deepCopy()
                : JsonUtils.getMapper().createObjectNode();
        var removed = ParamResolver.removeReasoningEffort(customParameters);
        customParameters.put(REASONING_EFFORT, NO_REASONING);
        if (customParameters.equals(modelParameters.customParameters())) {
            return modelParameters;
        }
        log.debug("Request to model '{}' changed: {}", modelParameters.name(),
                Stream.concat(Stream.of(REASONING_EFFORT), removed.stream())
                        .distinct()
                        .map(param -> new ParamChange(param, ParamChange.Reason.REFUSED_BY_ROUTE))
                        .toList());
        return modelParameters.toBuilder().customParameters(customParameters).build();
    }

    private static String effortOf(LlmAsJudgeModelParameters modelParameters) {
        return modelParameters.customParameters() == null
                ? null
                : modelParameters.customParameters().path(REASONING_EFFORT).asText(null);
    }
}
