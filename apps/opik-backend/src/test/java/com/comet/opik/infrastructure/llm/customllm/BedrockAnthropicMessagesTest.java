package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.antropic.AnthropicClientGenerator;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.SystemMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockAnthropicMessagesTest {

    private static final String PROVIDER_NAME = "bedrock-eu";
    private static final String MODEL = "eu.anthropic.claude-haiku-5-5";
    private static final String OPIK_MODEL = "custom-llm/" + PROVIDER_NAME + "/" + MODEL;
    private static final String API_KEY = "bedrock-test-key";
    private static final String MESSAGES_PATH = "/anthropic/v1/messages";
    private static final String MESSAGE = """
            {"id": "msg_1", "type": "message", "role": "assistant", "model": "eu.anthropic.claude-haiku-5-5",
             "content": [{"type": "text", "text": "hi from claude"}], "stop_reason": "end_turn",
             "usage": {"input_tokens": 3, "output_tokens": 4}}
            """;
    private static final String MESSAGE_STREAM = """
            event: message_start
            data: {"type": "message_start", "message": {"id": "msg_1", "type": "message", "role": "assistant", \
            "model": "eu.anthropic.claude-haiku-5-5", "content": [], "stop_reason": null, \
            "usage": {"input_tokens": 3, "output_tokens": 1}}}

            event: content_block_start
            data: {"type": "content_block_start", "index": 0, "content_block": {"type": "text", "text": ""}}

            event: content_block_delta
            data: {"type": "content_block_delta", "index": 0, "delta": {"type": "text_delta", "text": "hi from claude"}}

            event: content_block_stop
            data: {"type": "content_block_stop", "index": 0}

            event: message_delta
            data: {"type": "message_delta", "delta": {"stop_reason": "end_turn"}, "usage": {"output_tokens": 4}}

            event: message_stop
            data: {"type": "message_stop"}

            """;

    private final AuthTokenProvider authTokenProvider = mock(AuthTokenProvider.class);
    private final OpenAICompatibleServiceProvider serviceProvider = new OpenAICompatibleServiceProvider(
            new CustomLlmClientGenerator(new LlmProviderClientConfig(), mock(ProviderBaseUrlGuard.class),
                    authTokenProvider, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
            new AnthropicClientGenerator(TestConfigUtils.loadConfigTest().getLlmProviderClient(), bundledRegistry(),
                    LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
            bundledRegistry(),
            mock(LlmProviderFactory.class));

    private WireMockServer bedrock;

    @BeforeAll
    void startServer() {
        bedrock = LlmTestClients.newLoopbackWireMock();
        bedrock.start();
    }

    @AfterAll
    void stopServer() {
        bedrock.stop();
    }

    @BeforeEach
    void resetServer() {
        bedrock.resetAll();
        bedrock.stubFor(post(urlEqualTo(MESSAGES_PATH)).willReturn(okJson(MESSAGE)));
    }

    @Test
    void sendsClaudeThroughTheAnthropicMessagesRouteWithTheBareModelId() {
        var response = serviceProvider.getService(config("/anthropic"))
                .generate(request(1024), "workspace");

        assertThat(response.choices().getFirst().message().content()).isEqualTo("hi from claude");
        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH))
                .withHeader("x-api-key", equalTo(API_KEY))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .withHeader("Authorization", absent())
                .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("1024"))));
    }

    @Test
    void streamsClaudeThroughTheAnthropicMessagesRoute() throws Exception {
        bedrock.stubFor(post(urlEqualTo(MESSAGES_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(MESSAGE_STREAM)));
        var chunks = new ArrayList<ChatCompletionResponse>();
        var done = new CompletableFuture<Void>();

        serviceProvider.getService(config("/anthropic")).generateStream(
                ChatCompletionRequest.builder().from(request(1024)).stream(true).build(), "workspace",
                chunks::add, () -> done.complete(null), done::completeExceptionally);
        done.get(10, TimeUnit.SECONDS);

        assertThat(chunks).extracting(chunk -> chunk.choices().getFirst().delta().content())
                .contains("hi from claude");
        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH))
                .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL)))
                .withRequestBody(matchingJsonPath("$.stream", equalTo("true"))));
    }

    @ParameterizedTest
    @ValueSource(strings = {"/anthropic", "/anthropic/", "/anthropic/v1", "/anthropic/v1/", "/anthropic/v1/messages"})
    void acceptsEveryFormOfTheAnthropicUrlAwsDocuments(String path) {
        serviceProvider.getService(config(path)).generate(request(1024), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH)));
    }

    @Test
    void replacesTheZeroTokenLimitThePlaygroundAllowsWithTheAnthropicDefault() {
        serviceProvider.getService(config("/anthropic")).generate(request(0), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo("4096"))));
    }

    @ParameterizedTest
    @CsvSource({", 256, 256", "0, 256, 256", "512, 256, 512"})
    void fallsBackToTheLegacyMaxTokensOnlyWithoutAPositiveMaxCompletionTokens(Integer maxCompletionTokens,
            Integer maxTokens, String expected) {
        serviceProvider.getService(config("/anthropic")).generate(ChatCompletionRequest.builder()
                .from(request(0))
                .maxCompletionTokens(maxCompletionTokens)
                .maxTokens(maxTokens)
                .build(), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH))
                .withRequestBody(matchingJsonPath("$.max_tokens", equalTo(expected))));
    }

    @Test
    void sendsTheProvidersCustomHeaders() {
        var config = config("/anthropic").toBuilder().headers(Map.of("X-Team", "evals")).build();

        serviceProvider.getService(config).generate(request(1024), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH)).withHeader("X-Team", equalTo("evals")));
    }

    @Test
    void sendsAFetchedTokenInTheApiKeyHeaderInTokenMode() {
        when(authTokenProvider.bearer(any(), any(), any())).thenReturn("fetched-token");
        var config = config("/anthropic").toBuilder()
                .apiKey(null)
                .authConfig(EncryptedAuthConfig.fromCiphertext("ciphertext"))
                .providerId(UUID.randomUUID())
                .workspaceId("workspace")
                .build();

        serviceProvider.getService(config).generate(request(1024), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH))
                .withHeader("x-api-key", equalTo("fetched-token")));
    }

    @Test
    void judgesThroughTheAnthropicMessagesRouteWithTheBareModelId() {
        var judge = serviceProvider.getLanguageModel(config("/anthropic"),
                LlmAsJudgeModelParameters.builder().name(OPIK_MODEL).build());

        judge.chat(ChatRequest.builder().messages(UserMessage.from("Score the answer")).build());

        bedrock.verify(1, postRequestedFor(urlEqualTo(MESSAGES_PATH))
                .withHeader("x-api-key", equalTo(API_KEY))
                .withRequestBody(matchingJsonPath("$.model", equalTo(MODEL))));
    }

    @Test
    void keepsChatCompletionsWhenTheApiIsNotSet() {
        bedrock.stubFor(post(urlEqualTo("/openai/v1/chat/completions")).willReturn(okJson("""
                {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "openai.gpt-oss-20b-1:0",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}]}
                """)));
        var config = config("/openai/v1").toBuilder()
                .configuration(Map.of("provider_name", PROVIDER_NAME))
                .build();

        serviceProvider.getService(config).generate(request(1024), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo("/openai/v1/chat/completions")));
        assertThat(bedrock.findAll(postRequestedFor(urlEqualTo(MESSAGES_PATH)))).isEmpty();
    }

    @Test
    void ignoresTheSettingOnACustomProvider() {
        bedrock.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(okJson("""
                {"id": "chatcmpl-1", "object": "chat.completion", "created": 0, "model": "m",
                 "choices": [{"index": 0, "message": {"role": "assistant", "content": "hi"}, "finish_reason": "stop"}]}
                """)));
        var config = config("/v1").toBuilder().provider(LlmProvider.CUSTOM_LLM).build();

        serviceProvider.getService(config).generate(request(1024), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo("/v1/chat/completions")));
    }

    private LlmProviderClientApiConfig config(String path) {
        return LlmProviderClientApiConfig.builder()
                .provider(LlmProvider.BEDROCK)
                .apiKey(API_KEY)
                .baseUrl(LlmTestClients.baseUrlOf(bedrock) + path)
                .configuration(Map.of(
                        "provider_name", PROVIDER_NAME,
                        "models", OPIK_MODEL,
                        BedrockApi.CONFIG_KEY, "anthropic_messages"))
                .headers(Map.of())
                .build();
    }

    private static ChatCompletionRequest request(int maxCompletionTokens) {
        return ChatCompletionRequest.builder()
                .model(OPIK_MODEL)
                .maxCompletionTokens(maxCompletionTokens)
                .messages(SystemMessage.from("Be brief."),
                        dev.langchain4j.model.openai.internal.chat.UserMessage.from("Say hi"))
                .build();
    }
}
