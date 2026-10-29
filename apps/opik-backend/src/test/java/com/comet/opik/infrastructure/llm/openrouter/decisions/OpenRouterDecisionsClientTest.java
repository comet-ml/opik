package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.utils.RetryUtils;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenRouterDecisionsClientTest {

    private static final String PATH = "/api/alpha/decisions";
    private static final int MAX_ATTEMPTS = 2;

    // Real response and error bodies captured from the OpenRouter Decisions API.
    private static final String SUCCESS_BODY = """
            {"model":"typesafe/jev-1.13-20260917","answers":{"answer_relevant":{"type":"noul","noul":0.99},
            "answer_correct":{"type":"noul","noul":1}},"usage":{"input_tokens":340,"output_tokens":40,
            "cost":0.00001428},"id":"gen-dec-1790253060-8A0giztGJbuzG2EKEzSF","provider":"TypeSafe"}
            """;
    private static final String ERROR_BODY = """
            {"error":{"message":"At least one question is required","code":400},"user_id":"org_1"}
            """;

    private static final ProviderBaseUrlGuard RELAXED_GUARD = ProviderBaseUrlGuard.builder()
            .mode(DestinationGuard.Mode.RELAXED)
            .scheme(DestinationGuard.Scheme.PLAINTEXT_OR_TLS)
            .build();

    private WireMockUtils.WireMockRuntime wireMock;
    private LlmProviderClientConfig clientConfig;
    private OpenRouterDecisionsClient decisionsClient;
    private String apiKey;

    @BeforeAll
    void setUpAll() {
        wireMock = WireMockUtils.startWireMock();
        clientConfig = new LlmProviderClientConfig();
        clientConfig.setOpenRouterUrl(wireMock.runtimeInfo().getHttpBaseUrl() + "/api/v1");
        clientConfig.setMaxAttempts(MAX_ATTEMPTS);
        clientConfig.setDelayMillis(1);
        clientConfig.setConnectTimeout(Duration.seconds(5));
        clientConfig.setReadTimeout(Duration.seconds(5));
        decisionsClient = newDecisionsClient(RELAXED_GUARD);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        apiKey = RandomStringUtils.secure().nextAlphanumeric(32);
    }

    @Test
    void sendsRequestWithApiKeyAndParsesAnswers() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));
        var questions = new LinkedHashMap<String, DecisionsQuestion>();
        questions.put("answer_relevant", DecisionsQuestion.noul("Does the answer respond to the question?"));
        questions.put("answer_correct", DecisionsQuestion.noul("Is the answer correct?"));
        var request = DecisionsRequest.builder()
                .model("~typesafe/jev-latest")
                .state("Question: What is the capital of France?\nAnswer: Paris")
                .questions(questions)
                .build();

        var response = decisionsClient.decide(request, apiConfig(Map.of("X-Custom", "value"))).block();

        wireMock.server().verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + apiKey))
                .withHeader("X-Custom", equalTo("value"))
                .withRequestBody(equalToJson("""
                        {"model":"~typesafe/jev-latest",
                         "state":"Question: What is the capital of France?\\nAnswer: Paris",
                         "questions":{
                           "answer_relevant":{"type":"noul","instructions":"Does the answer respond to the question?"},
                           "answer_correct":{"type":"noul","instructions":"Is the answer correct?"}}}
                        """)));
        assertThat(response.model()).isEqualTo("typesafe/jev-1.13-20260917");
        assertThat(response.answers().get("answer_relevant").noul()).isEqualTo(0.99);
        // Whole-number probabilities arrive as integers.
        assertThat(response.answers().get("answer_correct").noul()).isEqualTo(1.0);
        assertThat(response.usage().inputTokens()).isEqualTo(340);
        assertThat(response.usage().cost()).isEqualByComparingTo("0.00001428");
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 402})
    void clientErrorCarriesStatusAndUpstreamMessageWithoutRetry(int status) {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(ERROR_BODY)));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(ClientErrorException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(status))
                .hasMessageContaining("At least one question is required");
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void serverErrorCarriesStatus() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(ServerErrorException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(500))
                .hasMessageContaining("boom");
    }

    @Test
    void timeoutIsNotRetriedInProcess() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(524).withBody("timeout")));

        // The timed-out call may have been billed: fail once and leave any retry to the consumer's redelivery.
        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(ServerErrorException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(524));
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void redirectCarriesStatus() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(308).withHeader("Location", "https://example.com")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(WebApplicationException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(308))
                .hasMessageContaining("status '308'");
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 529})
    void transientErrorIsRetriedInProcess(int status) {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(status).withBody("busy")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOf(RetryUtils.RetryableHttpException.class);
        wireMock.server().verify(MAX_ATTEMPTS + 1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void transientErrorThenSuccessReturnsTheResponse() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(429))
                .willSetStateTo("recovered"));
        wireMock.server().stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));

        var response = decisionsClient.decide(request(), apiConfig(Map.of())).block();

        assertThat(response.answers()).containsKeys("answer_relevant", "answer_correct");
    }

    @Test
    void providerBaseUrlReplacesTheConfiguredUrl() {
        var proxyPath = "/proxy/alpha/decisions";
        wireMock.server().stubFor(post(urlEqualTo(proxyPath))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(apiKey)
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl() + "/proxy/v1/")
                .build();

        decisionsClient.decide(request(), config).block();

        wireMock.server().verify(1, postRequestedFor(urlEqualTo(proxyPath)));
        wireMock.server().verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void baseUrlWithoutV1FailsWithoutCallingAnyHost() {
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(apiKey)
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl() + "/proxy")
                .build();

        assertThatThrownBy(() -> decisionsClient.decide(request(), config).block())
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("must end with '/v1'");
        assertThat(wireMock.server().getAllServeEvents()).isEmpty();
    }

    @Test
    void strictGuardRefusesAPrivateAddressAtConnectTime() {
        var strictClient = newDecisionsClient(RELAXED_GUARD.toBuilder().mode(DestinationGuard.Mode.STRICT).build());
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(apiKey)
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl() + "/api/v1")
                .build();

        assertThatThrownBy(() -> strictClient.decide(request(), config).block())
                .matches(DestinationGuardDns::isRefused);
        assertThat(wireMock.server().getAllServeEvents()).isEmpty();
    }

    @ParameterizedTest
    @CsvSource({
            "https://openrouter.ai/api/v1, https://openrouter.ai/api/alpha/decisions",
            "https://eu.openrouter.ai/api/v1/, https://eu.openrouter.ai/api/alpha/decisions",
            "https://my-proxy.example.com/openrouter/v1, https://my-proxy.example.com/openrouter/alpha/decisions"})
    void decisionsUrlReplacesTheTrailingV1(String openRouterUrl, String expected) {
        assertThat(OpenRouterDecisionsClient.decisionsUrl(openRouterUrl)).isEqualTo(expected);
    }

    private OpenRouterDecisionsClient newDecisionsClient(ProviderBaseUrlGuard guard) {
        return new OpenRouterDecisionsClient(new RetriableHttpClient(TestHttpClientUtils.client()), clientConfig,
                guard);
    }

    private DecisionsRequest request() {
        return DecisionsRequest.builder()
                .model("~typesafe/jev-latest")
                .state("state")
                .questions(Map.of("answer_relevant", DecisionsQuestion.noul("Is it relevant?")))
                .build();
    }

    private LlmProviderClientApiConfig apiConfig(Map<String, String> headers) {
        return LlmProviderClientApiConfig.builder()
                .apiKey(apiKey)
                .headers(headers)
                .build();
    }
}
