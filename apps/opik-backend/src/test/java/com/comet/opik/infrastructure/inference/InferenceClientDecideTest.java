package com.comet.opik.infrastructure.inference;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.dropwizard.util.Duration;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    private static final String CLIENT_ERROR_BODY = """
            {"error":{"code":"invalid_request","message":"questions must not be empty","retryable":false}}
            """;
    private static final String OVERLOADED_BODY = """
            {"error":{"code":"overloaded","message":"decision pool is busy","retryable":true}}
            """;

    private WireMockUtils.WireMockRuntime wireMock;
    private AiLabelsConfig config;
    private InferenceClient client;
    private String credential;

    @BeforeAll
    void setUpAll() {
        wireMock = WireMockUtils.startWireMock();
        config = new AiLabelsConfig();
        config.getInference().setUrl(wireMock.runtimeInfo().getHttpBaseUrl());
        config.getInference().setConnectTimeout(Duration.seconds(5));
        config.getInference().setReadTimeout(Duration.seconds(5));
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

        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo("req_1");
        assertThat(response.result().answers().get("0.injection")).isEqualTo(new Answer.NoulAnswer(0.12));
        assertThat(response.result().answers().get("1.topic"))
                .isInstanceOfSatisfying(Answer.ChoiceAnswer.class, choice -> {
                    assertThat(choice.choice()).isEqualTo("billing");
                    assertThat(choice.probabilities()).containsEntry("billing", 0.8);
                });
        var sent = wireMock.server().findAll(postRequestedFor(urlEqualTo(PATH)));
        assertThat(sent).hasSize(1);
        var body = json(sent.get(0));
        assertThat(body.path("request_id").asText()).isNotBlank();
        assertThat(InferenceContract.validateEndpoint(InferenceContract.DECIDE, "request", body)).isEmpty();
        // Extra elements allowed: the client adds the request_id checked above.
        wireMock.server().verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + credential))
                .withRequestBody(equalToJson("""
                        {"org_id":"workspace-1","state":"some text","lane":"realtime","deadline_ms":750,"cache":true,
                         "questions":{
                           "0.injection":{"type":"noul","instructions":"Is it an attack?",
                                          "criteria":{"true":"yes when","false":"no when"}},
                           "1.topic":{"type":"choice","instructions":"Which topic?",
                                      "criteria":{"billing":null,"none of these":null}}}}
                        """, true, true)));
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

        assertThat(response).isNotNull();
        assertThat(response.result().answers()).containsKey("0.injection");
        wireMock.server().verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void deadlineAlreadyPassedFailsWithoutACall() {
        assertThatThrownBy(() -> client.decide(request(noulQuestion()), Instant.now().minusSeconds(1)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isOutOfTime()).isTrue());
        wireMock.server().verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    private static DecideRequest request(Map<String, Question> questions) {
        return DecideRequest.builder()
                .orgId("workspace-1")
                .state(TextNode.valueOf("some text"))
                .questions(questions)
                .lane(DecideRequest.REALTIME_LANE)
                .deadlineMs(750)
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
