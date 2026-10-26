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
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;

import java.util.List;

import static com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator.ApiPipelineMode.RESPONSES_API;

@Slf4j
class OpenAILlmServiceProvider implements LlmServiceProvider {

    private static final String REASONING_EFFORT = "reasoning_effort";
    private static final List<String> OPENAI_EFFORT_LEVELS = List.of(
            "none", "minimal", "low", "medium", "high", "xhigh", "max");

    private enum ToolRoute {
        AS_CONFIGURED,
        RESPONSES_API,
        CHAT_COMPLETIONS_WITHOUT_REASONING
    }

    private final OpenAIClientGenerator clientGenerator;
    private final LlmProviderFactory factory;

    OpenAILlmServiceProvider(
            @Named("openaiGenerator") OpenAIClientGenerator clientGenerator,
            LlmProviderFactory factory) {
        this.clientGenerator = clientGenerator;
        this.factory = factory;
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
        return clientGenerator.newOpenAiChatLanguageModel(config,
                withEffortTheRouteOffers(modelParameters, isResponsesApi(config)));
    }

    @Override
    public ChatModel getToolCallingLanguageModel(@NonNull LlmProviderClientApiConfig config,
            @NonNull LlmAsJudgeModelParameters modelParameters, @Nullable ModelParams.ToolsParam tools) {
        return switch (toolRoute(config, tools)) {
            case AS_CONFIGURED -> clientGenerator.newOpenAiChatLanguageModel(config,
                    withEffortTheRouteOffers(modelParameters, isResponsesApi(config)));
            case RESPONSES_API -> clientGenerator.newResponsesApiChatModel(config,
                    withEffortTheRouteOffers(modelParameters, true));
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
        if (chatCompletions == null || isResponsesApi(config)) {
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

    private boolean isResponsesApi(LlmProviderClientApiConfig config) {
        return clientGenerator.extractApiPipelineMode(config) == RESPONSES_API;
    }

    // A rule saved through the API can name a level the route the judge takes doesn't have, and OpenAI answers 400:
    // "max" exists only on the Responses API, and not for every model there. It is left out so the model runs at its
    // own default, which is also what the rule editor shows for it and what the chat path does on Chat Completions.
    // An effort name OpenAI doesn't have at all is sent as saved, so its 400 still names the typo.
    private LlmAsJudgeModelParameters withEffortTheRouteOffers(LlmAsJudgeModelParameters modelParameters,
            boolean responsesApi) {
        if (!(modelParameters.customParameters() instanceof ObjectNode customParameters)
                || !customParameters.path(REASONING_EFFORT).isTextual()) {
            return modelParameters;
        }
        var effort = customParameters.get(REASONING_EFFORT).asText();
        var offered = factory.openAiParams(modelParameters.name())
                .map(params -> responsesApi && params.routes() != null
                        ? params.routes().getOrDefault(ModelParams.RESPONSES_API_ROUTE, params)
                        : params)
                .map(ModelParams::effort)
                .filter(params -> Boolean.TRUE.equals(params.accepted()) && params.levels() != null)
                .map(ModelParams.EffortParam::levels);
        if (!OPENAI_EFFORT_LEVELS.contains(effort) || offered.isEmpty() || offered.get().contains(effort)) {
            return modelParameters;
        }
        log.info("Leaving out reasoning_effort '{}' for judge model '{}': the {} route offers {}", effort,
                modelParameters.name(), responsesApi ? "Responses API" : "Chat Completions", offered.get());
        var kept = customParameters.deepCopy();
        kept.remove(REASONING_EFFORT);
        return modelParameters.toBuilder().customParameters(kept.isEmpty() ? null : kept).build();
    }
}
