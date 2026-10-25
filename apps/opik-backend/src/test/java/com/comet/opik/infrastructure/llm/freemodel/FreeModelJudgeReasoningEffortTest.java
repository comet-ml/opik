package com.comet.opik.infrastructure.llm.freemodel;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.FreeModelConfig;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.openai.OpenAIClientGenerator;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class FreeModelJudgeReasoningEffortTest {

    private static final String COMPLETIONS_PATH = "/chat/completions";
    private static final String COMPLETION = """
            {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "gpt-5-nano",
             "choices": [{"index": 0, "message": {"role": "assistant", "content": "{}"}, "finish_reason": "stop"}],
             "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2}}
            """;

    private WireMockServer wireMock;

    @BeforeEach
    void setUp() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo(COMPLETIONS_PATH)).willReturn(okJson(COMPLETION)));
    }

    @AfterEach
    void tearDown() {
        wireMock.stop();
    }

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource(value = {"gpt-5-nano, minimal", "gpt-4o-mini, null"}, nullValues = "null")
    void aFreeModelJudgeSendsTheEffortTheFreeModelChatSends(String actualModel, String expectedEffort) {
        var freeModelConfig = new FreeModelConfig();
        freeModelConfig.setEnabled(true);
        freeModelConfig.setActualModel(actualModel);
        var clientConfig = new LlmProviderClientConfig();
        var provider = new FreeModelServiceProvider(new OpenAIClientGenerator(clientConfig),
                mock(LlmProviderFactory.class), freeModelConfig, clientConfig);
        var key = LlmProviderClientApiConfig.builder().apiKey("test-key").baseUrl(wireMock.baseUrl()).build();

        provider.getLanguageModel(key, LlmAsJudgeModelParameters.builder().name(FreeModelConfig.FREE_MODEL).build())
                .chat(ChatRequest.builder().messages(UserMessage.from("Score the answer")).build());

        var requests = wireMock.findAll(postRequestedFor(urlEqualTo(COMPLETIONS_PATH)));
        assertThat(requests).hasSize(1);
        var body = JsonUtils.getJsonNodeFromString(requests.getFirst().getBodyAsString());
        assertThat(body.has("reasoning_effort") ? body.get("reasoning_effort").asText() : null)
                .isEqualTo(expectedEffort);
    }
}
