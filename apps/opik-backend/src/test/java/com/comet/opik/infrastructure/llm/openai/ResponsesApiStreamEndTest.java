package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.google.common.base.Throwables;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionRequest;
import dev.langchain4j.model.openai.internal.chat.ChatCompletionResponse;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

/**
 * A Responses API stream that ends without a final event, such as a 204's empty body, fails the call rather than
 * leaving it unanswered.
 */
class ResponsesApiStreamEndTest {

    private static final String DELTA = """
            event: response.output_text.delta
            data: {"type":"response.output_text.delta","sequence_number":1,"item_id":"msg_1",\
            "output_index":0,"content_index":0,"delta":"ok","logprobs":[]}

            """;

    private WireMockServer openAi;

    @BeforeEach
    void setUp() {
        openAi = LlmTestClients.newLoopbackWireMock();
        openAi.start();
    }

    @AfterEach
    void tearDown() {
        openAi.stop();
    }

    static Stream<Arguments> aStreamWithoutAFinalEventFails() {
        return Stream.of(
                arguments(Named.of("a 204 without a body", 204), null, null),
                arguments(Named.of("an event stream cut short", 200), "text/event-stream", DELTA));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource
    @DisplayName("a Responses API stream that ends without a final event fails")
    void aStreamWithoutAFinalEventFails(int status, String contentType, String body) throws Exception {
        var response = aResponse().withStatus(status);
        if (contentType != null) {
            response = response.withHeader("Content-Type", contentType).withBody(body);
        }
        openAi.stubFor(post(urlPathEqualTo("/v1/responses")).willReturn(response));
        var provider = new LlmProviderOpenAiResponses(
                new OpenAIClientGenerator(TestConfigUtils.loadConfigTest().getLlmProviderClient(),
                        LlmTestClients.RESPONSES_API_HTTP_CLIENT, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
                LlmProviderClientApiConfig.builder()
                        .apiKey(RandomStringUtils.secure().nextAlphanumeric(20))
                        .baseUrl("%s/v1".formatted(LlmTestClients.baseUrlOf(openAi)))
                        .build());
        var actualChunks = new CopyOnWriteArrayList<ChatCompletionResponse>();
        var outcome = new CompletableFuture<Throwable>();

        provider.generateStream(ChatCompletionRequest.builder().model("gpt-4o").addUserMessage("hi").build(),
                "workspace", actualChunks::add, () -> outcome.complete(null), outcome::complete);

        var actualError = outcome.get(10, TimeUnit.SECONDS);
        assertThat(actualError).isNotNull();
        assertThat(Throwables.getCausalChain(actualError))
                .anyMatch(cause -> OpenAiResponsesStreamEnd.NO_FINAL_EVENT.equals(cause.getMessage()));
    }
}
