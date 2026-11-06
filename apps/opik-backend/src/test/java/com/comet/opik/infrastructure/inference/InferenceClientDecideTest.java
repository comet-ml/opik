package com.comet.opik.infrastructure.inference;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import com.sun.net.httpserver.HttpServer;
import jakarta.ws.rs.client.ClientBuilder;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InferenceClientDecideTest {

    private static final String PATH = "/v1/decide";

    // Response shapes of the opik-inference decide API; the success body is checked against the vendored contract.
    private static final String SUCCESS_BODY = """
            {"request_id":"req_1","usage":{"input_tokens":12,"output_tokens":3},"cache_hit":false,"latency_ms":31,
             "result":{"answers":{
               "0.injection":{"type":"noul","noul":0.12},
               "1.topic":{"type":"choice","choice":"billing","probabilities":{"billing":0.8,"none of these":0.2},
                          "confidence":0.8}}}}
            """;
    private static final DecideResponse SUCCESS_RESPONSE = DecideResponse.builder()
            .requestId("req_1")
            .usage(Usage.builder().inputTokens(12).outputTokens(3).build())
            .cacheHit(false)
            .latencyMs(31)
            .result(DecideResponse.Result.builder()
                    .answers(Map.of(
                            "0.injection", Answer.NoulAnswer.builder().noul(0.12).build(),
                            "1.topic", Answer.ChoiceAnswer.builder()
                                    .choice("billing")
                                    .probabilities(Map.of("billing", 0.8, "none of these", 0.2))
                                    .confidence(0.8)
                                    .build()))
                    .build())
            .build();
    private static final String CLIENT_ERROR_BODY = """
            {"error":{"code":"invalid_request","message":"questions must not be empty","retryable":false}}
            """;
    private static final String OVERLOADED_BODY = """
            {"error":{"code":"overloaded","message":"decision pool is busy","retryable":true}}
            """;
    private static final String DEADLINE_EXCEEDED_BODY = """
            {"error":{"code":"deadline_exceeded","message":"no answer within 750 ms","retryable":true}}
            """;

    private WireMockUtils.WireMockRuntime wireMock;
    private AiLabelsConfig config;
    private InferenceClient client;
    private String credential;
    private String orgId;
    private String state;

    @BeforeAll
    void setUpAll() {
        wireMock = WireMockUtils.startWireMock();
        config = config(wireMock.runtimeInfo().getHttpBaseUrl());
        client = new InferenceClient(TestHttpClientUtils.client(), config);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        credential = RandomStringUtils.secure().nextAlphanumeric(32);
        config.getInference().setCredential(credential);
        orgId = UUID.randomUUID().toString();
        state = RandomStringUtils.secure().nextAlphanumeric(20);
    }

    @Test
    void successBodyFollowsTheContract() throws IOException {
        assertThat(InferenceContract.validateEndpoint(InferenceContract.DECIDE, "response",
                InferenceJson.MAPPER.readTree(SUCCESS_BODY))).isEmpty();
    }

    @Test
    void sendsBearerTokenAndParsesAnswers() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY)));
        var questions = new LinkedHashMap<String, Question>();
        questions.put("0.injection", Question.NoulQuestion.builder()
                .instructions(TextNode.valueOf("Is it an attack?"))
                .criteria(Question.NoulCriteria.builder()
                        .whenTrue(TextNode.valueOf("yes when")).whenFalse(TextNode.valueOf("no when")).build())
                .build());
        var options = new LinkedHashMap<String, JsonNode>();
        options.put("billing", NullNode.getInstance());
        options.put("none of these", NullNode.getInstance());
        questions.put("1.topic", Question.ChoiceQuestion.builder()
                .instructions(TextNode.valueOf("Which topic?")).criteria(options).build());

        var response = client.decide(request(questions), Instant.now().plusSeconds(5)).block();

        assertThat(response).isEqualTo(SUCCESS_RESPONSE);
        var sent = wireMock.server().findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(sent).hasSize(1);
        var body = json(sent.get(0));
        assertThat(body.path("request_id").asText()).isNotBlank();
        assertThat(InferenceContract.validateEndpoint(InferenceContract.DECIDE, "request", body)).isEmpty();
        // Extra elements allowed: the client adds the request_id checked above.
        wireMock.server().verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + credential))
                .withHeader("Content-Type", equalTo("application/json"))
                .withRequestBody(equalToJson("""
                        {"org_id":"%s","state":"%s","lane":"realtime","deadline_ms":750,"cache":true,
                         "questions":{
                           "0.injection":{"type":"noul","instructions":"Is it an attack?",
                                          "criteria":{"true":"yes when","false":"no when"}},
                           "1.topic":{"type":"choice","instructions":"Which topic?",
                                      "criteria":{"billing":null,"none of these":null}}}}
                        """.formatted(orgId, state), true, true)));
    }

    @Test
    void clientErrorKeepsTheServiceCodeWithoutRetry() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(CLIENT_ERROR_BODY)));

        assertThatThrownBy(() -> client.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("invalid_request");
                    assertThat(exception.getHttpStatus()).isEqualTo(400);
                    assertThat(exception.isRetryable()).isFalse();
                });
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retryableErrorIsRetriedWithinTheDeadline() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503).withHeader("Content-Type", "application/json")
                        .withBody(OVERLOADED_BODY))
                .willSetStateTo("recovered"));
        wireMock.server().stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(SUCCESS_BODY)));

        var response = client.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block();

        assertThat(response).isEqualTo(SUCCESS_RESPONSE);
        wireMock.server().verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void redirectIsNotFollowed() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(307).withHeader("Location", "/elsewhere")));
        wireMock.server().stubFor(post(urlEqualTo("/elsewhere")).willReturn(okJson(SUCCESS_BODY)));

        assertThatThrownBy(() -> client.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getHttpStatus()).isEqualTo(307);
                    assertThat(exception.isRetryable()).isFalse();
                });
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
        wireMock.server().verify(0, postRequestedFor(urlEqualTo("/elsewhere")));
    }

    @Test
    void deadlineExceededIsNotRetried() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(504).withHeader("Content-Type", "application/json")
                        .withBody(DEADLINE_EXCEEDED_BODY)));

        assertThatThrownBy(() -> client.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isDeadlineExceeded()).isTrue());
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void slowServiceFailsAtTheDeadline() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY).withFixedDelay(3_000)));
        var start = Instant.now();

        // The socket's read timeout, cut to the deadline, and the bound on the whole call fire together.
        assertThatThrownBy(() -> client.decide(request(noulQuestion()), start.plusMillis(500)).block())
                .isExactlyInstanceOf(InferenceException.class);
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(1_500));
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void tricklingBodyIsCutOffAtTheDeadline() {
        // Each chunk arrives well within the read timeout, so only a bound on the whole call stops it. Gzip off: a
        // gzipped body arrives in one piece.
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(okJson(SUCCESS_BODY).withGzipDisabled(true).withChunkedDribbleDelay(20, 3_000)));
        var start = Instant.now();

        assertThatThrownBy(() -> client.decide(request(noulQuestion()), start.plusMillis(500)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isOutOfTime()).isTrue());
        assertThat(Duration.between(start, Instant.now())).isLessThan(Duration.ofMillis(1_500));
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void callCutOffAtTheDeadlineKeepsItsSlotUntilItsBodyEnds() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(okJson(SUCCESS_BODY).withGzipDisabled(true).withChunkedDribbleDelay(20, 2_000)));
        var singleSlotConfig = config(wireMock.runtimeInfo().getHttpBaseUrl());
        singleSlotConfig.getInference().setMaxConcurrentDecides(1);
        var singleSlot = new InferenceClient(TestHttpClientUtils.client(), singleSlotConfig);

        assertThatThrownBy(() -> singleSlot.decide(request(noulQuestion()), Instant.now().plusMillis(300)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isOutOfTime()).isTrue());

        // Its thread is still reading the body, so the next call finds no slot, even after its retries.
        assertThatThrownBy(() -> singleSlot.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(InferenceException.CLIENT_BUSY));
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY)));
        await().atMost(Duration.ofSeconds(5)).ignoreExceptionsInstanceOf(InferenceException.class)
                .untilAsserted(() -> assertThat(
                        singleSlot.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                        .isEqualTo(SUCCESS_RESPONSE));
    }

    @Test
    void decideDoesNotUseTheSharedClient() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY)));
        var closedSharedClient = ClientBuilder.newClient();
        closedSharedClient.close();
        var isolated = new InferenceClient(closedSharedClient, config);

        assertThat(isolated.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void callOverTheCapRetriesThenFailsWithoutReachingTheService() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY).withFixedDelay(1_000)));
        var cappedConfig = config(wireMock.runtimeInfo().getHttpBaseUrl());
        cappedConfig.getInference().setMaxConcurrentDecides(2);
        var capped = new InferenceClient(TestHttpClientUtils.client(), cappedConfig);

        var outcomes = Flux.range(0, 3)
                .flatMap(i -> capped.decide(request(noulQuestion()), Instant.now().plusSeconds(5))
                        .map(response -> "answered")
                        .onErrorResume(InferenceException.class, exception -> Mono.just(exception.getCode())))
                .collectList()
                .block();

        assertThat(outcomes).containsExactlyInAnyOrder("answered", "answered", InferenceException.CLIENT_BUSY);
        wireMock.server().verify(2, postRequestedFor(urlEqualTo(PATH)));
        // The two calls gave their slots back when they ended.
        assertThat(capped.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isEqualTo(SUCCESS_RESPONSE);
    }

    @Test
    void consecutiveCallsShareOneConnection() throws IOException {
        var clientPorts = clientPortsOfThreeCalls(null);

        // One client port: the three calls went over one connection, with no new handshake.
        assertThat(clientPorts).hasSize(3).containsOnly(clientPorts.getFirst());
    }

    @Test
    void zeroKeepAliveOpensAConnectionPerCall() throws IOException {
        assertThat(clientPortsOfThreeCalls(io.dropwizard.util.Duration.milliseconds(0))).doesNotHaveDuplicates();
    }

    /** The client port of each of three decide calls to a local server, with the given keep-alive or the default. */
    private List<Integer> clientPortsOfThreeCalls(io.dropwizard.util.Duration decideKeepAlive) throws IOException {
        var clientPorts = new CopyOnWriteArrayList<Integer>();
        var server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext(PATH, exchange -> {
            clientPorts.add(exchange.getRemoteAddress().getPort());
            exchange.getRequestBody().readAllBytes();
            var body = SUCCESS_BODY.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var responseBody = exchange.getResponseBody()) {
                responseBody.write(body);
            }
        });
        server.start();
        try {
            var serverConfig = config("http://%s:%d".formatted(server.getAddress().getHostString(),
                    server.getAddress().getPort()));
            if (decideKeepAlive != null) {
                serverConfig.getInference().setDecideKeepAlive(decideKeepAlive);
            }
            var serverClient = new InferenceClient(TestHttpClientUtils.client(), serverConfig);

            for (var call = 0; call < 3; call++) {
                assertThat(serverClient.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                        .isEqualTo(SUCCESS_RESPONSE);
            }
            return List.copyOf(clientPorts);
        } finally {
            server.stop(0);
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "not json"})
    void malformedSuccessIsAnUpstreamError(String body) {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(body)));

        assertThatThrownBy(() -> client.decide(request(noulQuestion()), Instant.now().plusSeconds(5)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
    }

    @Test
    void requestWithoutAServiceDeadlineLeavesItToTheService() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY)));
        var withoutDeadline = request(noulQuestion()).toBuilder().deadlineMs(null).build();

        client.decide(withoutDeadline, Instant.now().plusSeconds(5)).block();

        var body = json(wireMock.server().findAll(postRequestedFor(urlEqualTo(PATH))).get(0));
        assertThat(body.has("deadline_ms")).isFalse();
        assertThat(InferenceContract.validateEndpoint(InferenceContract.DECIDE, "request", body)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(longs = {0, -1})
    void nonPositiveServiceDeadlineFailsWithoutACall(long deadlineMs) {
        var request = request(noulQuestion()).toBuilder().deadlineMs(deadlineMs).build();

        assertThatThrownBy(() -> client.decide(request, Instant.now().plusSeconds(5)))
                .isExactlyInstanceOf(IllegalArgumentException.class);
        wireMock.server().verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void deadlineAlreadyPassedFailsWithoutACall() {
        assertThatThrownBy(() -> client.decide(request(noulQuestion()), Instant.now().minusSeconds(1)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isOutOfTime()).isTrue());
        wireMock.server().verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    private AiLabelsConfig config(String url) {
        var newConfig = new AiLabelsConfig();
        newConfig.getInference().setUrl(url);
        newConfig.getInference().setCredential(credential);
        newConfig.getInference().setConnectTimeout(io.dropwizard.util.Duration.seconds(5));
        newConfig.getInference().setReadTimeout(io.dropwizard.util.Duration.seconds(5));
        return newConfig;
    }

    private DecideRequest request(Map<String, Question> questions) {
        return DecideRequest.builder()
                .orgId(orgId)
                .state(TextNode.valueOf(state))
                .questions(questions)
                .lane(DecideRequest.REALTIME_LANE)
                .deadlineMs(750L)
                .cache(true)
                .build();
    }

    private static Map<String, Question> noulQuestion() {
        return Map.of("0.injection",
                Question.NoulQuestion.builder().instructions(TextNode.valueOf("Is it an attack?")).build());
    }

    private static JsonNode json(LoggedRequest request) {
        try {
            return InferenceJson.MAPPER.readTree(request.getBodyAsString());
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }
}
