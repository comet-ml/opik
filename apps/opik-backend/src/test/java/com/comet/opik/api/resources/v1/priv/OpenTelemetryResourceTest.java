package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AuthenticationErrorResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.OpenTelemetryMapper;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.google.protobuf.ByteString;
import com.redis.testcontainers.RedisContainer;
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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.runner.RunWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.sql.SQLException;
import java.util.ArrayList;
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

@RunWith(Enclosed.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
class OpenTelemetryResourceTest {

    public static final String URL_TEMPLATE = "%s/v1/private/otel/v1/traces";
    public static final String API_KEY = UUID.randomUUID().toString();
    public static final String USER = UUID.randomUUID().toString();
    public static final String WORKSPACE_ID = UUID.randomUUID().toString();

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();

    private static final MySQLContainer<?> MY_SQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();

    private static final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;

    private static final WireMockUtils.WireMockRuntime wireMock;
    public static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    static {
        Startables.deepStart(REDIS, MY_SQL_CONTAINER, CLICK_HOUSE_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MY_SQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {
        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICK_HOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        log.info(client.toString());

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
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
                                            new AuthenticationErrorResponse(FAKE_API_KEY_MESSAGE,
                                                    401)))));
        }

        @ParameterizedTest
        @MethodSource("credentials")
        public void testExportTraceServiceRequest(String apiKey, String projectName, boolean expected,
                io.dropwizard.jersey.errors.ErrorMessage errorMessage) {

            String workspaceName = UUID.randomUUID().toString();
            mockTargetWorkspace(okApikey, workspaceName, WORKSPACE_ID);

            var dayMillis = 24 * 60 * 60 * 1000L;
            var todayMidnight = (System.currentTimeMillis() / dayMillis) * dayMillis;

            var otelTraceId = UUID.randomUUID().toString().getBytes();
            var parentSpanId = UUID.randomUUID().toString().getBytes();

            var opikTraceId = OpenTelemetryMapper.convertOtelIdToUUIDv7(otelTraceId, System.currentTimeMillis(), true);
            log.info("Expected trace id: '{}' -> '{}'", otelTraceId, opikTraceId);
            var opikParentSpanId = OpenTelemetryMapper.convertOtelIdToUUIDv7(parentSpanId, System.currentTimeMillis(),
                    true);

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

            byte[] requestProtobufBytes = ExportTraceServiceRequest.newBuilder()
                    .addResourceSpans(ResourceSpans.newBuilder()
                            .addScopeSpans(ScopeSpans.newBuilder().addAllSpans(otelSpans).build())
                            .build())
                    .build()
                    .toByteArray();

            var requestBuilder = client.target(URL_TEMPLATE.formatted(baseURI))
                    .request("application/x-protobuf")
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName);

            if (StringUtils.isNotEmpty(projectName)) {
                requestBuilder.header(RequestContext.PROJECT_NAME, projectName);
            }

            try (Response actualResponse = requestBuilder.post(
                    Entity.entity(requestProtobufBytes, "application/x-protobuf"))) {

                if (expected) {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(200);

                    Trace trace = traceResourceClient.getById(opikTraceId, workspaceName, apiKey);
                    assertThat(trace.id()).isEqualTo(opikTraceId);

                    var projectNameOrDefault = StringUtils.isNotEmpty(projectName)
                            ? projectName
                            : ProjectService.DEFAULT_PROJECT;
                    var spanPage = spanResourceClient.getByTraceIdAndProject(opikTraceId, projectNameOrDefault,
                            workspaceName, apiKey);
                    assertThat(spanPage.size()).isEqualTo(otelSpans.size());
                } else {
                    assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(401);
                    assertThat(actualResponse.readEntity(io.dropwizard.jersey.errors.ErrorMessage.class))
                            .isEqualTo(errorMessage);

                }
            }
        }
    }
}
