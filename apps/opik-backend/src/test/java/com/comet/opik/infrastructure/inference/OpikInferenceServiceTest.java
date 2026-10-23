package com.comet.opik.infrastructure.inference;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.FeatureFlags;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RetriableHttpClient;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.dropwizard.util.Duration;
import jakarta.ws.rs.ClientErrorException;
import jakarta.ws.rs.ForbiddenException;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.util.LinkedHashMap;
import java.util.List;
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
class OpikInferenceServiceTest {

    private static final String PATH = "/v1/decide";
    private static final int MAX_ATTEMPTS = 2;

    // Response shapes of the opik-inference decide API.
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
    private static final String RETRYABLE_ERROR_BODY = """
            {"error":{"code":"overloaded","message":"decision pool is busy","retryable":true}}
            """;

    private WireMockUtils.WireMockRuntime wireMock;
    private OpikConfiguration configuration;
    private OpikInferenceService service;
    private String apiKey;

    @BeforeAll
    void setUpAll() {
        wireMock = WireMockUtils.startWireMock();
        configuration = new OpikConfiguration();
        configuration.getServiceToggles().setOpikInferenceEnabled(true);
        var config = configuration.getOpikInference();
        config.setUrl(wireMock.runtimeInfo().getHttpBaseUrl());
        config.setMaxRetryAttempts(MAX_ATTEMPTS);
        config.setConnectTimeout(Duration.seconds(5));
        config.setReadTimeout(Duration.seconds(5));
        config.setDeadline(Duration.milliseconds(750));
        service = new OpikInferenceService(new RetriableHttpClient(TestHttpClientUtils.client()), config,
                new FeatureFlags(configuration));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        wireMock.server().resetAll();
        configuration.getServiceToggles().setOpikInferenceEnabled(true);
        apiKey = RandomStringUtils.secure().nextAlphanumeric(32);
        configuration.getOpikInference().setApiKey(apiKey);
    }

    @Test
    void sendsBearerTokenAndParsesAnswers() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).willReturn(okJson(SUCCESS_BODY)));
        var questions = new LinkedHashMap<String, DecideQuestion>();
        questions.put("0.injection", DecideQuestion.noul("Is it an attack?", "yes when", "no when"));
        questions.put("1.topic", DecideQuestion.choice("Which topic?", List.of("billing", "none of these")));

        var response = service.decide("workspace-1", "some text", questions).block();

        assertThat(response).isNotNull();
        assertThat(response.requestId()).isEqualTo("req_1");
        assertThat(response.answers().get("0.injection").noul()).isEqualTo(0.12);
        assertThat(response.answers().get("1.topic").choice()).isEqualTo("billing");
        assertThat(response.answers().get("1.topic").probabilities()).containsEntry("billing", 0.8);
        wireMock.server().verify(postRequestedFor(urlEqualTo(PATH))
                .withHeader("Authorization", equalTo("Bearer " + apiKey))
                .withRequestBody(equalToJson("""
                        {"org_id":"workspace-1","state":"some text","lane":"realtime","deadline_ms":750,"cache":true,
                         "questions":{
                           "0.injection":{"type":"noul","instructions":"Is it an attack?",
                                          "criteria":{"true":"yes when","false":"no when"}},
                           "1.topic":{"type":"choice","instructions":"Which topic?",
                                      "criteria":{"billing":null,"none of these":null}}}}
                        """)));
    }

    @Test
    void clientErrorKeepsStatusAndMessageWithoutRetry() {
        wireMock.server().stubFor(post(urlEqualTo(PATH))
                .willReturn(aResponse().withStatus(400).withHeader("Content-Type", "application/json")
                        .withBody(CLIENT_ERROR_BODY)));

        assertThatThrownBy(() -> service.decide("workspace-1", "text", noulQuestion()).block())
                .isInstanceOf(ClientErrorException.class)
                .hasMessageContaining("status '400'")
                .hasMessageContaining("invalid_request")
                .hasMessageContaining("questions must not be empty");
        wireMock.server().verify(1, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void retryableErrorIsRetried() {
        wireMock.server().stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(500).withHeader("Content-Type", "application/json")
                        .withBody(RETRYABLE_ERROR_BODY))
                .willSetStateTo("recovered"));
        wireMock.server().stubFor(post(urlEqualTo(PATH)).inScenario("retry")
                .whenScenarioStateIs("recovered")
                .willReturn(okJson(SUCCESS_BODY)));

        var response = service.decide("workspace-1", "text", noulQuestion()).block();

        assertThat(response).isNotNull();
        assertThat(response.answers()).containsKey("0.injection");
        wireMock.server().verify(2, postRequestedFor(urlEqualTo(PATH)));
    }

    @Test
    void disabledToggleIsForbiddenBeforeAnyCall() {
        configuration.getServiceToggles().setOpikInferenceEnabled(false);

        assertThatThrownBy(() -> service.decide("workspace-1", "text", noulQuestion()).block())
                .isInstanceOf(ForbiddenException.class);
        wireMock.server().verify(0, postRequestedFor(urlEqualTo(PATH)));
    }

    private static Map<String, DecideQuestion> noulQuestion() {
        return Map.of("0.injection", DecideQuestion.noul("Is it an attack?"));
    }
}
