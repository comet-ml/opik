package com.comet.opik.infrastructure.llm.customllm;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.EncryptedAuthConfig;
import com.comet.opik.api.LlmProvider;
import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.domain.llm.LlmProviderFactory;
import com.comet.opik.domain.llm.langchain4j.OpikUserMessage;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.antropic.AnthropicClientGenerator;
import com.github.tomakehurst.wiremock.WireMockServer;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.HttpException;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.openai.internal.chat.AssistantMessage;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import dev.langchain4j.model.openai.internal.chat.Message;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static com.comet.opik.infrastructure.llm.LlmModelRegistryTestUtils.bundledRegistry;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class BedrockConverseTest {

    private static final String PROVIDER_NAME = "bedrock-nova";
    private static final String MODEL = "us.amazon.nova-pro-v1:0";
    private static final String OPIK_MODEL = "custom-llm/" + PROVIDER_NAME + "/" + MODEL;
    private static final String API_KEY = "bedrock-test-key";
    private static final String CONVERSE_PATH = "/model/" + MODEL + "/converse";
    private static final String STREAM_PATH = "/model/" + MODEL + "/converse-stream";
    private static final String CONVERSE_RESPONSE = """
            {"output": {"message": {"role": "assistant", "content": [{"text": "Dublin."}]}},
             "stopReason": "end_turn", "usage": {"inputTokens": 12, "outputTokens": 3, "totalTokens": 15},
             "metrics": {"latencyMs": 300}}
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
        bedrock.stubFor(post(urlEqualTo(CONVERSE_PATH)).willReturn(okJson(CONVERSE_RESPONSE)));
    }

    @Test
    void sendsTheRequestInConverseFormatWithTheBareModelIdAndTheKeyAsBearer() {
        var response = serviceProvider.getService(config()).generate(request(
                List.of(dev.langchain4j.model.openai.internal.chat.SystemMessage.from("Be brief."),
                        OpikUserMessage.from("What is the capital of Ireland?")),
                256), "workspace");

        assertThat(response.choices().getFirst().message().content()).isEqualTo("Dublin.");
        assertThat(response.choices().getFirst().finishReason()).isEqualTo("stop");
        assertThat(response.usage().promptTokens()).isEqualTo(12);
        assertThat(response.usage().completionTokens()).isEqualTo(3);
        bedrock.verify(1, postRequestedFor(urlEqualTo(CONVERSE_PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(equalToJson("""
                        {"system": [{"text": "Be brief."}],
                         "messages": [{"role": "user", "content": [{"text": "What is the capital of Ireland?"}]}],
                         "inferenceConfig": {"maxTokens": 256}}
                        """)));
    }

    @Test
    void mergesTurnsFromOneRoleAndLeavesOutBlankTextAndAZeroTokenLimit() {
        serviceProvider.getService(config()).generate(request(
                List.of(OpikUserMessage.from("First question."), OpikUserMessage.from("  "),
                        OpikUserMessage.from("Second question."),
                        AssistantMessage.builder().content("An answer.").build()),
                0), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(CONVERSE_PATH)).withRequestBody(equalToJson("""
                {"messages": [
                  {"role": "user", "content": [{"text": "First question."}, {"text": "Second question."}]},
                  {"role": "assistant", "content": [{"text": "An answer."}]}]}
                """)));
    }

    @Test
    void sendsAnInlineImageAsBytesAndAWebImageAsText() {
        var message = OpikUserMessage.builder()
                .addText("Describe both.")
                .addImageUrl("data:image/png;base64,iVBORw0KGgo=")
                .addImageUrl("https://example.com/cat.png")
                .build();

        serviceProvider.getService(config()).generate(request(List.of(message), 100), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(CONVERSE_PATH)).withRequestBody(equalToJson("""
                {"messages": [{"role": "user", "content": [
                  {"text": "Describe both."},
                  {"image": {"format": "png", "source": {"bytes": "iVBORw0KGgo="}}},
                  {"text": "[Image: https://example.com/cat.png]"}]}],
                 "inferenceConfig": {"maxTokens": 100}}
                """)));
    }

    @Test
    void streamsTheTextThenAFinalChunkWithTheFinishReasonAndUsage() throws Exception {
        bedrock.stubFor(post(urlEqualTo(STREAM_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/vnd.amazon.eventstream")
                .withBody(concat(
                        event("messageStart", "{\"role\": \"assistant\"}"),
                        event("contentBlockDelta", "{\"contentBlockIndex\": 0, \"delta\": {\"text\": \"Dub\"}}"),
                        event("contentBlockDelta", "{\"contentBlockIndex\": 0, \"delta\": {\"text\": \"lin.\"}}"),
                        event("contentBlockStop", "{\"contentBlockIndex\": 0}"),
                        event("messageStop", "{\"stopReason\": \"max_tokens\"}"),
                        event("metadata", "{\"usage\": {\"inputTokens\": 12, \"outputTokens\": 3,"
                                + " \"totalTokens\": 15}, \"metrics\": {\"latencyMs\": 300}}")))));
        var stream = stream();

        assertThat(stream.done().get(10, TimeUnit.SECONDS)).isNull();
        assertThat(stream.chunks()).extracting(chunk -> chunk.choices().getFirst().delta().content())
                .containsExactly("Dub", "lin.", "");
        var last = stream.chunks().getLast();
        assertThat(last.choices().getFirst().finishReason()).isEqualTo("length");
        assertThat(last.usage().totalTokens()).isEqualTo(15);
    }

    @Test
    void reportsAnExceptionSentInsideTheStreamWithItsStatusAndMessage() {
        bedrock.stubFor(post(urlEqualTo(STREAM_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/vnd.amazon.eventstream")
                .withBody(concat(
                        event("contentBlockDelta", "{\"contentBlockIndex\": 0, \"delta\": {\"text\": \"Dub\"}}"),
                        frame(Map.of(AwsEventStreamParser.MESSAGE_TYPE, "exception",
                                AwsEventStreamParser.EXCEPTION_TYPE, "throttlingException"),
                                "{\"message\": \"Too many requests, please wait before trying again.\"}")))));
        var stream = stream();

        assertThat(stream.done()).failsWithin(10, TimeUnit.SECONDS).withThrowableThat()
                .havingRootCause().isInstanceOf(HttpException.class);
        var error = serviceProvider.getService(config())
                .getLlmProviderError(stream.done().exceptionNow());
        assertThat(error).hasValueSatisfying(message -> {
            assertThat(message.getCode()).isEqualTo(429);
            assertThat(message.getMessage()).isEqualTo("Too many requests, please wait before trying again.");
        });
    }

    @Test
    void reportsAFrameWithABadChecksumAsAnError() {
        var corrupt = event("contentBlockDelta", "{\"contentBlockIndex\": 0, \"delta\": {\"text\": \"Dub\"}}");
        corrupt[corrupt.length - 1] ^= 1;
        bedrock.stubFor(post(urlEqualTo(STREAM_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "application/vnd.amazon.eventstream")
                .withBody(corrupt)));
        var stream = stream();

        assertThat(stream.done()).failsWithin(10, TimeUnit.SECONDS);
        assertThat(stream.chunks()).isEmpty();
    }

    @Test
    void mapsBedrocksErrorBodyToItsStatusAndMessage() {
        bedrock.stubFor(post(urlEqualTo(CONVERSE_PATH)).willReturn(aResponse().withStatus(400)
                .withBody("{\"message\": \"Malformed input request, please reformat your input and try again.\"}")));
        var service = serviceProvider.getService(config());

        var thrown = catchThrowable(
                () -> service.generate(request(List.of(OpikUserMessage.from("Hi")), 10), "workspace"));

        assertThat(service.getLlmProviderError(thrown)).hasValueSatisfying(message -> {
            assertThat(message.getCode()).isEqualTo(400);
            assertThat(message.getMessage())
                    .isEqualTo("Malformed input request, please reformat your input and try again.");
        });
    }

    @Test
    void judgesThroughConverseWithTheBareModelIdAndTheRulesTemperature() {
        var judge = serviceProvider.getLanguageModel(config(),
                LlmAsJudgeModelParameters.builder().name(OPIK_MODEL).temperature(0.0).build());

        var response = judge.chat(ChatRequest.builder()
                .messages(SystemMessage.from("You grade answers."), UserMessage.from("Is Dublin right?"))
                .build());

        assertThat(response.aiMessage().text()).isEqualTo("Dublin.");
        assertThat(response.tokenUsage().totalTokenCount()).isEqualTo(15);
        bedrock.verify(1, postRequestedFor(urlEqualTo(CONVERSE_PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withRequestBody(equalToJson("""
                        {"system": [{"text": "You grade answers."}],
                         "messages": [{"role": "user", "content": [{"text": "Is Dublin right?"}]}],
                         "inferenceConfig": {"temperature": 0.0}}
                        """)));
    }

    @Test
    void sendsAFetchedTokenAsBearerInTokenMode() {
        when(authTokenProvider.bearer(any(), any(), any())).thenReturn("fetched-token");
        var config = config().toBuilder()
                .apiKey(null)
                .authConfig(EncryptedAuthConfig.fromCiphertext("ciphertext"))
                .providerId(UUID.randomUUID())
                .workspaceId("workspace")
                .build();

        serviceProvider.getService(config).generate(request(List.of(OpikUserMessage.from("Hi")), 10), "workspace");

        bedrock.verify(1, postRequestedFor(urlEqualTo(CONVERSE_PATH))
                .withHeader("Authorization", equalTo("Bearer fetched-token")));
    }

    @Test
    void keepsAnInferenceProfileArnInOnePathSegment() {
        var arn = "arn:aws:bedrock:us-east-2:123456789012:application-inference-profile/abc123";
        var arnPath = "/model/arn:aws:bedrock:us-east-2:123456789012:application-inference-profile%2Fabc123/converse";
        bedrock.stubFor(post(urlEqualTo(arnPath)).willReturn(okJson(CONVERSE_RESPONSE)));

        var response = serviceProvider.getService(config())
                .generate(ChatCompletionRequest.builder().model("custom-llm/" + PROVIDER_NAME + "/" + arn)
                        .messages(OpikUserMessage.from("Hi")).build(), "workspace");

        assertThat(response.choices().getFirst().message().content()).isEqualTo("Dublin.");
    }

    @Test
    void doesNotTakeOverAChatCompletionsBedrockProvider() {
        var config = config().toBuilder().configuration(Map.of("provider_name", PROVIDER_NAME)).build();

        assertThatThrownBy(() -> serviceProvider.getService(config)
                .generate(request(List.of(OpikUserMessage.from("Hi")), 10), "workspace"))
                .isInstanceOf(RuntimeException.class);
        assertThat(bedrock.findAll(postRequestedFor(urlEqualTo(CONVERSE_PATH)))).isEmpty();
    }

    private record Stream(List<ChatCompletionResponse> chunks, CompletableFuture<Void> done) {
    }

    private Stream stream() {
        var chunks = new ArrayList<ChatCompletionResponse>();
        var done = new CompletableFuture<Void>();
        serviceProvider.getService(config()).generateStream(
                ChatCompletionRequest.builder().from(request(List.of(OpikUserMessage.from("Hi")), 10))
                        .stream(true).build(),
                "workspace", chunks::add, () -> done.complete(null), done::completeExceptionally);
        return new Stream(chunks, done);
    }

    private LlmProviderClientApiConfig config() {
        return LlmProviderClientApiConfig.builder()
                .provider(LlmProvider.BEDROCK)
                .apiKey(API_KEY)
                .baseUrl(LlmTestClients.baseUrlOf(bedrock) + "/")
                .configuration(Map.of(
                        "provider_name", PROVIDER_NAME,
                        "models", OPIK_MODEL,
                        BedrockApi.CONFIG_KEY, "converse"))
                .headers(Map.of())
                .build();
    }

    private static ChatCompletionRequest request(List<Message> messages, int maxCompletionTokens) {
        return ChatCompletionRequest.builder()
                .model(OPIK_MODEL)
                .maxCompletionTokens(maxCompletionTokens)
                .messages(messages)
                .build();
    }

    private static byte[] event(String type, String payload) {
        var headers = new LinkedHashMap<String, String>();
        headers.put(AwsEventStreamParser.EVENT_TYPE, type);
        headers.put(":content-type", "application/json");
        headers.put(AwsEventStreamParser.MESSAGE_TYPE, "event");
        return frame(headers, payload);
    }

    private static byte[] frame(Map<String, String> headers, String payload) {
        var headerBytes = new ByteArrayOutputStream();
        headers.forEach((name, value) -> {
            var nameBytes = name.getBytes(StandardCharsets.UTF_8);
            var valueBytes = value.getBytes(StandardCharsets.UTF_8);
            headerBytes.write(nameBytes.length);
            headerBytes.writeBytes(nameBytes);
            headerBytes.write(7);
            headerBytes.write(valueBytes.length >> 8);
            headerBytes.write(valueBytes.length);
            headerBytes.writeBytes(valueBytes);
        });
        var headersArray = headerBytes.toByteArray();
        var payloadArray = payload.getBytes(StandardCharsets.UTF_8);
        int totalLength = 12 + headersArray.length + payloadArray.length + 4;
        var buffer = ByteBuffer.allocate(totalLength);
        buffer.putInt(totalLength).putInt(headersArray.length);
        buffer.putInt(crc(buffer.array(), 8));
        buffer.put(headersArray).put(payloadArray);
        buffer.putInt(crc(buffer.array(), totalLength - 4));
        return buffer.array();
    }

    private static int crc(byte[] bytes, int length) {
        var crc = new CRC32();
        crc.update(bytes, 0, length);
        return (int) crc.getValue();
    }

    private static byte[] concat(byte[]... frames) {
        var out = new ByteArrayOutputStream();
        for (byte[] frame : frames) {
            out.writeBytes(frame);
        }
        return out.toByteArray();
    }
}
