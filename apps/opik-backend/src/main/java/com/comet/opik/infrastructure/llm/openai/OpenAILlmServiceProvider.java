package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.ModelParams;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.LlmProviderService;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmServiceProvider;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.node.ObjectNode;
import dev.langchain4j.model.chat.ChatModel;
import jakarta.annotation.Nullable;
import jakarta.inject.Named;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;

import static com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator.ApiPipelineMode.RESPONSES_API;

class OpenAILlmServiceProvider implements LlmServiceProvider {

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
            @NonNull LlmAsJudgeModelParameters modelParameters, @Nullable ModelParams.ToolsParam tools) {
        return switch (toolRoute(config, tools)) {
            case AS_CONFIGURED -> clientGenerator.newOpenAiChatLanguageModel(config, modelParameters);
            case RESPONSES_API -> clientGenerator.newResponsesApiChatModel(config, modelParameters);
            case CHAT_COMPLETIONS_WITHOUT_REASONING ->
                clientGenerator.newCompletionsApiChatModel(config, withoutReasoning(modelParameters));
        };
    }

    // OpenAI's own advice for reasoning with tools is the Responses API. A key's own base URL can front a server that
    // has no /responses, so where the model also takes tools on Chat Completions without reasoning, the key's
    // endpoint stays and the call gives up reasoning instead. A model that takes no tools there goes to Responses
    // regardless: on Chat Completions it can only fail.
    private ToolRoute toolRoute(LlmProviderClientApiConfig config, ModelParams.ToolsParam tools) {
        var chatCompletions = tools == null ? null : tools.chatCompletions();
        if (chatCompletions == null || clientGenerator.extractApiPipelineMode(config) == RESPONSES_API) {
            return ToolRoute.AS_CONFIGURED;
        }
        return chatCompletions == ModelParams.ChatCompletionsTools.EFFORT_NONE_ONLY
                && StringUtils.isNotEmpty(config.baseUrl())
                        ? ToolRoute.CHAT_COMPLETIONS_WITHOUT_REASONING
                        : ToolRoute.RESPONSES_API;
    }

    // Written into custom_parameters rather than the typed field, because an extra body key replaces the typed field
    // of the same name, so a rule's own reasoning_effort would otherwise win.
    private static LlmAsJudgeModelParameters withoutReasoning(LlmAsJudgeModelParameters modelParameters) {
        var customParameters = modelParameters.customParameters() instanceof ObjectNode object
                ? object.deepCopy()
                : JsonUtils.getMapper().createObjectNode();
        customParameters.put("reasoning_effort", "none");
        return modelParameters.toBuilder().customParameters(customParameters).build();
    }
}
