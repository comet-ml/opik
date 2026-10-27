package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import jakarta.ws.rs.BadRequestException;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenAILlmServiceProviderJudgeCustomParametersTest {

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String COMPLETION = """
            {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "gpt-4o",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "{}"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            """;
    private static final List<ChatMessage> MESSAGES = List.of(UserMessage.from("Score the answer"));

    private WireMockServer wireMock;
    private OpenAILlmServiceProvider serviceProvider;

    @BeforeAll
    void setUpAll() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();

        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setLogRequests(false);
        clientConfig.setLogResponses(false);
        serviceProvider = new OpenAILlmServiceProvider(
                new OpenAIClientGenerator(clientConfig, LlmTestClients.RESPONSES_API_HTTP_CLIENT,
                        LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
                mock(LlmProviderFactory.class));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.resetAll();
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(okJson(COMPLETION)));
    }

    @Test
    void judgeSendsTheRuleCustomParametersAsTopLevelKeys() {
        score(LlmAsJudgeModelParameters.builder()
                .name("gpt-5-nano")
                .customParameters(JsonUtils.getJsonNodeFromString("""
                        {"reasoning_effort": "low", "verbosity": "low"}
                        """))
                .build());

        var body = sentBody();
        assertThat(body.get("reasoning_effort").asText()).isEqualTo("low");
        assertThat(body.get("verbosity").asText()).isEqualTo("low");
        assertThat(body.has("custom_parameters")).isFalse();
        assertThat(body.at("/response_format/type").asText()).isEqualTo("json_schema");
        assertThat(body.at("/response_format/json_schema/name").asText()).isEqualTo("scoring_schema");
    }

    @Test
    void judgeSendsACustomParameterThatRepeatsATypedFieldOnceWithTheCustomValue() {
        score(LlmAsJudgeModelParameters.builder()
                .name("gpt-4o")
                .temperature(0.0)
                .seed(7)
                .customParameters(JsonUtils.getJsonNodeFromString("""
                        {"temperature": 0.7, "seed": 42}
                        """))
                .build());

        var rawBody = sentRawBody();
        var body = JsonUtils.getJsonNodeFromString(rawBody);
        assertThat(StringUtils.countMatches(rawBody, "\"temperature\"")).isEqualTo(1);
        assertThat(StringUtils.countMatches(rawBody, "\"seed\"")).isEqualTo(1);
        assertThat(body.get("temperature").asDouble()).isEqualTo(0.7);
        assertThat(body.get("seed").asInt()).isEqualTo(42);
    }

    @Test
    void judgeWithoutCustomParametersSendsOnlyTheTypedFields() {
        score(LlmAsJudgeModelParameters.builder()
                .name("gpt-4o")
                .temperature(0.0)
                .seed(7)
                .build());

        var body = (ObjectNode) sentBody();
        assertThat(body.remove("response_format").at("/json_schema/name").asText()).isEqualTo("scoring_schema");
        assertThat(body).isEqualTo(JsonUtils.getJsonNodeFromString("""
                {"model": "gpt-4o", "messages": [{"role": "user", "content": "Score the answer"}],
                 "temperature": 0.0, "stream": false, "seed": 7}
                """));
    }

    @ParameterizedTest
    @ValueSource(strings = {"model", "messages", "stream", "stream_options"})
    void judgeRejectsAnExtraBodyKeyOpikSetsFromTheRequestBeforeSending(String key) {
        var parameters = LlmAsJudgeModelParameters.builder()
                .name("gpt-4o")
                .customParameters(JsonUtils.getMapper().valueToTree(Map.of(key, "other")))
                .build();

        assertThatThrownBy(() -> serviceProvider.getLanguageModel(config(), parameters))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("custom_parameters.%s is set by Opik from the request and cannot be replaced"
                        .formatted(key));
        assertThat(wireMock.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)))).isEmpty();
    }

    private LlmProviderClientApiConfig config() {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(wireMock.baseUrl())
                .build();
    }

    private void score(LlmAsJudgeModelParameters modelParameters) {
        var config = config();
        var schema = List.of(LlmAsJudgeOutputSchema.builder()
                .name("Relevance")
                .type(LlmAsJudgeOutputSchemaType.INTEGER)
                .description("How relevant the answer is")
                .build());
        ChatRequest request = new ToolCallingStrategy()
                .apply(ChatRequest.builder().messages(MESSAGES), MESSAGES, schema)
                .build();

        serviceProvider.getLanguageModel(config, modelParameters).chat(request);
    }

    private JsonNode sentBody() {
        return JsonUtils.getJsonNodeFromString(sentRawBody());
    }

    private String sentRawBody() {
        var requests = wireMock.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)));
        assertThat(requests).hasSize(1);
        return requests.getFirst().getBodyAsString();
    }
}
