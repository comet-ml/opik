package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.ModelParams;
import com.comet.opik.api.ModelParams.ChatCompletionsTools;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.request.ToolChoice;
import dev.langchain4j.model.chat.request.json.JsonObjectSchema;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.Map;
import java.util.Optional;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The effort a judge rule saved under custom_parameters, as OpenAI receives it on the route the judge takes, with the
 * shipped catalog's levels per route. Probed 10-23: "max" got a 400 on Chat Completions for gpt-5.6-luna to
 * gpt-6.1-sol and on the Responses API for gpt-5.5, and passed on the Responses API for the gpt-6 models.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAIJudgeEffortRouteTest {

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String RESPONSES_PATH = "/responses";
    private static final String COMPLETION = """
            {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "gpt-6-luna",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "{}"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            """;
    private static final ChatRequest PLAIN_REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("Score the answer"))
            .build();
    private static final ChatRequest TOOL_REQUEST = ChatRequest.builder()
            .messages(UserMessage.from("Score the trace"))
            .toolSpecifications(ToolSpecification.builder()
                    .name("read")
                    .description("Read the trace")
                    .parameters(JsonObjectSchema.builder().addStringProperty("id").required("id").build())
                    .build())
            .toolChoice(ToolChoice.REQUIRED)
            .build();

    private WireMockServer wireMock;
    private OpenAILlmServiceProvider serviceProvider;

    @BeforeAll
    void setUpAll() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        clientConfig.setOpenAiClient(new OpenAiClientConfig(wireMock.baseUrl()));
        var factory = mock(LlmProviderFactory.class);
        var registry = bundledRegistry();
        when(factory.openAiParams(anyString())).thenAnswer(invocation -> Optional.ofNullable(
                registry.paramsOf("openai", invocation.getArgument(0, String.class))));
        serviceProvider = new OpenAILlmServiceProvider(new OpenAIClientGenerator(clientConfig), factory);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(okJson(COMPLETION)));
        // Only the outgoing request matters; a 400 is not retried, so each call is recorded once.
        wireMock.stubFor(post(urlEqualTo(RESPONSES_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"message\":\"stub\",\"type\":\"invalid_request_error\"}}")));
    }

    @ParameterizedTest
    @CsvSource({"gpt-6-luna,max,", "gpt-6.1-sol,max,", "gpt-6-luna,xhigh,xhigh", "gpt-5.4,low,low",
            "gpt-6-luna,ultra,ultra"})
    void aChatCompletionsKeySendsOnlyAnEffortChatCompletionsOffers(String model, String saved, String sent) {
        call(serviceProvider.getLanguageModel(chatCompletionsKey(), judge(model, saved)), PLAIN_REQUEST);

        var body = sentBody(COMPLETIONS_PATH);
        assertThat(body.path("reasoning_effort").isMissingNode() ? null : body.get("reasoning_effort").asText())
                .isEqualTo(sent);
        assertThat(body.at("/service_tier").asText()).isEqualTo("flex");
    }

    @ParameterizedTest
    @CsvSource({"gpt-6-luna,max,max", "gpt-5.5,max,", "gpt-5.5,xhigh,xhigh"})
    void aResponsesKeySendsOnlyAnEffortTheResponsesApiOffers(String model, String saved, String sent) {
        call(serviceProvider.getLanguageModel(responsesKey(), judge(model, saved)), PLAIN_REQUEST);

        var body = sentBody(RESPONSES_PATH);
        assertThat(body.at("/reasoning/effort").isMissingNode() ? null : body.at("/reasoning/effort").asText())
                .isEqualTo(sent);
    }

    @Test
    void aToolCallingJudgeSentToTheResponsesApiKeepsAMaxItOffersThere() {
        var model = serviceProvider.getToolCallingLanguageModel(chatCompletionsKey(), judge("gpt-6-luna", "max"),
                ModelParams.ToolsParam.builder().chatCompletions(ChatCompletionsTools.EFFORT_NONE_ONLY).build());
        call(model, TOOL_REQUEST);

        assertThat(sentBody(RESPONSES_PATH).at("/reasoning/effort").asText()).isEqualTo("max");
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)))).isEmpty();
    }

    @Test
    void aModelTheCatalogDoesNotListKeepsItsEffort() {
        call(serviceProvider.getLanguageModel(chatCompletionsKey(), judge("gpt-7-preview", "max")), PLAIN_REQUEST);

        assertThat(sentBody(COMPLETIONS_PATH).at("/reasoning_effort").asText()).isEqualTo("max");
    }

    private static LlmProviderClientApiConfig chatCompletionsKey() {
        return LlmProviderClientApiConfig.builder().apiKey("test-key").build();
    }

    private static LlmProviderClientApiConfig responsesKey() {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .configuration(Map.of("openai_pipeline_mode", "responses_api"))
                .build();
    }

    private static LlmAsJudgeModelParameters judge(String model, String effort) {
        return LlmAsJudgeModelParameters.builder()
                .name(model)
                .customParameters(JsonUtils.getJsonNodeFromString("""
                        {"reasoning_effort": "%s", "service_tier": "flex"}""".formatted(effort)))
                .build();
    }

    private static void call(ChatModel model, ChatRequest request) {
        catchThrowable(() -> model.chat(request));
    }

    private JsonNode sentBody(String path) {
        var requests = wireMock.findAll(postRequestedFor(urlEqualTo(path)));
        assertThat(requests).hasSize(1);
        return JsonUtils.getJsonNodeFromString(requests.getFirst().getBodyAsString());
    }
}
