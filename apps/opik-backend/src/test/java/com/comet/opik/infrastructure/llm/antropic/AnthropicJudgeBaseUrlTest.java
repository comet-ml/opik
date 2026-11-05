package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.AnthropicClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.OpenAiClientConfig;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

class AnthropicJudgeBaseUrlTest {

    private static final String MESSAGES_RESPONSE = """
            {"id":"msg_1","type":"message","role":"assistant","model":"claude-haiku-4-5-20251001",\
            "content":[{"type":"text","text":"ok"}],"stop_reason":"end_turn",\
            "usage":{"input_tokens":1,"output_tokens":1}}""";

    private WireMockServer anthropic;
    private WireMockServer openAi;

    @BeforeEach
    void setUp() {
        anthropic = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        anthropic.start();
        openAi.start();
        anthropic.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(MESSAGES_RESPONSE)));
        openAi.stubFor(post(urlPathEqualTo("/v1/messages")).willReturn(okJson(MESSAGES_RESPONSE)));
    }

    @AfterEach
    void tearDown() {
        anthropic.stop();
        openAi.stop();
    }

    @Test
    void aJudgeReachesTheInstallationsAnthropicUrlWithItsApiVersionNotTheOpenAiUrl() {
        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setAnthropicClient(new AnthropicClientConfig(anthropic.baseUrl() + "/v1/", "2099-01-01"));
        clientConfig.setOpenAiClient(new OpenAiClientConfig(openAi.baseUrl() + "/v1/"));
        var generator = new AnthropicClientGenerator(clientConfig, bundledRegistry(),
                LlmTestClients.LLM_HTTP_CLIENT_BUILDERS);
        var key = LlmProviderClientApiConfig.builder().apiKey("test-key").build();

        generator.generateChat(key, LlmAsJudgeModelParameters.builder().name("claude-haiku-4-5-20251001").build())
                .chat(ChatRequest.builder().messages(UserMessage.from("Score the answer")).build());

        anthropic.verify(1, postRequestedFor(urlPathEqualTo("/v1/messages"))
                .withHeader("anthropic-version", equalTo("2099-01-01")));
        assertThat(openAi.getAllServeEvents()).isEmpty();
    }
}
