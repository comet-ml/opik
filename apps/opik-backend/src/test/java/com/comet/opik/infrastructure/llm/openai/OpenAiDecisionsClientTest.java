package com.comet.opik.infrastructure.llm.openai;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.LlmTestClients;
import com.comet.opik.infrastructure.llm.decisions.DecisionsQuestion;
import com.comet.opik.infrastructure.llm.decisions.DecisionsRequest;
import com.comet.opik.infrastructure.llm.decisions.DecisionsResponse;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ServerErrorException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OpenAiDecisionsClientTest {

    private static final String DECISIONS_PATH = "/decisions";
    private static final String API_KEY = "test-key";
    // The SDK honours this header instead of its own backoff, so retried calls stay fast.
    private static final String RETRY_AFTER_MS = "retry-after-ms";

    private static final String DECISION = """
            {
              "model": "gpt-6-luna-2026-09-22",
              "answers": [
                {"type": "predicate", "name": "q0", "probability": 0.93},
                {"type": "refusal", "name": "q1"}
              ],
              "usage": {
                "input_tokens": 212,
                "input_tokens_details": {"cached_tokens": 64, "cache_write_tokens": 0},
                "output_tokens": 0,
                "output_tokens_details": {"reasoning_tokens": 0},
                "total_tokens": 212
              }
            }
            """;

    private final OpenAiDecisionsClient client = new OpenAiDecisionsClient(
            new OpenAIClientGenerator(TestConfigUtils.loadConfigTest().getLlmProviderClient(),
                    LlmTestClients.RESPONSES_API_HTTP_CLIENT, LlmTestClients.LLM_HTTP_CLIENT_BUILDERS),
            new ProviderErrorMessageValidator(TestConfigUtils.loadConfigTest().getLlmProviderClient()));

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

    @Test
    void sendsPredicateQuestionsToTheBaseUrlAndParsesAnswers() {
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).willReturn(json(200, DECISION)));

        var response = client.decide(request(), config()).block();

        openAi.verify(1, postRequestedFor(urlPathEqualTo(DECISIONS_PATH))
                .withHeader("Authorization", equalTo("Bearer " + API_KEY))
                .withHeader("X-Team", equalTo("evals"))
                .withRequestBody(equalToJson("""
                        {
                          "model": "gpt-6-luna",
                          "input": "Question: hi\\nAnswer: hello",
                          "questions": [
                            {"type": "predicate", "name": "q0", "instructions": "Is the answer relevant?"},
                            {"type": "predicate", "name": "q1", "instructions": "Is the answer polite?"}
                          ]
                        }
                        """)));
        assertThat(response).isEqualTo(DecisionsResponse.builder()
                .model("gpt-6-luna-2026-09-22")
                // The refusal has no probability, so it is left out and its score reported as unreadable.
                .answers(Map.of("q0", DecisionsResponse.Answer.builder()
                        .type(OpenAiDecisionsClient.PREDICATE_TYPE).noul(0.93).build()))
                .usage(DecisionsResponse.Usage.builder()
                        .inputTokens(212)
                        .outputTokens(0)
                        .cachedInputTokens(64)
                        .build())
                .build());
    }

    @Test
    void clientErrorCarriesStatusAndDocumentedMessageWithoutRetry() {
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).willReturn(json(400, """
                {"error": {"message": "Too many questions", "type": "invalid_request_error"}}
                """)));

        assertThatThrownBy(() -> client.decide(request(), config()).block())
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(400))
                .hasMessageContaining("Too many questions");
        openAi.verify(1, postRequestedFor(urlPathEqualTo(DECISIONS_PATH)));
    }

    @Test
    void undocumentedErrorKeepsStatusButNotTheUpstreamText() {
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).willReturn(json(404, """
                {"error": {"message": "internal admin page", "type": "proxy_error"}}
                """)));

        assertThatThrownBy(() -> client.decide(request(), config()).block())
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(404))
                .hasMessageContaining(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)
                .hasMessageNotContaining("internal admin page");
    }

    @Test
    void documentedErrorWithMoreThanPlainTextKeepsStatusButNotTheUpstreamText() {
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).willReturn(json(400, """
                {"error": {"message": "Bad question\\nINFO forged log line", "type": "invalid_request_error"}}
                """)));

        assertThatThrownBy(() -> client.decide(request(), config()).block())
                .isInstanceOfSatisfying(ClientErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(400))
                .hasMessageContaining(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)
                .hasMessageNotContaining("forged log line");
    }

    @Test
    void rateLimitIsRetriedThenReturnsTheResponse() {
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).inScenario("rate limit")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(json(429, """
                        {"error": {"message": "Slow down", "type": "rate_limit_error"}}
                        """).withHeader(RETRY_AFTER_MS, "1"))
                .willSetStateTo("recovered"));
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).inScenario("rate limit")
                .whenScenarioStateIs("recovered")
                .willReturn(json(200, DECISION)));

        var response = client.decide(request(), config()).block();

        assertThat(response.answers()).containsOnlyKeys("q0");
        openAi.verify(2, postRequestedFor(urlPathEqualTo(DECISIONS_PATH)));
    }

    @Test
    void serverErrorCarriesStatus() {
        openAi.stubFor(post(urlPathEqualTo(DECISIONS_PATH)).willReturn(json(500, """
                {"error": {"message": "Boom", "type": "server_error"}}
                """).withHeader(RETRY_AFTER_MS, "1")));

        assertThatThrownBy(() -> client.decide(request(), config()).block())
                .isInstanceOfSatisfying(ServerErrorException.class,
                        exception -> assertThat(exception.getResponse().getStatus()).isEqualTo(500));
    }

    private LlmProviderClientApiConfig config() {
        return LlmProviderClientApiConfig.builder()
                .apiKey(API_KEY)
                .headers(Map.of("X-Team", "evals"))
                .baseUrl(LlmTestClients.baseUrlOf(openAi))
                .build();
    }

    private static DecisionsRequest request() {
        var questions = new LinkedHashMap<String, DecisionsQuestion>();
        questions.put("q0", DecisionsQuestion.noul("Is the answer relevant?"));
        questions.put("q1", DecisionsQuestion.noul("Is the answer polite?"));
        return DecisionsRequest.builder()
                .model("gpt-6-luna")
                .state("Question: hi\nAnswer: hello")
                .questions(questions)
                .build();
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body);
    }
}
