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
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.OpenTelemetryMapper;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.protobuf.ByteString;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.RandomUtils;
import org.apache.commons.lang3.StringUtils;
import org.jdbi.v3.core.Jdbi;
import org.junit.experimental.runners.Enclosed;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.runner.RunWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
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
    public static final String API_KEY = UUID.randomUUID().toString();
    public static final String USER = UUID.randomUUID().toString();
    public static final String WORKSPACE_ID = UUID.randomUUID().toString();
    public static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private String baseURI;
    private ClientSupport client;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
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

        private final String fakeApikey = UUID.randomUUID().toString();
        private final String okApikey = UUID.randomUUID().toString();

        Stream<Arguments> credentials() {
            return Stream.of(
                    arguments(okApikey, null, true, null),
                    arguments(okApikey, "Demo Project", true, null),
                    arguments(fakeApikey, null, false, UNAUTHORIZED_RESPONSE),
                    arguments("", null, false, NO_API_KEY_RESPONSE));
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

            var batchSize = RandomUtils.insecure().nextInt(4, 9);
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

        void sendBatch(Entity<?> payload, String mediaType, String projectName, String workspaceName, String apiKey,
                boolean expected, ErrorMessage errorMessage) {

            var requestBuilder = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request(mediaType)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName);

            if (StringUtils.isNotEmpty(projectName)) {
                requestBuilder.header(RequestContext.PROJECT_NAME, projectName);
            }

            try (Response actualResponse = requestBuilder.post(payload)) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

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
    }
}
