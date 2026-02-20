package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.OpenTelemetryMapper;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.protobuf.ByteString;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.junit.experimental.runners.Enclosed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.runner.RunWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.FAKE_API_KEY_MESSAGE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.NO_API_KEY_RESPONSE;
import static com.comet.opik.api.resources.utils.TestHttpClientUtils.UNAUTHORIZED_RESPONSE;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@Slf4j
@RunWith(Enclosed.class)
@ExtendWith(DropwizardAppExtensionProvider.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OpenTelemetryResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/otel/v1/traces";
    public static final String METRICS_URL_TEMPLATE = "%s/v1/private/otel/v1/metrics";
    public static final String API_KEY = UUID.randomUUID().toString();
    public static final String USER = UUID.randomUUID().toString();
    public static final String WORKSPACE_ID = UUID.randomUUID().toString();
    public static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MY_SQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private String baseURI;
    private ClientSupport client;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) throws SQLException {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        log.info(client.toString());

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Api Key Authentication:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ApiKey {

        private static final String fakeApikey = UUID.randomUUID().toString();
        private static final String okApikey = UUID.randomUUID().toString();

        static Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(okApikey, null, true, null),
                    arguments(okApikey, "Demo Project", true, null),
                    arguments(fakeApikey, null, false, UNAUTHORIZED_RESPONSE),
                    arguments("", null, false, NO_API_KEY_RESPONSE));
        }

        static Stream<Arguments> otelMetricsPayloads() {
            return Stream.of(
                    arguments("application/x-protobuf", Entity.entity(new byte[]{1, 2, 3}, "application/x-protobuf")),
                    arguments("application/json", Entity.json("{}")));
        }

        static Stream<Arguments> otelMetricsRequests() {
            return credentials()
                    .flatMap(credentials -> otelMetricsPayloads().map(metricsPayload -> arguments(credentials.get()[0],
                            credentials.get()[1], credentials.get()[2], credentials.get()[3],
                            metricsPayload.get()[0], metricsPayload.get()[1])));
        }

        @BeforeEach
        void setUp() {
            wireMock.server().stubFor(
                    post(urlPathEqualTo("/opik/auth"))
                            .withHeader(HttpHeaders.AUTHORIZATION, equalTo(fakeApikey))
                            .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                            .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                    .withJsonBody(JsonUtils.readTree(
                                            new ReactServiceErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("ingest otel traces via protobuf")
        void testOtelProtobufRequests(String apiKey, String projectName, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var otelTraceId = UUID.randomUUID().toString().getBytes(); // otel uses 128-bit, but it doesnt matter
            var parentSpanId = UUID.randomUUID().toString().getBytes();// otel uses  64-bit, but it doesnt matter

            // creates a batch with parent + 4-9 spans
            var otelSpans = new ArrayList<Span>();
            otelSpans.add(Span.newBuilder()
                    .setName("parent span")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setSpanId(ByteString.copyFrom(parentSpanId))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .build());

            var batchSize = RandomUtils.insecure().randomInt(4, 9);
            IntStream.range(0, batchSize)
                    .mapToObj(i -> Span.newBuilder()
                            .setName("span " + i)
                            .setTraceId(ByteString.copyFrom(otelTraceId))
                            .setParentSpanId(ByteString.copyFrom(parentSpanId))
                            .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                            .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                            .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                            .build())
                    .forEach(otelSpans::add);

            // split batch in 2 small batches so we can test redis trace id mapping
            // lets shuffle th
            var otelSpansBatch1 = otelSpans.subList(0, batchSize / 2);
            Collections.shuffle(otelSpansBatch1);
            var otelSpansBatch2 = otelSpans.subList(batchSize / 2, batchSize);
            Collections.shuffle(otelSpansBatch2);

            // opik trace id should be created with the earliest timestamp in the batch;
            // we use batch as its the first server will receive
            var minTimestamp = otelSpansBatch1.stream().map(Span::getStartTimeUnixNano).min(Long::compareTo)
                    .orElseThrow();
            var minTimestampMs = Duration.ofNanos(minTimestamp).toMillis();
            var expectedOpikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId, minTimestampMs);
            var expectedOpikParentSpanId = OpenTelemetryMapper.convertOtelIdToUUIDv7(parentSpanId, minTimestampMs);

            // send batch with the half the spans
            sendProtobufTraces(otelSpansBatch1, projectName, workspaceName, apiKey, expected, errorMessage);
            // send another
            sendProtobufTraces(otelSpansBatch2, projectName, workspaceName, apiKey, expected, errorMessage);

            if (expected) {
                // the otel span batch should have created a trace with this expect traceId. Check it.
                Trace trace = traceResourceClient.getById(expectedOpikTraceId, workspaceName, apiKey);
                assertThat(trace.id()).isEqualTo(expectedOpikTraceId);

                var projectNameOrDefault = StringUtils.isNotEmpty(projectName)
                        ? projectName
                        : ProjectService.DEFAULT_PROJECT;

                // the otel span batch should have created spans with this expected traceId. Check it.
                var generatedSpanPage = spanResourceClient.getByTraceIdAndProject(expectedOpikTraceId,
                        projectNameOrDefault,
                        workspaceName, apiKey);

                assertThat(generatedSpanPage.size()).isEqualTo(otelSpansBatch1.size() + otelSpansBatch2.size());

                // in the test we have root span and 1st level spans, so check if our calculated parent span appears
                generatedSpanPage.content().forEach(span -> {
                    if (span.parentSpanId() != null) {
                        assertThat(span.parentSpanId()).isEqualTo(expectedOpikParentSpanId);
                    } else {
                        assertThat(span.id()).isEqualTo(expectedOpikParentSpanId);
                    }
                });

            }
        }

        @ParameterizedTest
        @MethodSource("credentials")
        @DisplayName("ingest otel traces via json")
        void testOtelJsonRequests(String apiKey, String projectName, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            // using example payload from integration; it will be protobuffed when ingested,
            // so we just need to make sure the parsing works
            String payload2 = "{\"resourceSpans\":[{\"resource\":{\"attributes\":[{\"key\":\"service.name\",\"value\":{\"stringValue\":\"my-service\"}}]},\"scopeSpans\":[{\"spans\":[{\"traceId\":\"%s\",\"spanId\":\"%s\",\"name\":\"example-span\",\"kind\":\"SPAN_KIND_SERVER\",\"startTimeUnixNano\":\"%d\",\"endTimeUnixNano\":\"1623456790000000000\"}]}]}]}";

            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            String otelTraceId = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
            String spanId = Base64.getEncoder().encodeToString(UUID.randomUUID().toString().getBytes());
            long startTimeUnixNano = System.currentTimeMillis() * 1_000_000;

            String injectedPayload = String.format(payload2, otelTraceId, spanId, startTimeUnixNano);

            Entity<String> payload = Entity.json(injectedPayload);

            sendBatch(payload, "application/json", projectName, workspaceName, apiKey, expected, errorMessage);
        }

        @ParameterizedTest
        @MethodSource("otelMetricsRequests")
        @DisplayName("ingest otel metrics")
        void ingestOtelMetrics(String apiKey, String projectName, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage, String mediaType, Entity<?> payload) {
            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);
            sendBatch(payload, mediaType, projectName, workspaceName, apiKey, expected, 501,
                    new ErrorMessage(501, "OpenTelemetry metrics ingestion is not yet supported"),
                    errorMessage, METRICS_URL_TEMPLATE);
        }

        void sendBatch(Entity<?> payload, String mediaType, String projectName, String workspaceName, String apiKey,
                boolean expected, ErrorMessage errorMessage) {

            sendBatch(payload, mediaType, projectName, workspaceName, apiKey, expected, 200, null,
                    errorMessage, URL_TEMPLATE);
        }

        void sendBatch(Entity<?> payload, String mediaType, String projectName, String workspaceName, String apiKey,
                boolean expected, int expectedSuccessStatus, ErrorMessage expectedSuccessError,
                ErrorMessage errorMessage, String endpointTemplate) {

            var requestBuilder = client.target(endpointTemplate.formatted(baseURI))
                    .request(mediaType)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName);

            if (StringUtils.isNotEmpty(projectName)) {
                requestBuilder.header(RequestContext.PROJECT_NAME, projectName);
            }

            try (Response actualResponse = requestBuilder.post(payload)) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(expectedSuccessStatus);
                    if (expectedSuccessError != null) {
                        assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                                .isEqualTo(expectedSuccessError);
                    }

                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);
                }
            }
        }

        void sendProtobufTraces(List<Span> otelSpans, String projectName, String workspaceName, String apiKey,
                boolean expected, ErrorMessage errorMessage) {

            var protoBuilder = ExportTraceServiceRequest.newBuilder()
                    .addResourceSpans(ResourceSpans.newBuilder()
                            .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(otelSpans).build())
                            .build())
                    .build();

            byte[] requestProtobufBytes = protoBuilder.toByteArray();
            var payload = Entity.entity(requestProtobufBytes, "application/x-protobuf");

            sendBatch(payload, "application/x-protobuf", projectName, workspaceName, apiKey, expected, errorMessage);
        }

        @ParameterizedTest
        @ValueSource(strings = {"model_name", "gen_ai.request.model", "gen_ai.response.model", "gen_ai.request_model",
                "gen_ai.response_model"})
        void testRuleMapping(String modelKey) {
            String randomKeyArray = UUID.randomUUID().toString();
            String randomKeyJson = UUID.randomUUID().toString();
            String randomKeyInt = UUID.randomUUID().toString();

            var attributes = List.of(
                    KeyValue.newBuilder().setKey("gen_ai.system")
                            .setValue(AnyValue.newBuilder().setStringValue("openai"))
                            .build(),
                    KeyValue.newBuilder().setKey(modelKey).setValue(AnyValue.newBuilder().setStringValue("gpt-4o"))
                            .build(),
                    KeyValue.newBuilder().setKey("code.line").setValue(AnyValue.newBuilder().setIntValue(11)).build(),
                    KeyValue.newBuilder().setKey("input")
                            .setValue(AnyValue.newBuilder().setStringValue("{\"key\": \"value\"}")).build(),
                    KeyValue.newBuilder().setKey("tools")
                            .setValue(AnyValue.newBuilder().setStringValue("[\"key\", \"value\"]")).build(),
                    KeyValue.newBuilder().setKey("all_messages")
                            .setValue(AnyValue.newBuilder().setStringValue("[\"key\", \"value\"]")).build(),
                    KeyValue.newBuilder().setKey("tool_responses")
                            .setValue(AnyValue.newBuilder().setStringValue("[\"key\", \"value\"]")).build(),

                    KeyValue.newBuilder().setKey("smolagents.single")
                            .setValue(AnyValue.newBuilder().setStringValue("value")).build(),
                    KeyValue.newBuilder().setKey("smolagents.node")
                            .setValue(AnyValue.newBuilder().setStringValue("{\"key\": \"value\"}")).build(),
                    KeyValue.newBuilder().setKey("smolagents.array")
                            .setValue(AnyValue.newBuilder().setStringValue("[\"key\", \"value\"]")).build(),

                    KeyValue.newBuilder().setKey(randomKeyArray)
                            .setValue(AnyValue.newBuilder().setStringValue("[\"key\", \"value\"]")).build(),
                    KeyValue.newBuilder().setKey(randomKeyJson)
                            .setValue(AnyValue.newBuilder().setStringValue("{\"key\": \"value\"}")).build(),
                    KeyValue.newBuilder().setKey(randomKeyInt)
                            .setValue(AnyValue.newBuilder().setIntValue(3)).build(),

                    KeyValue.newBuilder().setKey("opik.tags")
                            .setValue(AnyValue.newBuilder()
                                    .setStringValue("[\"machine-learning\", \"nlp\", \"chatbot\"]").build())
                            .build(),
                    KeyValue.newBuilder().setKey("opik.metadata")
                            .setValue(AnyValue.newBuilder().setStringValue("{\"foo\": \"bar\"}").build()).build(),
                    KeyValue.newBuilder().setKey("opik.metadata.inline")
                            .setValue(AnyValue.newBuilder().setStringValue("inline_value").build()).build());

            var spanBuilder = com.comet.opik.api.Span.builder()
                    .id(UUID.randomUUID())
                    .traceId(UUID.randomUUID())
                    .projectId(UUID.randomUUID())
                    .startTime(Instant.now());

            OpenTelemetryMapper.enrichSpanWithAttributes(spanBuilder, attributes, null, null);

            var span = spanBuilder.build();

            // checks key-values - we know there are no rules associated with
            assertThat(span.input().get(randomKeyArray)).size().isEqualTo(2);
            assertThat(span.input().get(randomKeyJson).get("key").asText()).isEqualTo("value");
            assertThat(span.input().get(randomKeyInt).asInt()).isEqualTo(3);

            // checks key-values with rules
            assertThat(span.model()).isEqualTo("gpt-4o");
            assertThat(span.provider()).isEqualTo("openai");
            assertThat(span.type()).isEqualTo(SpanType.llm);

            assertThat(span.metadata().get("code.line").asInt()).isEqualTo(11);
            assertThat(span.metadata().get("smolagents.single").asText()).isEqualTo("value");
            assertThat(span.metadata().get("smolagents.node").get("key").asText()).isEqualTo("value");
            assertThat(span.metadata().get("smolagents.array").isArray()).isTrue();

            assertThat(span.input().get("input").get("key").asText()).isEqualTo("value");
            assertThat(span.input().get("tools").isArray()).isEqualTo(Boolean.TRUE);
            assertThat(span.input().get("all_messages").isArray()).isEqualTo(Boolean.TRUE);

            assertThat(span.output().get("tool_responses").isArray()).isEqualTo(Boolean.TRUE);

            // checks key-values for tags
            assertThat(span.tags()).isNotEmpty();
            assertThat(span.tags()).contains("machine-learning", "nlp", "chatbot");

            // check metadata
            assertThat(span.metadata()).isNotEmpty();
            assertThat(span.metadata().get("opik.metadata").get("foo").asText()).isEqualTo("bar");
            assertThat(span.metadata().get("opik.metadata.inline").asText()).isEqualTo("inline_value");
        }

        @Test
        @DisplayName("test thread_id support in OpenTelemetry")
        void testThreadIdSupport() {
            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var parentSpanId = UUID.randomUUID().toString().getBytes();
            String threadId = "test-thread-123";

            // Create a root span with thread_id attribute
            var rootSpan = Span.newBuilder()
                    .setName("root span")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setSpanId(ByteString.copyFrom(parentSpanId))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .addAttributes(KeyValue.newBuilder()
                            .setKey("thread_id")
                            .setValue(AnyValue.newBuilder().setStringValue(threadId))
                            .build())
                    .build();

            // Create a child span
            var childSpan = Span.newBuilder()
                    .setName("child span")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setParentSpanId(ByteString.copyFrom(parentSpanId))
                    .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 500) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .build();

            var otelSpans = List.of(rootSpan, childSpan);

            // Calculate expected Opik trace ID
            var minTimestamp = otelSpans.stream().map(Span::getStartTimeUnixNano).min(Long::compareTo).orElseThrow();
            var minTimestampMs = Duration.ofNanos(minTimestamp).toMillis();
            var expectedOpikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId, minTimestampMs);

            // Send the spans
            sendProtobufTraces(otelSpans, "Test Project", workspaceName, okApikey, true, null);

            // Verify the trace was created with the correct thread_id
            Trace trace = traceResourceClient.getById(expectedOpikTraceId, workspaceName, okApikey);
            assertThat(trace.id()).isEqualTo(expectedOpikTraceId);
            assertThat(trace.threadId()).isEqualTo(threadId);

            // Verify the spans were created
            var generatedSpanPage = spanResourceClient.getByTraceIdAndProject(expectedOpikTraceId,
                    "Test Project", workspaceName, okApikey);
            assertThat(generatedSpanPage.size()).isEqualTo(2);

            // Verify the root span has thread_id in metadata
            var rootSpanFromDb = generatedSpanPage.content().stream()
                    .filter(span -> span.parentSpanId() == null)
                    .findFirst()
                    .orElseThrow();
            assertThat(rootSpanFromDb.metadata().get("thread_id").asText()).isEqualTo(threadId);
        }

        @Test
        @DisplayName("test gen_ai.conversation.id maps to threadId in OpenTelemetry")
        void testGenAiConversationIdSupport() {
            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var parentSpanId = UUID.randomUUID().toString().getBytes();
            String conversationId = "conversation-456";

            // Create a root span with gen_ai.conversation.id attribute
            var rootSpan = Span.newBuilder()
                    .setName("root span")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setSpanId(ByteString.copyFrom(parentSpanId))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .addAttributes(KeyValue.newBuilder()
                            .setKey("gen_ai.conversation.id")
                            .setValue(AnyValue.newBuilder().setStringValue(conversationId))
                            .build())
                    .build();

            // Create a child span
            var childSpan = Span.newBuilder()
                    .setName("child span")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setParentSpanId(ByteString.copyFrom(parentSpanId))
                    .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 500) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .build();

            var otelSpans = List.of(rootSpan, childSpan);

            // Calculate expected Opik trace ID
            var minTimestamp = otelSpans.stream().map(Span::getStartTimeUnixNano).min(Long::compareTo).orElseThrow();
            var minTimestampMs = Duration.ofNanos(minTimestamp).toMillis();
            var expectedOpikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId, minTimestampMs);

            // Send the spans
            sendProtobufTraces(otelSpans, "Test Project", workspaceName, okApikey, true, null);

            // Verify the trace was created with gen_ai.conversation.id mapped to threadId
            Trace trace = traceResourceClient.getById(expectedOpikTraceId, workspaceName, okApikey);
            assertThat(trace.id()).isEqualTo(expectedOpikTraceId);
            assertThat(trace.threadId()).isEqualTo(conversationId);

            // Verify the spans were created
            var generatedSpanPage = spanResourceClient.getByTraceIdAndProject(expectedOpikTraceId,
                    "Test Project", workspaceName, okApikey);
            assertThat(generatedSpanPage.size()).isEqualTo(2);

            // Verify the root span has thread_id in metadata (mapped from gen_ai.conversation.id)
            var rootSpanFromDb = generatedSpanPage.content().stream()
                    .filter(span -> span.parentSpanId() == null)
                    .findFirst()
                    .orElseThrow();
            assertThat(rootSpanFromDb.metadata().get("thread_id").asText()).isEqualTo(conversationId);
        }

        @Test
        @DisplayName("test integer thread_id is properly stored in OpenTelemetry traces")
        void testIntegerThreadIdSupport() {
            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var parentSpanId = UUID.randomUUID().toString().getBytes();
            long integerThreadId = 98765L;

            // Create a root span with thread_id as integer (per OpenTelemetry thread.id spec)
            var rootSpan = Span.newBuilder()
                    .setName("root span with int thread_id")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setSpanId(ByteString.copyFrom(parentSpanId))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 1_000) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .addAttributes(KeyValue.newBuilder()
                            .setKey("thread_id")
                            .setValue(AnyValue.newBuilder().setIntValue(integerThreadId))
                            .build())
                    .build();

            // Create a child span
            var childSpan = Span.newBuilder()
                    .setName("child span")
                    .setTraceId(ByteString.copyFrom(otelTraceId))
                    .setParentSpanId(ByteString.copyFrom(parentSpanId))
                    .setSpanId(ByteString.copyFrom(UUID.randomUUID().toString().getBytes()))
                    .setStartTimeUnixNano((System.currentTimeMillis() - 500) * 1_000_000L)
                    .setEndTimeUnixNano(System.currentTimeMillis() * 1_000_000L)
                    .build();

            var otelSpans = List.of(rootSpan, childSpan);

            // Calculate expected Opik trace ID
            var minTimestamp = otelSpans.stream().map(Span::getStartTimeUnixNano).min(Long::compareTo).orElseThrow();
            var minTimestampMs = Duration.ofNanos(minTimestamp).toMillis();
            var expectedOpikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId, minTimestampMs);

            // Send the spans
            sendProtobufTraces(otelSpans, "Test Project", workspaceName, okApikey, true, null);

            // Verify the trace was created with integer thread_id converted to string
            Trace trace = traceResourceClient.getById(expectedOpikTraceId, workspaceName, okApikey);
            assertThat(trace.id()).isEqualTo(expectedOpikTraceId);
            assertThat(trace.threadId()).isEqualTo(String.valueOf(integerThreadId));

            // Verify the spans were created
            var generatedSpanPage = spanResourceClient.getByTraceIdAndProject(expectedOpikTraceId,
                    "Test Project", workspaceName, okApikey);
            assertThat(generatedSpanPage.size()).isEqualTo(2);

            // Verify the root span has thread_id in metadata as integer
            var rootSpanFromDb = generatedSpanPage.content().stream()
                    .filter(span -> span.parentSpanId() == null)
                    .findFirst()
                    .orElseThrow();
            assertThat(rootSpanFromDb.metadata().get("thread_id").asLong()).isEqualTo(integerThreadId);
        }

    }
}
