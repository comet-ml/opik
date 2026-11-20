package com.comet.opik.infrastructure.inference;

import com.comet.opik.TestConfigUtils;
import com.comet.opik.api.resources.utils.TestHttpClientUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import jakarta.ws.rs.client.Client;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
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
import java.util.UUID;
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
    private static final String REALTIME_GENERATE_PATH = "/v1/generate";
    private static final String REALTIME_GENERATE = "POST /v1/generate";
    /** config-test.yml's inference.retry.maxRetries (2), plus the first attempt. */
    private static final int ATTEMPTS = 3;
    /** config-test.yml's inference.limits.maxStatusJobIds. */
    private static final int MAX_STATUS_JOB_IDS = 1_000;

    private static final String EMBEDDED_BODY = """
            {"request_id": "r-1", "usage": {"input_tokens": 9, "output_tokens": 0}, "cache_hit": false,
             "latency_ms": 12, "result": {"embeddings": [[0.6, 0.8], [1.0, 0.0]], "dimensions": 2}}
            """;
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
        client = newClient(TestHttpClientUtils.client(), config(Duration.ofSeconds(5), 1024 * 1024));
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
        var tooMany = IntStream.rangeClosed(0, MAX_STATUS_JOB_IDS)
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
                .hasSize(ATTEMPTS);
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
                .hasSize(ATTEMPTS);
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
                .hasSize(ATTEMPTS);
    }

    @ParameterizedTest(name = "chunked {0}")
    @ValueSource(booleans = {false, true})
    void responseBodyOfExactlyTheCapIsRead(boolean chunked) {
        client = newClient(TestHttpClientUtils.client(), maxResponseBytes(EMBEDDED_BODY.length()));
        var response = embedded().withGzipDisabled(true);
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(chunked
                ? response.withChunkedDribbleDelay(2, 10)
                : response));

        assertThat(client.embed(embedRequest(), later()).block().result().embeddings()).hasDimensions(2, 2);
    }

    static Stream<Arguments> bodiesOverTheCap() {
        var error = "{\"error\":{\"code\":\"overloaded\",\"message\":\"m\",\"retryable\":true}}";
        return Stream.of(arguments(200, EMBEDDED_BODY, false), arguments(200, EMBEDDED_BODY, true),
                arguments(503, error, false), arguments(503, error, true));
    }

    /** Chunked, the body has no Content-Length, so only the capped read can catch the byte over. */
    @ParameterizedTest(name = "status {0}, chunked {2}")
    @MethodSource("bodiesOverTheCap")
    void responseBodyOverTheCapFailsTheCallUnparsed(int status, String body, boolean chunked) {
        client = newClient(TestHttpClientUtils.client(), maxResponseBytes(body.length() - 1));
        var response = aResponse().withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody(body)
                .withGzipDisabled(true);
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(chunked
                ? response.withChunkedDribbleDelay(2, 10)
                : response));

        assertThatThrownBy(() -> client.embed(embedRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    // An error body over the cap is not parsed either: its code would be overloaded.
                    assertThat(exception.getCode()).isEqualTo("upstream_error");
                    assertThat(exception.getHttpStatus()).isEqualTo(status);
                    assertThat(exception.getCause()).hasMessageContaining("maxResponseBytes");
                });
    }

    @Test
    void responseBodyOverTheCapIsNotReadToItsEnd() {
        client = newClient(TestHttpClientUtils.client(), maxResponseBytes(1024));
        // Chunked, so no Content-Length warns the client: only the capped read stops it. 10 MiB over 30 s, so a client
        // that read it to its end would take 30 s.
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("{\"job_id\":\"" + "x".repeat(10 * 1024 * 1024) + "\"}")
                .withGzipDisabled(true)
                .withChunkedDribbleDelay(1_000, 30_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, false, later()).block(Duration.ofSeconds(10)))
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.getCode()).isEqualTo("upstream_error");
                    assertThat(exception.getCause()).hasMessageContaining("maxResponseBytes");
                });

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH)))).hasSize(1);
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
        RETRYABLE(true, ATTEMPTS),
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
        client = newClient(TestHttpClientUtils.client(), config(Duration.ofSeconds(5), 2_000));
        server.stubFor(post(urlEqualTo(GENERATE_PATH)).willReturn(accepted()));

        assertThatThrownBy(() -> client.submitGenerate(generateRequest(2, 1_000), later()).block())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("maxJobBytes");
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    @Test
    void noAttemptOutlastsTheReadTimeout() {
        var readTimeout = Duration.ofMillis(300);
        client = newClient(TestHttpClientUtils.client(), config(readTimeout, 1024 * 1024));
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(jobView().withFixedDelay(5_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, false, later()).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(server.findAll(getRequestedFor(urlPathEqualTo(JOB_PATH))))
                .hasSize(ATTEMPTS);
        // Three attempts of 300 ms plus 0.1 s and 0.2 s of backoff, never one 5 s response.
        assertThat(elapsed).isLessThan(Duration.ofMillis(3_000));
    }

    @Test
    void noAttemptOutlastsTheDeadline() {
        server.stubFor(get(urlPathEqualTo(JOB_PATH)).willReturn(jobView().withFixedDelay(5_000)));

        var started = System.nanoTime();
        // The socket's read timeout, cut to the deadline, and the bound on the whole call fire together.
        assertThatThrownBy(() -> client.getJob(JOB_ID, 0, false, Instant.now().plusMillis(300)).block())
                .isExactlyInstanceOf(InferenceException.class);
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
    void embedSendsAContractValidBodyAndReadsTheVectors() {
        var request = embedRequest();
        var vectors = randomVectors(request.inputs().size());
        var response = embedResponse(vectors);
        assertThat(InferenceContract.validateEndpoint(InferenceContract.EMBED, "response", response)).isEmpty();
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(okJson(response)));

        var actual = client.embed(request, Instant.now().plusSeconds(600)).block();

        assertThat(actual).usingRecursiveComparison().isEqualTo(EmbedResponse.builder()
                .result(EmbedResult.builder().embeddings(vectors).dimensions(4).build())
                .build());
        var sent = single(postRequestedFor(urlEqualTo(EMBED_PATH)));
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + CREDENTIAL);
        var body = (ObjectNode) json(sent);
        assertThat(InferenceContract.validateEndpoint(InferenceContract.EMBED, "request", body)).isEmpty();
        assertThat(body.remove("request_id").asText()).isNotBlank();
        var expected = NODES.objectNode()
                .put("org_id", request.orgId())
                .put("cache", false)
                // The time left, capped at the service's maximum of 60 s.
                .put("deadline_ms", 60_000);
        request.inputs().forEach(expected.putArray("inputs")::add);
        assertThat(body).isEqualTo(expected);
    }

    @Test
    void embedQuotaExceededIsNotRetried() {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(error("quota_exceeded", 429)));

        assertThatThrownBy(() -> client.embed(embedRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isQuotaExceeded()).isTrue());
        assertThat(server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)))).hasSize(1);
    }

    @Test
    void embedDeadlineExceededIsNotRetried() {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(error("deadline_exceeded", 504)));

        assertThatThrownBy(() -> client.embed(embedRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isDeadlineExceeded()).isTrue());
        assertThat(server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)))).hasSize(1);
    }

    static Stream<Arguments> malformedEmbedResults() {
        return Stream.of(
                arguments("one vector for two inputs", "{\"embeddings\": [[0.6, 0.8]], \"dimensions\": 2}"),
                arguments("a null vector", "{\"embeddings\": [[0.6, 0.8], null], \"dimensions\": 2}"),
                arguments("a null in a vector", "{\"embeddings\": [[0.6, 0.8], [1.0, null]], \"dimensions\": 2}"),
                arguments("a vector shorter than dimensions",
                        "{\"embeddings\": [[0.6, 0.8], [1.0]], \"dimensions\": 2}"),
                arguments("no dimensions", "{\"embeddings\": [[0.6, 0.8], [1.0, 0.0]]}"),
                arguments("no vectors", "{\"dimensions\": 2}"));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("malformedEmbedResults")
    void embedMalformedResultIsAnUpstreamError(String description, String result) {
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"request_id": "r-1", "cache_hit": false, "latency_ms": 12, "result": %s}
                        """.formatted(result))));

        assertThatThrownBy(() -> client.embed(embedRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
    }

    @Test
    void embedRetrySendsTheSameRequestId() {
        stubSequence(() -> post(urlEqualTo(EMBED_PATH)), error("overloaded", 503),
                okJson(embedResponse(randomVectors(2))));

        client.embed(embedRequest(), later()).block();

        var sent = server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)));
        assertThat(sent).hasSize(2);
        assertThat(json(sent.get(1)).path("request_id")).isEqualTo(json(sent.get(0)).path("request_id"));
    }

    @Test
    void embedTricklingBodyIsCutOffAtTheDeadline() {
        // Each chunk arrives well within the read timeout, so only a bound on the whole call stops it.
        server.stubFor(post(urlEqualTo(EMBED_PATH)).willReturn(okJson(embedResponse(randomVectors(2)))
                .withGzipDisabled(true)
                .withChunkedDribbleDelay(20, 3_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.embed(embedRequest(), Instant.now().plusMillis(500)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.isOutOfTime()).isTrue());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofMillis(1_500));
        assertThat(server.findAll(postRequestedFor(urlEqualTo(EMBED_PATH)))).hasSize(1);
    }

    @Test
    void generateSendsAContractValidBodyAndReadsTheOutput() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(generated()));

        var response = client.generate(generateRequest(), later()).block();

        assertThat(response.requestId()).isEqualTo("r-1");
        assertThat(response.usage()).isEqualTo(Usage.builder().inputTokens(812).outputTokens(64).build());
        assertThat(response.result().output().path("topics").get(0).path("name").asText()).isEqualTo("Billing");
        var sent = single(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH)));
        assertThat(sent.getHeader("Authorization")).isEqualTo("Bearer " + CREDENTIAL);
        var body = json(sent);
        assertThat(InferenceContract.validateEndpoint(REALTIME_GENERATE, "request", body)).isEmpty();
        assertThat(body.path("org_id").asText()).isEqualTo("workspace-1");
        assertThat(body.path("lane").asText()).isEqualTo("batch");
        assertThat(body.path("max_tokens").asInt()).isEqualTo(2500);
        assertThat(body.path("schema").path("type").asText()).isEqualTo("object");
        assertThat(body.path("request_id").asText()).isNotBlank();
        assertThat(body.path("deadline_ms").asLong()).isBetween(1L, 60_000L);
        assertThat(InferenceContract.validateEndpoint(REALTIME_GENERATE, "response",
                InferenceJson.MAPPER.valueToTree(response))).isEmpty();
    }

    @Test
    void generateKeepsTheSchemaKeyOrder() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(generated()));
        var schema = NODES.objectNode().put("type", "object");
        var properties = schema.putObject("properties");
        properties.putObject("topics").put("type", "array");
        properties.putObject("other").put("type", "array");
        schema.putArray("required").add("topics").add("other");

        client.generate(generateRequest().toBuilder().schema(schema).build(), later()).block();

        var sent = single(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH))).getBodyAsString();
        assertThat(sent).contains("\"schema\":{\"type\":\"object\",\"properties\":{\"topics\":{\"type\":\"array\"},"
                + "\"other\":{\"type\":\"array\"}},\"required\":[\"topics\",\"other\"]}");
    }

    @Test
    void generateQuotaExceededIsNotRetried() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH))
                .willReturn(error("quota_exceeded", 429).withHeader("Retry-After", "2")));

        assertThatThrownBy(() -> client.generate(generateRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.isQuotaExceeded()).isTrue();
                    assertThat(exception.getHttpStatus()).isEqualTo(429);
                });
        assertThat(server.findAll(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH)))).hasSize(1);
    }

    @Test
    void generateSchemaViolationIsNotRetried() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(error("schema_violation", 500)));

        assertThatThrownBy(() -> client.generate(generateRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class, exception -> {
                    assertThat(exception.isSchemaViolation()).isTrue();
                    assertThat(exception.getHttpStatus()).isEqualTo(500);
                });
        assertThat(server.findAll(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH)))).hasSize(1);
    }

    @Test
    void generateDeadlineExceededIsNotRetried() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(error("deadline_exceeded", 504)));

        assertThatThrownBy(() -> client.generate(generateRequest(), later()).block())
                .isInstanceOf(InferenceException.class);
        assertThat(server.findAll(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH)))).hasSize(1);
    }

    @Test
    void generateServerErrorIsRetriedWithTheSameRequestIdThenSucceeds() {
        stubSequence(() -> post(urlEqualTo(REALTIME_GENERATE_PATH)), error("overloaded", 503),
                error("overloaded", 503), generated());

        var response = client.generate(generateRequest(), later()).block();

        assertThat(response.result().output().path("other")).isNotNull();
        var sent = server.findAll(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH)));
        assertThat(sent).hasSize(ATTEMPTS);
        var requestId = json(sent.getFirst()).path("request_id").asText();
        assertThat(sent).allSatisfy(request -> assertThat(json(request).path("request_id").asText())
                .isEqualTo(requestId));
    }

    @Test
    void generateTimeoutStopsAtTheDeadline() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(generated().withFixedDelay(5_000)));

        var started = System.nanoTime();
        assertThatThrownBy(() -> client.generate(generateRequest(), Instant.now().plusSeconds(1)).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo(InferenceException.OUT_OF_TIME));
        var elapsed = Duration.ofNanos(System.nanoTime() - started);

        assertThat(server.findAll(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH)))).hasSize(1);
        assertThat(elapsed).isLessThan(Duration.ofMillis(3_000));
    }

    @ParameterizedTest(name = "body ''{0}''")
    @ValueSource(strings = {"<html>bad gateway</html>", "{\"request_id\": \"r-1\"}", "null"})
    void generateNonJsonOrIncompleteAnswerIsAnUpstreamError(String answer) {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json").withBody(answer)));

        assertThatThrownBy(() -> client.generate(generateRequest(), later()).block())
                .isInstanceOfSatisfying(InferenceException.class,
                        exception -> assertThat(exception.getCode()).isEqualTo("upstream_error"));
    }

    @Test
    void generateDeadlineIsCutToTheTimeLeftAndTheLanesMaximum() {
        server.stubFor(post(urlEqualTo(REALTIME_GENERATE_PATH)).willReturn(generated()));

        client.generate(generateRequest(), Instant.now().plusSeconds(600)).block();
        client.generate(generateRequest().toBuilder().lane(GenerateLane.REALTIME).build(),
                Instant.now().plusSeconds(600)).block();
        client.generate(generateRequest(), Instant.now().plusSeconds(5)).block();

        var sent = server.findAll(postRequestedFor(urlEqualTo(REALTIME_GENERATE_PATH))).stream()
                .map(request -> json(request).path("deadline_ms").asLong())
                .toList();
        assertThat(sent).hasSize(3);
        var limits = TestConfigUtils.loadConfigTest().getInference().getLimits();
        assertThat(sent.get(0)).isEqualTo(limits.getMaxGenerateBatchDeadline().toMilliseconds());
        assertThat(sent.get(1)).isEqualTo(limits.getMaxGenerateRealtimeDeadline().toMilliseconds());
        assertThat(sent.get(2)).isBetween(1L, 5_000L);
    }

    @Test
    void generateRejectsABadRequestWithoutSending() {
        assertThatThrownBy(() -> client.generate(generateRequest().toBuilder().messages(List.of()).build(), later()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.generate(generateRequest().toBuilder().maxTokens(4097).build(), later()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(server.getAllServeEvents()).isEmpty();
    }

    private static Instant later() {
        return Instant.now().plusSeconds(60);
    }

    private OpikConfiguration config(Duration readTimeout, long maxJobBytes) {
        var config = TestConfigUtils.loadConfigTest();
        config.getInference().setUrl(server.baseUrl());
        config.getInference().setCredential(CREDENTIAL);
        config.getInference().setConnectTimeout(io.dropwizard.util.Duration.seconds(1));
        config.getInference().setReadTimeout(io.dropwizard.util.Duration.milliseconds(readTimeout.toMillis()));
        config.getAiLabels().setMaxJobBytes(maxJobBytes);
        return config;
    }

    private OpikConfiguration maxResponseBytes(long maxResponseBytes) {
        var config = config(Duration.ofSeconds(5), 1024 * 1024);
        config.getInference().setMaxResponseBytes(maxResponseBytes);
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
                .withBody(EMBEDDED_BODY);
    }

    private static ResponseDefinitionBuilder generated() {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"request_id": "r-1", "usage": {"input_tokens": 812, "output_tokens": 64}, "cache_hit": false,
                         "latency_ms": 2140, "result": {"output": {"topics": [{"name": "Billing",
                         "definition": "Questions about invoices.", "clusters": [0, 2]}], "other": [1]}}}
                        """);
    }

    private static GenerateRequest generateRequest() {
        var schema = NODES.objectNode().put("type", "object");
        return GenerateRequest.builder()
                .orgId("workspace-1")
                .workspaceId("workspace-1")
                .cache(true)
                .lane(GenerateLane.BATCH)
                .messages(List.of(Message.builder().role(Message.Role.USER).content("Name these groups").build()))
                .schema(schema)
                .maxTokens(2500)
                .temperature(0)
                .build();
    }

    private static ResponseDefinitionBuilder okJson(JsonNode body) {
        return aResponse().withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withBody(body.toString());
    }

    private static ObjectNode embedResponse(float[][] vectors) {
        var response = NODES.objectNode()
                .put("request_id", UUID.randomUUID().toString())
                .put("cache_hit", false)
                .put("latency_ms", RandomUtils.secure().randomInt(1, 1_000));
        response.putObject("usage").put("input_tokens", RandomUtils.secure().randomInt(1, 1_000))
                .put("output_tokens", 0);
        var result = response.putObject("result").put("dimensions", vectors[0].length);
        var embeddings = result.putArray("embeddings");
        for (var vector : vectors) {
            var array = embeddings.addArray();
            for (var value : vector) {
                array.add(value);
            }
        }
        return response;
    }

    private static float[][] randomVectors(int count) {
        var vectors = new float[count][4];
        for (var vector : vectors) {
            for (var i = 0; i < vector.length; i++) {
                vector[i] = RandomUtils.secure().randomFloat(0, 1);
            }
        }
        return vectors;
    }

    private static EmbedRequest embedRequest() {
        return EmbedRequest.builder()
                .orgId(UUID.randomUUID().toString())
                // Two, as EMBEDDED_BODY has two vectors.
                .inputs(IntStream.range(0, 2).mapToObj(index -> RandomStringUtils.secure().nextAlphanumeric(32))
                        .toList())
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

    private static InferenceClient newClient(Client httpClient, OpikConfiguration config) {
        return new InferenceClient(httpClient, config.getInference(), config.getAiLabels());
    }
}
