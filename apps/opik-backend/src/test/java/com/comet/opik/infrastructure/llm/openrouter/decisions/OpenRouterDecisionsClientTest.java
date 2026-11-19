package com.comet.opik.infrastructure.llm.openrouter.decisions;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.domain.llm.ProviderErrorMessageValidator;
import com.comet.opik.infrastructure.LlmProviderClientConfig;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.comet.opik.infrastructure.llm.LlmProviderClientApiConfig;
import com.comet.opik.infrastructure.llm.customllm.ProviderBaseUrlGuard;
import com.comet.opik.infrastructure.llm.customllm.ProviderRequestRefusedException;
import com.comet.opik.infrastructure.llm.decisions.DecisionsQuestion;
import com.comet.opik.infrastructure.llm.decisions.DecisionsRequest;
import com.comet.opik.infrastructure.net.DestinationGuard;
import com.comet.opik.infrastructure.net.DestinationGuardDns;
import com.comet.opik.utils.RetryUtils;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.dropwizard.util.DataSize;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.InternalServerErrorException;
import jakarta.ws.rs.ServerErrorException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.client.Client;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.GZIPOutputStream;

import static com.comet.opik.domain.llm.ChatCompletionService.UNEXPECTED_ERROR_CALLING_LLM_PROVIDER;
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
    private static final int ERROR_BODY_MAX_SIZE = 1024;
    private static final String URL_REFUSED = new ProviderRequestRefusedException(
            ProviderRequestRefusedException.Reason.OPENROUTER_DECISIONS_URL).getMessage();

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
        clientConfig.setProviderErrorMessageMaxLength(500);
        clientConfig.setConnectTimeout(Duration.seconds(5));
        clientConfig.setReadTimeout(Duration.seconds(5));
        clientConfig.setProviderErrorBodyMaxSize(DataSize.bytes(ERROR_BODY_MAX_SIZE));
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
                .withHeader("Accept-Encoding", equalTo("identity"))
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

    @Test
    void sendsTheApiKeyAndAsksForNoCompression() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));

        decisionsClient.decide(request(), apiConfig(Map.of())).block();

        var sent = wireMock.server().getAllServeEvents().getFirst().getRequest().getHeaders();
        assertThat(sent.getHeader("Authorization").values()).containsExactly("Bearer " + apiKey);
        assertThat(sent.getHeader("Accept-Encoding").values()).containsExactly("identity");
        assertThat(sent.getHeader("Content-Type").values()).containsExactly("application/json");
    }

    @Test
    void storedHeadersAreNormalizedAndReplaceTheOnesOpikSets() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));
        var authorization = "Bearer " + RandomStringUtils.secure().nextAlphanumeric(16);
        var custom = RandomStringUtils.secure().nextAlphanumeric(16);
        var headers = new HashMap<String, String>();
        headers.put("authorization", authorization);
        headers.put("accept-encoding", "br");
        headers.put("content-type", "text/plain");
        headers.put("X-Custom", custom);
        headers.put(" ", "no name");
        headers.put("X-Empty", null);

        decisionsClient.decide(request(), apiConfig(headers)).block();

        var sent = wireMock.server().getAllServeEvents().getFirst().getRequest().getHeaders();
        assertThat(sent.getHeader("Authorization").values()).containsExactly(authorization);
        assertThat(sent.getHeader("Accept-Encoding").values()).containsExactly("br");
        // the body is always JSON, so its type is the one sent
        assertThat(sent.getHeader("Content-Type").values()).containsExactly("application/json");
        assertThat(sent.getHeader("X-Custom").values()).containsExactly(custom);
        assertThat(sent.keys()).doesNotContain(" ", "X-Empty");
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
    void serverErrorCarriesStatusWithoutUpstreamText() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(500).withBody("boom")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(ServerErrorException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(500))
                .hasMessageContaining(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)
                .hasMessageNotContaining("boom");
    }

    @Test
    void undocumentedErrorCodeDropsUpstreamText() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"message\":\"internal detail\",\"code\":499}}")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOf(ClientErrorException.class)
                .hasMessageContaining(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)
                .hasMessageNotContaining("internal detail");
    }

    @Test
    void documentedErrorOverTheBodyLimitDropsUpstreamText() {
        var padding = " ".repeat(ERROR_BODY_MAX_SIZE);
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(ERROR_BODY.replaceFirst("\\{", "{" + padding))));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOf(ClientErrorException.class)
                .hasMessageContaining(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)
                .hasMessageNotContaining("At least one question is required");
    }

    @Test
    void unreadableSuccessBodyDropsUpstreamText() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody("{\"answers\": upstream-secret}")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOf(InternalServerErrorException.class)
                .satisfies(error -> assertThat(ExceptionUtils.getStackTrace(error))
                        .doesNotContain("upstream-secret"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void successBodyOverTheSizeLimitFails(boolean gzip) throws IOException {
        var body = SUCCESS_BODY.replaceFirst("\\{", "{" + " ".repeat(1024 * 1024)).getBytes(StandardCharsets.UTF_8);
        var reply = aResponse().withStatus(200).withHeader("Content-Type", "application/json");
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(gzip
                ? reply.withHeader("Content-Encoding", "gzip").withBody(gzip(body))
                : reply.withBody(body)));
        // the gzip client inflates as the production client does
        var client = gzip ? newDecisionsClient(RELAXED_GUARD, TestHttpClientUtils.gzipClient()) : decisionsClient;

        assertThatThrownBy(() -> client.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOf(InternalServerErrorException.class)
                .hasMessage("OpenRouter Decisions API returned an unreadable response, status '200'");
    }

    @ParameterizedTest
    @ValueSource(ints = {200, 400})
    void bodyThatArrivesSlowlyStopsAtTheDeadline(int status) {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(status).withHeader("Content-Type", "application/json")
                        .withBody(status == 200 ? SUCCESS_BODY : ERROR_BODY)
                        .withChunkedDribbleDelay(10, 6_000)));
        // each chunk comes within the read timeout, but the whole body does not
        clientConfig.setReadTimeout(Duration.seconds(1));
        long start = System.nanoTime();
        try {
            assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                    .isInstanceOf(status == 200 ? InternalServerErrorException.class : ClientErrorException.class);
        } finally {
            clientConfig.setReadTimeout(Duration.seconds(5));
        }

        assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(4_000);
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
    void redirectIsNotFollowedAndCarriesStatus() {
        var otherPath = "/other/alpha/decisions";
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(307)
                .withHeader("Location", wireMock.runtimeInfo().getHttpBaseUrl() + otherPath)));
        wireMock.server().stubFor(post(urlEqualTo(otherPath))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(WebApplicationException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(307))
                .hasMessage("OpenRouter Decisions API request failed, status '307', message '%s'"
                        .formatted(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER));
        assertThat(wireMock.server().getAllServeEvents()).hasSize(1);
    }

    @Test
    void errorWithoutBodyCarriesTheGenericMessage() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(400)));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOfSatisfying(ClientErrorException.class,
                        error -> assertThat(error.getResponse().getStatus()).isEqualTo(400))
                .hasMessage("OpenRouter Decisions API request failed, status '400', message '%s'"
                        .formatted(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER));
    }

    @ParameterizedTest
    @ValueSource(ints = {429, 502, 503, 529})
    void transientErrorIsRetriedInProcess(int status) {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(status).withBody("busy")));

        assertThatThrownBy(() -> decisionsClient.decide(request(), apiConfig(Map.of())).block())
                .isInstanceOf(RetryUtils.RetryableHttpException.class)
                .hasMessageContaining(UNEXPECTED_ERROR_CALLING_LLM_PROVIDER)
                .hasMessageNotContaining("busy");
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

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = "  ")
    void blankBaseUrlFallsBackToTheConfiguredUrl(String baseUrl) {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));
        var config = LlmProviderClientApiConfig.builder().apiKey(apiKey).baseUrl(baseUrl).build();

        decisionsClient.decide(request(), config).block();

        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void configuredDecisionsUrlIsUsedWhenTheProviderHasNoBaseUrl() {
        var overridePath = "/override/decisions";
        wireMock.server().stubFor(post(urlEqualTo(overridePath))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                        .withBody(SUCCESS_BODY)));
        clientConfig.setOpenRouterDecisionsUrl(wireMock.runtimeInfo().getHttpBaseUrl() + overridePath);
        try {
            decisionsClient.decide(request(), apiConfig(Map.of())).block();
        } finally {
            clientConfig.setOpenRouterDecisionsUrl(null);
        }

        wireMock.server().verify(1, postRequestedFor(urlEqualTo(overridePath)));
        wireMock.server().verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void baseUrlWithoutV1FailsWithoutCallingAnyHost() {
        var config = LlmProviderClientApiConfig.builder()
                .apiKey(apiKey)
                .baseUrl(wireMock.runtimeInfo().getHttpBaseUrl() + "/proxy")
                .build();

        // the error is in the Mono, so it reaches the caller's error mapping
        var decided = decisionsClient.decide(request(), config);

        assertThatThrownBy(decided::block)
                .isInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(URL_REFUSED);
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
            "https://my-proxy.example.com/openrouter/v1, https://my-proxy.example.com/openrouter/alpha/decisions",
            "https://h.example.com/api/v1?x=/v1, https://h.example.com/api/alpha/decisions?x=/v1"})
    void decisionsUrlReplacesTheTrailingV1(String openRouterUrl, String expected) {
        assertThat(OpenRouterDecisionsClient.decisionsUrl(openRouterUrl)).isEqualTo(expected);
    }

    @ParameterizedTest
    @ValueSource(strings = {"https://v1", "https://h.example.com/api", "https://h.example.com/api/v1/chat",
            "not a url"})
    void decisionsUrlRejectsAPathWithoutTheTrailingV1(String openRouterUrl) {
        assertThatThrownBy(() -> OpenRouterDecisionsClient.decisionsUrl(openRouterUrl))
                .isInstanceOf(ProviderRequestRefusedException.class)
                .hasMessage(URL_REFUSED);
    }

    private OpenRouterDecisionsClient newDecisionsClient(ProviderBaseUrlGuard guard) {
        return newDecisionsClient(guard, TestHttpClientUtils.client());
    }

    private OpenRouterDecisionsClient newDecisionsClient(ProviderBaseUrlGuard guard, Client httpClient) {
        return new OpenRouterDecisionsClient(new RetriableHttpClient(httpClient), clientConfig, guard,
                new ProviderErrorMessageValidator(clientConfig));
    }

    private static byte[] gzip(byte[] bytes) throws IOException {
        var compressed = new ByteArrayOutputStream();
        try (var gzip = new GZIPOutputStream(compressed)) {
            gzip.write(bytes);
        }
        return compressed.toByteArray();
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
