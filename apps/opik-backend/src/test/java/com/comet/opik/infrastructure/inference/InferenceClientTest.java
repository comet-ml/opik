package com.comet.opik.infrastructure.inference;

import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.AiLabelsConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InferenceClientTest {

    private static final String CREDENTIAL = "test-credential-do-not-log";
    private static final String DECIDE_PATH = "/v1/batch/decide";
    private static final String GENERATE_PATH = "/v1/batch/generate";
    private static final String JOB_ID = "job-7d3f";
    private static final String JOB_PATH = "/v1/batch/jobs/" + JOB_ID;
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private WireMockServer server;
    private InferenceClient client;

    @BeforeAll
    void startServer() {
        server = WireMockUtils.startWireMock().server();
    }

    @AfterAll
    void stopServer() {
        server.stop();
    }

    @BeforeEach
    void setUp() {
        server.resetAll();
        client = new InferenceClient(TestHttpClientUtils.client(), config(Duration.ofSeconds(5), 1024 * 1024));
    }

    @Test
    void submitDecideSendsBearerAuthAndContractValidBody() {
        server.stubFor(post(urlEqualTo(DECIDE_PATH)).willReturn(accepted()));

        var response = client.submitDecide(decideRequest()).block();

        assertThat(response).isEqualTo(BatchSubmitResponse.builder().jobId(JOB_ID).items(1).build());
        var sent = single(postRequestedFor(urlEqualTo(DECIDE_PATH)));
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + CREDENTIAL);
        assertThat(sent.getHeader("Content-Type")).startsWith("application/json");
        var body = json(sent);
        assertThat(body.path("org_id").asText()).isEqualTo("workspace-1");
        assertThat(body.path("request_id").asText()).isNotBlank();
        assertThat(InferenceContract.validateEndpoint(InferenceContract.BATCH_DECIDE, "request", body)).isEmpty();
    }

    @Test
    void getJobWhenResultsFalseSendsResultsFalse() {
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(jobView()));

        var view = client.getJob(JOB_ID, 0, false).block();

        assertThat(view.status()).isEqualTo(JobStatus.RUNNING);
        single(getRequestedFor(urlEqualTo(JOB_PATH + "?cursor=0&results=false")));
    }

    @Test
    void submitRetriesOverloadedWithTheSameRequestIdAndANewOnePerCall() {
        stubSequence(() -> post(urlEqualTo(DECIDE_PATH)), error("overloaded", 503), error("upstream_error", 502),
                accepted());

        client.submitDecide(decideRequest()).block();

        var retried = server.findAll(postRequestedFor(urlEqualTo(DECIDE_PATH)));
        assertThat(retried).hasSize(3);
        var requestId = json(retried.getFirst()).path("request_id").asText();
        assertThat(retried).allSatisfy(request -> assertThat(json(request).path("request_id").asText())
                .isEqualTo(requestId));

        server.resetRequests();
        client.submitDecide(decideRequest()).block();

        var next = single(postRequestedFor(urlEqualTo(DECIDE_PATH)));
        assertThat(json(next).path("request_id").asText()).isNotEqualTo(requestId);
    }

    @Test
    void transportErrorIsRetried() {
        stubSequence(() -> get(urlPathEqualTo(JOB_PATH)),
                aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), jobView());

        var view = client.getJob(JOB_ID, 0, false).block();

        assertThat(view).isNotNull();
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH)))).hasSize(2);
    }

    @Test
    void submitQuotaExceededIsNotRetriedAndCarriesRetryAfter() {
        server.stubFor(post(urlEqualTo(GENERATE_PATH))
                .willReturn(error("quota_exceeded", 429).withHeader("Retry-After", "3")));

        assertThatThrownBy(() -> client.submitGenerate(generateRequest(1, 10)).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.isQuotaExceeded()).isTrue();
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getHttpStatus()).isEqualTo(429);
                    assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofSeconds(3));
                });
        assertThat(server.findAll(postRequestedFor(urlEqualTo(GENERATE_PATH)))).hasSize(1);
    }

    static Stream<InferenceErrorCode> nonRetryableCodes() {
        return Stream.of(InferenceErrorCode.values()).filter(code -> !code.isRetryable());
    }

    @ParameterizedTest
    @MethodSource("nonRetryableCodes")
    void nonRetryableCodeFailsAtOnce(InferenceErrorCode code) {
        server.stubFor(post(urlEqualTo(DECIDE_PATH)).willReturn(error(code.getValue(), code.getHttpStatus())));

        assertThatThrownBy(() -> client.submitDecide(decideRequest()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(code.getValue());
                    assertThat(exception.getHttpStatus()).isEqualTo(code.getHttpStatus());
                    assertThat(exception.isRetryable()).isFalse();
                });
        assertThat(server.findAll(postRequestedFor(urlEqualTo(DECIDE_PATH)))).hasSize(1);
    }

    static Stream<InferenceErrorCode> retryableCodes() {
        return Stream.of(InferenceErrorCode.values()).filter(InferenceErrorCode::isRetryable);
    }

    @ParameterizedTest
    @MethodSource("retryableCodes")
    void retryableCodeIsRetriedTwiceThenFails(InferenceErrorCode code) {
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(error(code.getValue(), code.getHttpStatus())));

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo(code.getValue());
                    assertThat(exception.isRetryable()).isTrue();
                });
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH))))
                .hasSize(InferenceClient.MAX_RETRIES + 1);
    }

    @Test
    void errorBodyThatIsNotJsonBecomesRetryableUpstreamError() {
        server.stubFor(get(urlPathEqualTo(JOB_PATH))
                .willReturn(aResponse().withStatus(502).withBody("<html>bad gateway</html>")));

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("upstream_error");
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getHttpStatus()).isEqualTo(502);
                });
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH))))
                .hasSize(InferenceClient.MAX_RETRIES + 1);
    }

    @ParameterizedTest
    @EnumSource(value = UnknownCode.class)
    void unknownCodeFollowsTheEnvelopesRetryableFlag(UnknownCode unknown) {
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(aResponse().withStatus(500)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"added_later\",\"message\":\"m\",\"retryable\":%s}}"
                        .formatted(unknown.retryable))));

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("added_later");
                    assertThat(exception.isRetryable()).isEqualTo(unknown.retryable);
                });
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH)))).hasSize(unknown.requests);
    }

    enum UnknownCode {
        RETRYABLE(true, InferenceClient.MAX_RETRIES + 1),
        NOT_RETRYABLE(false, 1);

        final boolean retryable;
        final int requests;

        UnknownCode(boolean retryable, int requests) {
            this.retryable = retryable;
            this.requests = requests;
        }
    }

    @Test
    void errorCodesMatchTheContract() {
        var codes = InferenceContract.CONTRACT.path("errors").path("codes");
        var contractCodes = new ArrayList<String>();
        codes.fieldNames().forEachRemaining(contractCodes::add);

        assertThat(Stream.of(InferenceErrorCode.values()).map(InferenceErrorCode::getValue))
                .containsExactlyInAnyOrderElementsOf(contractCodes);
        assertThat(InferenceErrorCode.values()).allSatisfy(code -> {
            var contract = codes.path(code.getValue());
            assertThat(code.getHttpStatus()).as(code.getValue()).isEqualTo(contract.path("http_status").asInt());
            assertThat(code.isRetryable()).as(code.getValue()).isEqualTo(contract.path("retryable").asBoolean());
        });
    }

    static Stream<Arguments> retryAfterHeaders() {
        return Stream.of(
                arguments("3", Duration.ofSeconds(3)),
                arguments("1.5", Duration.ofMillis(1500)),
                arguments("0", Duration.ZERO),
                arguments("120", Duration.ofSeconds(30)),
                arguments("Wed, 21 Oct 2026 07:28:00 GMT", null),
                arguments("-1", null),
                arguments("", null),
                arguments(null, null));
    }

    @ParameterizedTest
    @MethodSource("retryAfterHeaders")
    void retryAfterIsParsedAsSecondsAndCapped(String header, Duration expected) {
        assertThat(InferenceException.parseRetryAfter(header)).isEqualTo(expected);
    }

    @Test
    void oversizedBodyFailsBeforeAnyRequest() {
        client = new InferenceClient(TestHttpClientUtils.client(), config(Duration.ofSeconds(5), 2_000));
        server.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(accepted()));

        assertThatThrownBy(() -> client.submitGenerate(generateRequest(2, 1_000)).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxJobBytes");
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void noAttemptOutlastsTheReadTimeout() {
        var readTimeout = Duration.ofMillis(300);
        client = new InferenceClient(TestHttpClientUtils.client(), config(readTimeout, 1024 * 1024));
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(jobView().withFixedDelay(5_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, false).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH))))
                .hasSize(InferenceClient.MAX_RETRIES + 1);
        // Three attempts of 300 ms plus 0.1 s and 0.2 s of backoff, never one 5 s response.
        assertThat(elapsed).isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void exceptionMessageCarriesNeitherTheCredentialNorTheServiceMessage() {
        server.stubFor(post(urlEqualTo(DECIDE_PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"invalid_request\",\"message\":\"input_value='secret trace'\","
                        + "\"retryable\":false}}")));

        assertThatThrownBy(() -> client.submitDecide(decideRequest()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getMessage()).doesNotContain(CREDENTIAL).doesNotContain("secret trace");
                    assertThat(exception.getServiceMessage()).isEqualTo("input_value='secret trace'");
                });
    }

    private AiLabelsConfig config(Duration readTimeout, long maxJobBytes) {
        var config = new AiLabelsConfig();
        config.getInference().setUrl(server.baseUrl());
        config.getInference().setCredential(CREDENTIAL);
        config.getInference().setConnectTimeout(io.dropwizard.util.Duration.seconds(1));
        config.getInference().setReadTimeout(io.dropwizard.util.Duration.milliseconds(readTimeout.toMillis()));
        config.setMaxJobBytes(maxJobBytes);
        return config;
    }

    private void stubSequence(Supplier<MappingBuilder> request, ResponseDefinitionBuilder... responses) {
        var state = Scenario.STARTED;
        for (int index = 0; index < responses.length; index++) {
            var next = index == responses.length - 1 ? state : "attempt-" + (index + 1);
            server.stubFor(request.get()
                    .inScenario("sequence")
                    .whenScenarioStateIs(state)
                    .willSetStateTo(next)
                    .willReturn(responses[index]));
            state = next;
        }
    }

    private LoggedRequest single(RequestPatternBuilder pattern) {
        var requests = server.findAll(pattern);
        assertThat(requests).hasSize(1);
        return requests.getFirst();
    }

    private static JsonNode json(LoggedRequest request) {
        try {
            return InferenceJson.MAPPER.readTree(request.getBodyAsString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    private static ResponseDefinitionBuilder accepted() {
        return aResponse().withStatus(202)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"job_id\":\"%s\",\"items\":1,\"added_later\":true}".formatted(JOB_ID));
    }

    private static ResponseDefinitionBuilder jobView() {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(InferenceContract.resourceText("job-view-no-results.json"));
    }

    private static ResponseDefinitionBuilder error(String code, int status) {
        return aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"%s\",\"message\":\"m\",\"retryable\":true}}".formatted(code));
    }

    private static BatchDecideRequest decideRequest() {
        return BatchDecideRequest.builder()
                .orgId("workspace-1")
                .workspaceId("workspace-1")
                .cache(false)
                .items(List.of(BatchDecideItem.builder()
                        .itemId("trace-1")
                        .state(NODES.objectNode().put("name", "agent"))
                        .questions(Map.of("task.q", Question.NoulQuestion.builder()
                                .instructions(NODES.textNode("Did it work?"))
                                .build()))
                        .build()))
                .build();
    }

    private static BatchGenerateRequest generateRequest(int items, int contentLength) {
        return BatchGenerateRequest.builder()
                .orgId("workspace-1")
                .workspaceId("workspace-1")
                .cache(false)
                .items(Stream.iterate(0, index -> index + 1).limit(items)
                        .map(index -> BatchGenerateItem.builder()
                                .itemId("trace-%d:summary".formatted(index))
                                .messages(List.of(Message.builder().role(Message.Role.USER)
                                        .content("x".repeat(contentLength)).build()))
                                .schema(NODES.objectNode().put("type", "object"))
                                .maxTokens(16)
                                .temperature(0)
                                .build())
                        .toList())
                .build();
    }
}
