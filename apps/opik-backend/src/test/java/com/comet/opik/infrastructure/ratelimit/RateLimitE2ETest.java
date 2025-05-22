package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentItemsBatch;
import com.comet.opik.api.FeedbackScoreBatch;
import com.comet.opik.api.FeedbackScoreBatchItem;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatch;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceBatch;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.OpikConfiguration;
import com.comet.opik.infrastructure.RateLimitConfig;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.reactivex.rxjava3.internal.operators.single.SingleDelay;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.client.Invocation;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.infrastructure.RateLimitConfig.LimitConfig;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Rate limit Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class RateLimitE2ETest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/traces";
    private static final String CUSTOM_LIMIT = "customLimit";
    private static final String GET_SPAN_ID_LIMIT = "getSpanById";
    private static final long LIMIT = 4L;
    private static final long WORKSPACE_LIMIT = 6L;
    private static final long LIMIT_DURATION_IN_SECONDS = 1L;
    public static final String TOO_MANY_REQUESTS_MESSAGEE = "Too Many Requests: %s";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private LimitConfig customLimit;
    private LimitConfig getSpanIdLimit;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @Path("/v1/private/test")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public static class CustomRatedBean {

        @POST
        @RateLimited(value = CUSTOM_LIMIT)
        public Response test(@RequestBody String test, @QueryParam("time") Integer time) {
            if (time != null) {
                Mono.delay(Duration.ofSeconds(time)).block();
            }
            return Response.status(Response.Status.CREATED).build();
        }
    }

    {
        Startables.deepStart(MYSQL, CLICKHOUSE, REDIS, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        customLimit = new LimitConfig(CUSTOM_LIMIT, CUSTOM_LIMIT, 1, 1, "custom limit");

        getSpanIdLimit = new LimitConfig("Get-Span-Id", GET_SPAN_ID_LIMIT, 3, 1, "get span id");

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .rateLimitEnabled(true)
                        .limit(LIMIT)
                        .workspaceLimit(WORKSPACE_LIMIT)
                        .limitDurationInSeconds(LIMIT_DURATION_IN_SECONDS)
                        .customLimits(Map.of(CUSTOM_LIMIT, customLimit, GET_SPAN_ID_LIMIT, getSpanIdLimit))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;
    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    private void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName, String workspaceId,
            String user) {
        AuthTestUtils.mockSessionCookieTargetWorkspace(wireMock.server(), sessionToken, workspaceName, workspaceId,
                user);
    }

    @Test
    @DisplayName("Rate limit: When using apiKey and limit is exceeded, Then block remaining calls")
    void rateLimit__whenUsingApiKeyAndLimitIsExceeded__shouldBlockRemainingCalls() {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithApiKey(LIMIT * 2, projectName, apiKey, workspaceName);

        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_TOO_MANY_REQUESTS));
        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_CREATED));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // Verify that traces created are equal to the limit
            assertEquals(HttpStatus.SC_OK, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            assertEquals(LIMIT, page.content().size());
            assertEquals(LIMIT, page.total());
            assertEquals(LIMIT, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When using apiKey and limit is not exceeded given duration, Then allow all calls")
    void rateLimit__whenUsingApiKeyAndLimitIsNotExceededGivenDuration__thenAllowAllCalls() {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithApiKey(LIMIT, projectName, apiKey, workspaceName);

        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_CREATED));

        SingleDelay.timer(LIMIT_DURATION_IN_SECONDS, TimeUnit.SECONDS).blockingGet();

        responseMap = triggerCallsWithApiKey(LIMIT, projectName, apiKey, workspaceName);

        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_CREATED));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertEquals(HttpStatus.SC_OK, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            assertEquals(LIMIT * 2, page.content().size());
            assertEquals(LIMIT * 2, page.total());
            assertEquals(LIMIT * 2, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When using sessionToken and limit is exceeded, Then block remaining calls")
    void rateLimit__whenUsingSessionTokenAndLimitIsExceeded__shouldBlockRemainingCalls() {

        String sessionToken = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockSessionCookieTargetWorkspace(sessionToken, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithCookie(LIMIT * 2, projectName, sessionToken, workspaceName);

        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_TOO_MANY_REQUESTS));
        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_CREATED));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            assertEquals(HttpStatus.SC_OK, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            assertEquals(LIMIT, page.content().size());
            assertEquals(LIMIT, page.total());
            assertEquals(LIMIT, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When using sessionToken and limit is not exceeded given duration, Then allow all calls")
    void rateLimit__whenUsingSessionTokenAndLimitIsNotExceededGivenDuration__thenAllowAllCalls() {

        String sessionToken = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockSessionCookieTargetWorkspace(sessionToken, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithCookie(LIMIT, projectName, sessionToken, workspaceName);

        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_CREATED));

        SingleDelay.timer(LIMIT_DURATION_IN_SECONDS, TimeUnit.SECONDS).blockingGet();

        responseMap = triggerCallsWithCookie(LIMIT, projectName, sessionToken, workspaceName);

        assertEquals(LIMIT, responseMap.get(HttpStatus.SC_CREATED));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // Verify that traces created are equal to the limit
            assertEquals(HttpStatus.SC_OK, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            assertEquals(LIMIT * 2, page.content().size());
            assertEquals(LIMIT * 2, page.total());
            assertEquals(LIMIT * 2, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When remaining limit is less than the batch size, Then reject the request")
    void rateLimit__whenRemainingLimitIsLessThanRequestedSize__thenRejectTheRequest(
            OpikConfiguration opikConfiguration) {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithApiKey(1, projectName, apiKey, workspaceName);

        assertEquals(1, responseMap.get(HttpStatus.SC_CREATED));

        List<Trace> traces = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .projectId(null)
                        .build())
                .toList();

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path("batch")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new TraceBatch(traces)))) {

            assertLimitExceeded(response, opikConfiguration.getRateLimit().getGeneralLimit());
        }
    }

    private String getLimitErrorMessage(String errorMessage) {
        return TOO_MANY_REQUESTS_MESSAGEE.formatted(errorMessage);
    }

    @Test
    @DisplayName("Rate limit: When after reject request due to batch size, Then accept the request with remaining limit")
    void rateLimit__whenAfterRejectRequestDueToBatchSize__thenAcceptTheRequestWithRemainingLimit(
            OpikConfiguration configuration) {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        String workspaceId2 = UUID.randomUUID().toString();
        String workspaceName2 = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);
        mockTargetWorkspace(apiKey, workspaceName2, workspaceId2, user);

        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .comments(null)
                .build();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        List<Trace> traces = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                        .feedbackScores(null)
                        .comments(null)
                        .build())
                .toList();

        try (var response = traceResourceClient.callBatchCreateTraces(traces, apiKey, workspaceName)) {
            assertLimitExceeded(response, configuration.getRateLimit().getGeneralLimit());
        }

        traceResourceClient.batchCreateTraces(traces.subList(0, (int) LIMIT - 1), apiKey, workspaceName2);
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Rate limit: When batch endpoint consumer remaining limit, Then reject next request")
    void rateLimit__whenBatchEndpointConsumerRemainingLimit__thenRejectNextRequest(
            Object batch,
            Object batch2,
            String url,
            String method,
            OpikConfiguration configuration) {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        Invocation.Builder request = client.target(url)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName);

        try (var response = request.method(method, Entity.json(batch))) {

            assertEquals(HttpStatus.SC_NO_CONTENT, response.getStatus());
        }

        try (var response = request.method(method, Entity.json(batch2))) {

            assertLimitExceeded(response, configuration.getRateLimit().getGeneralLimit());
        }
    }

    @Test
    @DisplayName("Rate limit: When processing operations, Then return remaining limit as header")
    void rateLimit__whenProcessingOperations__thenReturnRemainingLimitAsHeader(OpikConfiguration opikConfiguration) {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        IntStream.range(0, (int) LIMIT + 1).forEach(i -> {
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();

            try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, apiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(trace))) {

                if (i < LIMIT) {
                    assertEquals(HttpStatus.SC_CREATED, response.getStatus());

                    assertLimitHeaders(response, LIMIT - i - 1, RateLimited.GENERAL_EVENTS,
                            (int) LIMIT_DURATION_IN_SECONDS, opikConfiguration.getRateLimit().getGeneralLimit());
                } else {
                    assertLimitExceeded(response, opikConfiguration.getRateLimit().getGeneralLimit());
                }
            }
        });
    }

    private void assertLimitExceeded(Response response, @Valid LimitConfig limitConfig) {
        assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, response.getStatus());
        assertRateLimitResetHeader(response, limitConfig);
        ErrorMessage errorMessage = response.readEntity(ErrorMessage.class);
        assertThat(errorMessage.getMessage())
                .isEqualTo(getLimitErrorMessage(limitConfig.errorMessage()));
    }

    private void assertRateLimitResetHeader(Response response, RateLimitConfig.LimitConfig limitConfig) {
        assertThat(response.getHeaders().get(RequestContext.RATE_LIMIT_RESET))
                .isNotNull()
                .hasSize(1)
                .first()
                .isInstanceOf(String.class)
                .asString()
                .satisfies(value -> {
                    long rateLimitResetValue = Long.parseLong(value);

                    String limitInMillis = response.getHeaders()
                            .get(RequestContext.LIMIT_REMAINING_TTL.formatted(limitConfig.headerName()))
                            .getFirst()
                            .toString();

                    long expectedTTLInSec = Math.max(Duration.ofMillis(Long.parseLong(limitInMillis)).getSeconds(), 1);

                    assertEquals(expectedTTLInSec, rateLimitResetValue);
                });

    }

    @Test
    @DisplayName("Workspace Rate limit: When processing operations, Then return remaining limit as header")
    void workspaceRateLimit__whenProcessingOperations__thenReturnRemainingLimitAsHeader(
            OpikConfiguration opikConfiguration) {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        String apiKey2 = UUID.randomUUID().toString();
        String user2 = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);
        mockTargetWorkspace(apiKey2, workspaceName, workspaceId, user2);

        String projectName = UUID.randomUUID().toString();

        IntStream.range(0, (int) WORKSPACE_LIMIT + 1).forEach(i -> {
            Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .build();

            var currentApiKey = i % 2 == 0 ? apiKey : apiKey2;

            try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                    .request()
                    .accept(MediaType.APPLICATION_JSON_TYPE)
                    .header(HttpHeaders.AUTHORIZATION, currentApiKey)
                    .header(WORKSPACE_HEADER, workspaceName)
                    .post(Entity.json(trace))) {

                if (i < WORKSPACE_LIMIT) {
                    assertEquals(HttpStatus.SC_CREATED, response.getStatus());

                    assertLimitHeaders(response, WORKSPACE_LIMIT - i - 1, RateLimited.WORKSPACE_EVENTS,
                            (int) LIMIT_DURATION_IN_SECONDS, opikConfiguration.getRateLimit().getWorkspaceLimit());
                } else {
                    assertLimitExceeded(response, opikConfiguration.getRateLimit().getWorkspaceLimit());
                }
            }
        });
    }

    public Stream<Arguments> rateLimit__whenBatchEndpointConsumerRemainingLimit__thenRejectNextRequest() {

        var projectName = UUID.randomUUID().toString();

        var traces = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(Trace.class).toBuilder()
                        .projectName(projectName)
                        .projectId(null)
                        .build())
                .toList();

        var spans = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(Span.class).toBuilder()
                        .projectName(projectName)
                        .projectId(null)
                        .parentSpanId(null)
                        .build())
                .toList();

        var datasetItems = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(DatasetItem.class).toBuilder()
                        .experimentItems(null)
                        .build())
                .toList();

        var tracesFeedbackScores = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                        .projectId(null)
                        .build())
                .toList();

        var spansFeedbackScores = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                        .projectId(null)
                        .build())
                .toList();

        var experimentItems = IntStream.range(0, (int) LIMIT)
                .mapToObj(i -> factory.manufacturePojo(ExperimentItem.class).toBuilder()
                        .feedbackScores(null)
                        .build())
                .collect(Collectors.toSet());

        return Stream.of(
                Arguments.of(new TraceBatch(traces), new TraceBatch(List.of(traces.getFirst())),
                        BASE_RESOURCE_URI.formatted(baseURI) + "/batch", HttpMethod.POST),
                Arguments.of(new SpanBatch(spans), new SpanBatch(List.of(spans.getFirst())),
                        "%s/v1/private/spans".formatted(baseURI) + "/batch", HttpMethod.POST),
                Arguments.of(new DatasetItemBatch(projectName, null, datasetItems),
                        new DatasetItemBatch(projectName, null, List.of(datasetItems.getFirst())),
                        "%s/v1/private/datasets".formatted(baseURI) + "/items", HttpMethod.PUT),
                Arguments.of(new FeedbackScoreBatch(tracesFeedbackScores),
                        new FeedbackScoreBatch(List.of(tracesFeedbackScores.getFirst())),
                        BASE_RESOURCE_URI.formatted(baseURI) + "/feedback-scores", HttpMethod.PUT),
                Arguments.of(new FeedbackScoreBatch(spansFeedbackScores),
                        new FeedbackScoreBatch(List.of(spansFeedbackScores.getFirst())),
                        "%s/v1/private/spans".formatted(baseURI) + "/feedback-scores", HttpMethod.PUT),
                Arguments.of(new ExperimentItemsBatch(experimentItems),
                        new ExperimentItemsBatch(Set.of(experimentItems.stream().findFirst().orElseThrow())),
                        "%s/v1/private/experiments".formatted(baseURI) + "/items", HttpMethod.POST));
    }

    @Test
    @DisplayName("Rate limit: When custom rated bean method is called, Then rate limit is applied")
    void rateLimit__whenCustomRatedBeanMethodIsCalled__thenRateLimitIsApplied() {
        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        try (var response = client.target("%s/v1/private/test".formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {

            assertEquals(HttpStatus.SC_CREATED, response.getStatus());

            assertLimitHeaders(response, 0, CUSTOM_LIMIT, 1, customLimit);
        }

        try (var response = client.target("%s/v1/private/test".formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {

            assertLimitExceeded(response, customLimit);
            assertLimitHeaders(response, 0, CUSTOM_LIMIT, 1, customLimit);
        }

    }

    @Test
    @DisplayName("Rate limit: When custom rated bean method is called but takes longer then ttl, Then rate limit header is reset")
    void rateLimit__whenCustomRatedBeanMethodIsCalledButTakesLongerThenTtl__thenRateLimitHeaderIsReset() {
        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        try (var response = client.target("%s/v1/private/test?time=2".formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {

            assertEquals(HttpStatus.SC_CREATED, response.getStatus());

            assertLimitHeaders(response, 1, CUSTOM_LIMIT, 1, customLimit);
        }
    }

    @Test
    @DisplayName("Rate limit: When rate limit is not set, Then set and return limit")
    void rateLimit__whenCustomRatedBeanMethodIsCalled__thenRateLimitIsApplied(RateLimitService rateLimitService) {
        String apiKey = UUID.randomUUID().toString();
        int limit = 100;

        String generalLimit = "generalLimit";

        Long availableEvents = rateLimitService
                .availableEvents(apiKey, new LimitConfig(generalLimit, generalLimit, limit, 1, "general limit"))
                .block();

        assertEquals(limit, availableEvents);
    }

    @Test
    @DisplayName("Rate limit: When custom rate limit has placeholder, Then set and return limit")
    void rateLimit__whenRateLimitHasPlaceholder__thenSetAndReturnLimit() {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        Span span = factory.manufacturePojo(Span.class);

        spanResourceClient.createSpan(span, apiKey, workspaceName);

        IntStream.range(0, (int) getSpanIdLimit.limit() + 1)
                .forEach(i -> {
                    if (i < getSpanIdLimit.limit()) {
                        try (var response = spanResourceClient.callGetSpanIdApi(span.id(), workspaceName, apiKey)) {
                            assertLimitHeaders(response, getSpanIdLimit.limit() - i - 1, GET_SPAN_ID_LIMIT, 1,
                                    getSpanIdLimit);
                        }
                    } else {
                        try (var response = spanResourceClient.callGetSpanIdApi(span.id(), workspaceName, apiKey)) {
                            assertLimitExceeded(response, getSpanIdLimit);
                        }
                    }
                });
    }

    private static void assertLimitHeaders(Response response, long expected, String limitBucket, int limitDuration,
            LimitConfig limitConfig) {
        String remainingLimit = response
                .getHeaderString(RequestContext.REMAINING_LIMIT.formatted(limitConfig.headerName()));
        String userLimit = response.getHeaderString(RequestContext.LIMIT.formatted(limitConfig.headerName()));
        String remainingTtl = response
                .getHeaderString(RequestContext.LIMIT_REMAINING_TTL.formatted(limitConfig.headerName()));

        assertEquals(expected, Long.parseLong(remainingLimit));
        assertEquals(limitBucket, userLimit);
        assertThat(Long.parseLong(remainingTtl)).isBetween(0L, Duration.ofSeconds(limitDuration).toMillis());
    }

    private Map<Integer, Long> triggerCallsWithCookie(long limit, String projectName, String sessionToken,
            String workspaceName) {
        return Flux.range(0, ((int) limit))
                .flatMap(i -> {
                    Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .build();

                    try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                            .request()
                            .accept(MediaType.APPLICATION_JSON_TYPE)
                            .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                            .header(WORKSPACE_HEADER, workspaceName)
                            .post(Entity.json(trace))) {

                        return Flux.just(response);
                    }
                }, 5)
                .toStream()
                .collect(groupingBy(Response::getStatus, counting()));
    }

    private Map<Integer, Long> triggerCallsWithApiKey(long limit, String projectName, String apiKey,
            String workspaceName) {
        return Flux.range(0, ((int) limit))
                .flatMap(i -> {
                    Trace trace = factory.manufacturePojo(Trace.class).toBuilder()
                            .projectName(projectName)
                            .build();

                    try (Response data = traceResourceClient.callCreateTrace(trace, apiKey, workspaceName)) {
                        return Flux.just(data);
                    }
                }, 5)
                .toStream()
                .collect(groupingBy(Response::getStatus, counting()));
    }

}
