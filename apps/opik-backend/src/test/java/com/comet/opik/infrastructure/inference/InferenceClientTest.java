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
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;
import java.util.stream.IntStream;
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
    private static final String STATUS_PATH = "/v1/batch/jobs/status";
    private static final String EMBED_PATH = "/v1/embed";
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

        var response = client.submitDecide(decideRequest(), later()).block();

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

        var view = client.getJob(JOB_ID, 0, false, later()).block();

        assertThat(view.status()).isEqualTo(JobStatus.RUNNING);
        single(getRequestedFor(urlEqualTo(JOB_PATH + "?cursor=0&results=false")));
    }

    @Test
    void getJobsSendsAContractValidBodyAndReadsTheFoundJobs() {
        var view = InferenceJson.MAPPER.createObjectNode();
        view.putArray("jobs").add(InferenceContract.resource("job-view-no-results.json"));
        server.stubFor(post(urlEqualTo(STATUS_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(view.toString())));

        var views = client.getJobs(List.of(JOB_ID, "job-unknown"), later()).block();

        assertThat(views).singleElement().satisfies(found -> {
            assertThat(found.jobId()).isEqualTo(InferenceContract.resource("job-view-no-results.json")
                    .path("job_id").asText());
            assertThat(found.status()).isEqualTo(JobStatus.RUNNING);
        });
        var body = json(single(postRequestedFor(urlEqualTo(STATUS_PATH))));
        assertThat(body.path("job_ids")).extracting(JsonNode::asText).containsExactly(JOB_ID, "job-unknown");
        assertThat(InferenceContract.validateEndpoint(InferenceContract.JOBS_STATUS, "request", body)).isEmpty();
    }

    @Test
    void getJobsIsRetriedLikeAGet() {
        var empty = aResponse().withStatus(200).withHeader("Content-Type", "application/json")
                .withBody("{\"jobs\":[]}");
        stubSequence(() -> post(urlEqualTo(STATUS_PATH)), error("overloaded", 503), empty);

        assertThat(client.getJobs(List.of(JOB_ID), later()).block()).isEmpty();
        assertThat(server.findAll(postRequestedFor(urlEqualTo(STATUS_PATH)))).hasSize(2);
    }

    @Test
    void getJobsTakesOneToAThousandIds() {
        var tooMany = IntStream.rangeClosed(0, InferenceClient.MAX_STATUS_JOB_IDS)
                .mapToObj("job-%d"::formatted)
                .toList();

        assertThatThrownBy(() -> client.getJobs(List.of(), later())).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.getJobs(tooMany, later())).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void submitRetriesOverloadedWithTheSameRequestIdAndANewOnePerCall() {
        stubSequence(() -> post(urlEqualTo(DECIDE_PATH)), error("overloaded", 503), error("upstream_error", 502),
                accepted());

        client.submitDecide(decideRequest(), later()).block();

        var retried = server.findAll(postRequestedFor(urlEqualTo(DECIDE_PATH)));
        assertThat(retried).hasSize(3);
        var requestId = json(retried.getFirst()).path("request_id").asText();
        assertThat(retried).allSatisfy(request -> assertThat(json(request).path("request_id").asText())
                .isEqualTo(requestId));

        server.resetRequests();
        client.submitDecide(decideRequest(), later()).block();

        var next = single(postRequestedFor(urlEqualTo(DECIDE_PATH)));
        assertThat(json(next).path("request_id").asText()).isNotEqualTo(requestId);
    }

    @Test
    void transportErrorIsRetried() {
        stubSequence(() -> get(urlPathEqualTo(JOB_PATH)),
                aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER), jobView());

        var view = client.getJob(JOB_ID, 0, false, later()).block();

        assertThat(view).isNotNull();
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH)))).hasSize(2);
    }

    @Test
    void bodyCutOffAfterTheHeadersIsRetried() {
        stubSequence(() -> get(urlPathEqualTo(JOB_PATH)),
                aResponse().withFault(Fault.MALFORMED_RESPONSE_CHUNK), jobView());

        var view = client.getJob(JOB_ID, 0, false, later()).block();

        assertThat(view).isNotNull();
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH)))).hasSize(2);
    }

    static Stream<Arguments> nullBodies() {
        return Stream.of(arguments(200), arguments(500));
    }

    @ParameterizedTest
    @MethodSource("nullBodies")
    void jsonNullBodyBecomesRetryableUpstreamError(int status) {
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("null")));

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true, later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("upstream_error");
                    assertThat(exception.isRetryable()).isTrue();
                    assertThat(exception.getHttpStatus()).isEqualTo(status);
                });
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH))))
                .hasSize(InferenceClient.MAX_RETRIES + 1);
    }

    @Test
    void submitQuotaExceededIsNotRetriedAndCarriesRetryAfter() {
        server.stubFor(post(urlEqualTo(GENERATE_PATH))
                .willReturn(error("quota_exceeded", 429).withHeader("Retry-After", "3")));

        assertThatThrownBy(() -> client.submitGenerate(generateRequest(1, 10), later()).block())
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

        assertThatThrownBy(() -> client.submitDecide(decideRequest(), later()).block())
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

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true, later()).block())
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

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true, later()).block())
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

        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, true, later()).block())
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

        assertThatThrownBy(() -> client.submitGenerate(generateRequest(2, 1_000), later()).block())
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
        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, false, later()).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH))))
                .hasSize(InferenceClient.MAX_RETRIES + 1);
        // Three attempts of 300 ms plus 0.1 s and 0.2 s of backoff, never one 5 s response.
        assertThat(elapsed).isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void noAttemptOutlastsTheDeadline() {
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(jobView().withFixedDelay(5_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, false, Instant.now().plusMillis(300)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        // The 5 s read timeout is cut to the 300 ms left, and no retry fits after it.
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH)))).hasSize(1);
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_000));
    }

    @Test
    void retryWhoseBackoffPassesTheDeadlineIsNotMade() {
        server.stubFor(post(urlEqualTo(DECIDE_PATH))
                .willReturn(error("overloaded", 503).withHeader("Retry-After", "5")));

        assertThatThrownBy(() -> client.submitDecide(decideRequest(), Instant.now().plusSeconds(2)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("overloaded"));
        assertThat(server.findAll(postRequestedFor(urlEqualTo(DECIDE_PATH)))).hasSize(1);
    }

    @Test
    void callPastItsDeadlineSendsNothing() {
        server.stubFor(post(urlEqualTo(DECIDE_PATH)).willReturn(accepted()));

        assertThatThrownBy(() -> client.submitDecide(decideRequest(), Instant.now().minusMillis(1)).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.isOutOfTime()).isTrue();
                    assertThat(exception.isRetryable()).isFalse();
                });
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void exceptionMessageCarriesNeitherTheCredentialNorTheServiceMessage() {
        server.stubFor(post(urlEqualTo(DECIDE_PATH)).willReturn(aResponse().withStatus(400)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"error\":{\"code\":\"invalid_request\",\"message\":\"input_value='secret trace'\","
                        + "\"retryable\":false}}")));

        assertThatThrownBy(() -> client.submitDecide(decideRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getMessage()).doesNotContain(CREDENTIAL).doesNotContain("secret trace");
                    assertThat(exception.getServiceMessage()).isEqualTo("input_value='secret trace'");
                });
    }

    @Test
    void embedSendsBearerAuthAndReadsTheVectorsInOrder() {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(embedded()));

        var response = client.embed(embedRequest(), later()).block();

        assertThat(response.result()).isEqualTo(EmbedResult.builder()
                .embeddings(List.of(List.of(0.6f, 0.8f), List.of(1.0f, 0.0f)))
                .dimensions(2)
                .build());
        var sent = single(postRequestedFor(urlEqualTo(EMBED_PATH)));
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + CREDENTIAL);
        var body = json(sent);
        assertThat(body.path("org_id").asText()).isEqualTo("workspace-1");
        assertThat(body.path("cache").asBoolean(true)).isFalse();
        assertThat(body.path("inputs")).extracting(JsonNode::asText)
                .containsExactly("refund a duplicate invoice charge", "export traces to csv");
        assertThat(body.path("request_id").asText()).isNotBlank();
        // The time left before the 60 s deadline, never above the service's maximum.
        assertThat(body.path("deadline_ms").asLong()).isBetween(1L, InferenceClient.MAX_EMBED_DEADLINE.toMillis());
    }

    @Test
    void embedQuotaExceededIsNotRetried() {
        server.stubFor(post(urlEqualTo(EMBED_PATH))
                .willReturn(error("quota_exceeded", 429).withHeader("Retry-After", "2")));

        assertThatThrownBy(() -> client.embed(embedRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.isQuotaExceeded()).isTrue();
                    assertThat(exception.getHttpStatus()).isEqualTo(429);
                    assertThat(exception.getRetryAfter()).isEqualTo(Duration.ofSeconds(2));
                });
        assertThat(server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)))).hasSize(1);
    }

    @Test
    void embedServerErrorIsRetriedWithTheSameRequestIdThenFails() {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(error("overloaded", 503)));

        assertThatThrownBy(() -> client.embed(embedRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("overloaded");
                    assertThat(exception.getHttpStatus()).isEqualTo(503);
                });
        var sent = server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)));
        assertThat(sent).hasSize(InferenceClient.MAX_RETRIES + 1);
        var requestId = json(sent.getFirst()).path("request_id").asText();
        assertThat(sent).allSatisfy(request -> assertThat(json(request).path("request_id").asText())
                .isEqualTo(requestId));
    }

    @Test
    void embedTimeoutStopsAtTheDeadline() {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(embedded().withFixedDelay(5_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.embed(embedRequest(), Instant.now().plusMillis(300)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)))).hasSize(1);
        assertThat(elapsed).isLessThan(Duration.ofMillis(2_000));
    }

    @Test
    void embedDeadlineIsTheSmallestOfTheCallersTheTimeLeftAndTheMaximum() {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(embedded()));

        client.embed(embedRequest().toBuilder().deadlineMs(2_000L).build(), later()).block();
        client.embed(embedRequest().toBuilder().deadlineMs(120_000L).build(), Instant.now().plusSeconds(5)).block();
        client.embed(embedRequest().toBuilder().deadlineMs(120_000L).build(), Instant.now().plusSeconds(600))
                .block();

        var sent = server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH))).stream()
                .map(request -> json(request).path("deadline_ms").asLong())
                .toList();
        assertThat(sent).hasSize(3);
        assertThat(sent.get(0)).isEqualTo(2_000L);
        assertThat(sent.get(1)).isBetween(1L, 5_000L);
        assertThat(sent.get(2)).isEqualTo(InferenceClient.MAX_EMBED_DEADLINE.toMillis());
    }

    @ParameterizedTest(name = "deadline_ms {0}")
    @ValueSource(longs = {0L, -1L})
    void embedRejectsANonPositiveDeadline(long deadlineMs) {
        var request = embedRequest().toBuilder().deadlineMs(deadlineMs).build();

        assertThatThrownBy(() -> client.embed(request, later())).isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void embedTakesOneTo256Inputs() {
        var tooMany = IntStream.rangeClosed(0, InferenceClient.MAX_EMBED_INPUTS).mapToObj("text %d"::formatted)
                .toList();

        assertThatThrownBy(() -> client.embed(embedRequest().toBuilder().inputs(List.of()).build(), later()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.embed(embedRequest().toBuilder().inputs(tooMany).build(), later()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private static Instant later() {
        return Instant.now().plusSeconds(60);
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

    private static ResponseDefinitionBuilder embedded() {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"request_id": "r-1", "usage": {"input_tokens": 9, "output_tokens": 0}, "cache_hit": false,
                         "latency_ms": 12, "result": {"embeddings": [[0.6, 0.8], [1.0, 0.0]], "dimensions": 2}}
                        """);
    }

    private static EmbedRequest embedRequest() {
        return EmbedRequest.builder()
                .orgId("workspace-1")
                .workspaceId("workspace-1")
                .inputs(List.of("refund a duplicate invoice charge", "export traces to csv"))
                .build();
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
