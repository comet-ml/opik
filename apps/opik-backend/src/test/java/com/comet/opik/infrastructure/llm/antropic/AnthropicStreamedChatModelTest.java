package com.comet.opik.infrastructure.llm.antropic;

import com.comet.opik.api.evaluators.LlmAsJudgeModelParameters;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchema;
import com.comet.opik.api.evaluators.LlmAsJudgeOutputSchemaType;
import com.comet.opik.api.resources.v1.events.OnlineScoringEngine;
import com.comet.opik.domain.evaluation.LlmUsageExtractor;
import com.comet.opik.domain.llm.structuredoutput.ToolCallingStrategy;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.exception.InternalServerException;
import dev.langchain4j.exception.InvalidRequestException;
import dev.langchain4j.exception.LangChain4jException;
import dev.langchain4j.exception.NonRetriableException;
import dev.langchain4j.exception.TimeoutException;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.output.FinishReason;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.StringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class AnthropicStreamedChatModelTest {

    private static final String MESSAGES_PATH = "/v1/messages";
    private static final String SCORE_NAME = "Correctness";
    private static final List<LlmAsJudgeOutputSchema> SCHEMA = List.of(LlmAsJudgeOutputSchema.builder()
            .name(SCORE_NAME)
            .type(LlmAsJudgeOutputSchemaType.INTEGER)
            .description("1 if correct")
            .build());
    private static final String JUDGE_ANSWER = """
            {"Correctness": {"score": 1, "reason": "Paris is the capital of France"}}""";

    private WireMockServer wireMock;
    private HttpServer stallingServer;
    private CountDownLatch releaseStallingServer;

    @BeforeAll
    void startWireMock() {
        wireMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wireMock.start();
    }

    @AfterAll
    void stopWireMock() {
        wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    @AfterEach
    void stopStallingServer() {
        if (stallingServer != null) {
            releaseStallingServer.countDown();
            stallingServer.stop(0);
            stallingServer = null;
        }
    }

    @Test
    void collectsTheStreamIntoOneResponseThatHoldsOnlyTheJudgeAnswer() {
        stubStream(messageStart(120, 40)
                + thinkingBlock(0)
                + textBlock(1, JUDGE_ANSWER.substring(0, 20), JUDGE_ANSWER.substring(20))
                + messageEnd("end_turn", 6150));

        var response = judge(wireMockConfig(), "claude-sonnet-5", "{\"output_config\": {\"effort\": \"max\"}}")
                .chat(judgeRequest());

        assertThat(response.aiMessage().text()).isEqualTo(JUDGE_ANSWER);
        assertThat(response.aiMessage().thinking()).isNull();
        assertThat(response.finishReason()).isEqualTo(FinishReason.STOP);
        assertThat(LlmUsageExtractor.toUsageMap(response))
                .containsEntry("prompt_tokens", 120)
                .containsEntry("completion_tokens", 6150)
                .containsEntry("cache_read_input_tokens", 40);
        assertThat(OnlineScoringEngine.toFeedbackScores(response, SCHEMA).scores())
                .singleElement()
                .satisfies(score -> {
                    assertThat(score.name()).isEqualTo(SCORE_NAME);
                    assertThat(score.value()).isEqualByComparingTo(BigDecimal.ONE);
                });
    }

    @Test
    void reportsMaxTokensWhenThinkingUsesUpTheWholeLimit() {
        stubStream(messageStart(120, 0) + thinkingBlock(0) + messageEnd("max_tokens", 16000));

        var response = judge(wireMockConfig(), "claude-sonnet-5", "{\"output_config\": {\"effort\": \"max\"}}")
                .chat(judgeRequest());

        assertThat(response.aiMessage().text()).isNull();
        assertThat(response.finishReason()).isEqualTo(FinishReason.LENGTH);
        assertThat(LlmUsageExtractor.toUsageMap(response)).containsEntry("completion_tokens", 16000);
        assertThat(OnlineScoringEngine.toFeedbackScores(response, SCHEMA).scores()).isEmpty();
    }

    @ParameterizedTest(name = "{0} with custom_parameters {1}")
    @CsvSource(delimiter = '|', value = {
            "claude-sonnet-5           | {\"output_config\": {\"effort\": \"max\", \"future_option\": true}} |  | 16000 | ",
            "claude-opus-4-8           | {\"thinking\": {\"type\": \"adaptive\"}, \"output_config\": {\"effort\": \"high\"}} | {\"type\": \"adaptive\"} | 16000 | ",
            "claude-sonnet-5           | {\"max_tokens\": 32000, \"output_config\": {\"effort\": \"max\"}} |  | 32000 | ",
            "claude-sonnet-5           | {\"thinking\": {\"type\": \"disabled\"}, \"output_config\": {\"effort\": \"low\"}} | {\"type\": \"disabled\"} | 4096 | ",
            "claude-sonnet-4-6         | {\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 2048}, \"max_tokens\": 3000} | {\"type\": \"enabled\", \"budget_tokens\": 2048} | 3000 | ",
            "claude-sonnet-4-6         | {\"thinking\": {\"type\": \"enabled\", \"budget_tokens\": 20000}} | {\"type\": \"enabled\", \"budget_tokens\": 20000} | 24096 | ",
            "claude-haiku-4-5-20251001 | {}                                                       |  | 4096  | 0.3"})
    void streamsTheJudgeRequestWithEverythingItCarries(String model, String customParameters,
            String expectedThinking, int expectedMaxTokens, Double expectedTemperature) {
        stubStream(messageStart(10, 0) + textBlock(0, JUDGE_ANSWER) + messageEnd("end_turn", 30));

        judge(wireMockConfig(), model, customParameters).chat(judgeRequest());

        var rawBody = sentBody();
        var body = JsonUtils.getJsonNodeFromString(rawBody);
        var ruleOutputConfig = JsonUtils.getJsonNodeFromString(customParameters).path("output_config");
        assertThat(body.path("stream").asBoolean()).isTrue();
        assertThat(StringUtils.countMatches(rawBody, "\"output_config\"")).isEqualTo(1);
        assertThat(body.path("output_config").path("format").path("type").asText()).isEqualTo("json_schema");
        assertThat(body.path("output_config").path("format").path("schema").path("properties").has(SCORE_NAME))
                .isTrue();
        ruleOutputConfig.properties().forEach(
                field -> assertThat(body.path("output_config").path(field.getKey())).isEqualTo(field.getValue()));
        assertThat(body.path("max_tokens").asInt()).isEqualTo(expectedMaxTokens);
        if (expectedThinking == null) {
            assertThat(body.has("thinking")).isFalse();
        } else {
            assertThat(body.path("thinking")).isEqualTo(JsonUtils.getJsonNodeFromString(expectedThinking));
        }
        if (expectedTemperature == null) {
            assertThat(body.has("temperature")).isFalse();
        } else {
            assertThat(body.path("temperature").asDouble()).isEqualTo(expectedTemperature);
        }
    }

    @Test
    void mapsAnErrorStatusToTheExceptionTheRetryPolicyReads() {
        wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH)).willReturn(aResponse()
                .withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"type\":\"error\",\"error\":{\"type\":\"invalid_request_error\",\"message\":\"bad\"}}")));

        var judge = judge(wireMockConfig(), "claude-sonnet-5", "{}");

        assertThatThrownBy(() -> judge.chat(judgeRequest()))
                .isInstanceOf(InvalidRequestException.class)
                .isInstanceOf(NonRetriableException.class);
    }

    @Test
    void mapsAnOverloadedStatusToARetriableException() {
        wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH)).willReturn(aResponse()
                .withStatus(529)
                .withHeader("Content-Type", "application/json")
                .withBody(
                        "{\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"Overloaded\"}}")));

        var judge = judge(wireMockConfig(), "claude-sonnet-5", "{}");

        assertThatThrownBy(() -> judge.chat(judgeRequest()))
                .isInstanceOf(InternalServerException.class);
    }

    @Test
    void failsOnAnErrorEventInTheMiddleOfTheStream() {
        stubStream(messageStart(10, 0) + thinkingBlock(0) + event("error", Map.of("type", "error",
                "error", Map.of("type", "overloaded_error", "message", "Overloaded"))));

        var judge = judge(wireMockConfig(), "claude-sonnet-5", "{}");

        assertThatThrownBy(() -> judge.chat(judgeRequest()))
                .isInstanceOf(LangChain4jException.class)
                .isNotInstanceOf(NonRetriableException.class)
                .hasMessageContaining("Overloaded");
    }

    @Test
    void failsTheAttemptAndHangsUpWhenTheStreamGoesQuiet() throws Exception {
        var clientHungUp = startStallingServer(messageStart(10, 0) + thinkingStart(0), 1_500);
        var judge = judge(stallingServerConfig(), timeouts(Duration.milliseconds(300), Duration.seconds(10)));

        var start = System.nanoTime();
        assertThatThrownBy(() -> judge.chat(judgeRequest()))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("sent nothing for PT0.3S");

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3_000);
        assertThat(clientHungUp.await(5, TimeUnit.SECONDS)).isTrue();
    }

    @Test
    void failsTheAttemptAndHangsUpWhenTheAnswerRunsPastTheCallTimeout() throws Exception {
        var clientHungUp = startStallingServer(messageStart(10, 0) + thinkingStart(0), 0);
        var judge = judge(stallingServerConfig(), timeouts(Duration.seconds(5), Duration.milliseconds(500)));

        var start = System.nanoTime();
        assertThatThrownBy(() -> judge.chat(judgeRequest()))
                .isInstanceOf(TimeoutException.class)
                .hasMessageContaining("did not finish the answer within PT0.5S");

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(3_000);
        assertThat(clientHungUp.await(5, TimeUnit.SECONDS)).isTrue();
    }

    private ChatModel judge(LlmProviderClientApiConfig apiConfig, String model, String customParameters) {
        return new AnthropicClientGenerator(new LlmProviderClientConfig()).generateChat(apiConfig,
                LlmAsJudgeModelParameters.builder()
                        .name(model)
                        .temperature(0.3)
                        .customParameters(JsonUtils.getJsonNodeFromString(customParameters))
                        .build());
    }

    private ChatModel judge(LlmProviderClientApiConfig apiConfig, LlmProviderClientConfig clientConfig) {
        return new AnthropicClientGenerator(clientConfig).generateChat(apiConfig,
                LlmAsJudgeModelParameters.builder().name("claude-sonnet-5").build());
    }

    private LlmProviderClientConfig timeouts(Duration idleTimeout, Duration callTimeout) {
        var clientConfig = new LlmProviderClientConfig();
        clientConfig.setReadTimeout(idleTimeout);
        clientConfig.setJudgeCallTimeout(callTimeout);
        return clientConfig;
    }

    private ChatRequest judgeRequest() {
        List<ChatMessage> messages = List.of(UserMessage.from("Is Paris the capital of France?"));
        var builder = ChatRequest.builder().messages(messages);
        return new ToolCallingStrategy().apply(builder, messages, SCHEMA).build();
    }

    private void stubStream(String events) {
        wireMock.stubFor(post(urlPathEqualTo(MESSAGES_PATH)).willReturn(aResponse()
                .withHeader("Content-Type", "text/event-stream")
                .withBody(events)));
    }

    private String sentBody() {
        var sent = wireMock.findAll(postRequestedFor(urlPathEqualTo(MESSAGES_PATH)));
        assertThat(sent).hasSize(1);
        return sent.getFirst().getBodyAsString();
    }

    private LlmProviderClientApiConfig wireMockConfig() {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl(wireMock.baseUrl() + "/v1/")
                .build();
    }

    private LlmProviderClientApiConfig stallingServerConfig() {
        return LlmProviderClientApiConfig.builder()
                .apiKey("test-key")
                .baseUrl("http://localhost:%d/v1/".formatted(stallingServer.getAddress().getPort()))
                .build();
    }

    // WireMock spaces a dribbled body evenly, so it cannot send the first events and then go quiet. This server
    // sends them, says nothing for quietMillis, then pings until a write fails because the client hung up.
    private CountDownLatch startStallingServer(String firstEvents, long quietMillis) throws IOException {
        var clientHungUp = new CountDownLatch(1);
        releaseStallingServer = new CountDownLatch(1);
        var release = releaseStallingServer;
        stallingServer = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        stallingServer.createContext(MESSAGES_PATH, exchange -> {
            exchange.getResponseHeaders().add("Content-Type", "text/event-stream");
            exchange.sendResponseHeaders(200, 0);
            try (var body = exchange.getResponseBody()) {
                body.write(firstEvents.getBytes(StandardCharsets.UTF_8));
                body.flush();
                if (release.await(quietMillis, TimeUnit.MILLISECONDS)) {
                    return;
                }
                var ping = event("ping", Map.of("type", "ping")).getBytes(StandardCharsets.UTF_8);
                while (!release.await(50, TimeUnit.MILLISECONDS)) {
                    body.write(ping);
                    body.flush();
                }
            } catch (IOException e) {
                clientHungUp.countDown();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
        stallingServer.start();
        return clientHungUp;
    }

    private static String event(String type, Map<String, ?> data) {
        return "event: %s\ndata: %s\n\n".formatted(type, JsonUtils.valueToTree(data));
    }

    private static String messageStart(int inputTokens, int cacheReadInputTokens) {
        return event("message_start", Map.of("type", "message_start", "message", Map.of(
                "id", "msg_1",
                "type", "message",
                "role", "assistant",
                "model", "claude-sonnet-5",
                "content", List.of(),
                "usage", Map.of("input_tokens", inputTokens, "cache_read_input_tokens", cacheReadInputTokens,
                        "output_tokens", 1))));
    }

    private static String thinkingStart(int index) {
        return event("content_block_start", Map.of("type", "content_block_start", "index", index,
                "content_block", Map.of("type", "thinking", "thinking", "")));
    }

    private static String thinkingBlock(int index) {
        return thinkingStart(index)
                + event("content_block_delta", Map.of("type", "content_block_delta", "index", index,
                        "delta", Map.of("type", "thinking_delta", "thinking", "The answer says Paris. That is right.")))
                + event("content_block_delta", Map.of("type", "content_block_delta", "index", index,
                        "delta", Map.of("type", "signature_delta", "signature", "c2lnbmF0dXJl")))
                + event("content_block_stop", Map.of("type", "content_block_stop", "index", index));
    }

    private static String textBlock(int index, String... chunks) {
        var block = new StringBuilder(event("content_block_start", Map.of("type", "content_block_start",
                "index", index, "content_block", Map.of("type", "text", "text", ""))));
        for (var chunk : chunks) {
            block.append(event("content_block_delta", Map.of("type", "content_block_delta", "index", index,
                    "delta", Map.of("type", "text_delta", "text", chunk))));
        }
        return block.append(event("content_block_stop", Map.of("type", "content_block_stop", "index", index)))
                .toString();
    }

    private static String messageEnd(String stopReason, int outputTokens) {
        return event("message_delta", Map.of("type", "message_delta", "delta", Map.of("stop_reason", stopReason),
                "usage", Map.of("output_tokens", outputTokens)))
                + event("message_stop", Map.of("type", "message_stop"));
    }
}
