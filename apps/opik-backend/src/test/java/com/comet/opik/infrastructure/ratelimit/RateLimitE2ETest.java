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
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import io.dropwizard.jersey.errors.ErrorMessage;
import io.reactivex.rxjava3.internal.operators.single.SingleDelay;
import io.swagger.v3.oas.annotations.parameters.RequestBody;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.HttpMethod;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
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
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
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

@Testcontainers(parallel = true)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Rate limit Resource Test")
class RateLimitE2ETest {

    private static final String BASE_RESOURCE_URI = "%s/v1/private/traces";

    private static final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private static final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private static final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer();

    @RegisterExtension
    private static final TestDropwizardAppExtension app;
    private static final WireMockUtils.WireMockRuntime wireMock;

    private static final long LIMIT = 4L;
    private static final long LIMIT_DURATION_IN_SECONDS = 1L;
    public static final String CUSTOM_LIMIT = "customLimit";

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    @Path("/v1/private/test")
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    public static class CustomRatedBean {

        @POST
        @RateLimited(value = CUSTOM_LIMIT)
        public Response test(@RequestBody String test) {
            return Response.status(Response.Status.CREATED).build();
        }
    }

    static {
        MYSQL.start();
        CLICKHOUSE.start();
        REDIS.start();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE, DATABASE_NAME);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .rateLimitEnabled(true)
                        .limit(LIMIT)
                        .limitDurationInSeconds(LIMIT_DURATION_IN_SECONDS)
                        .customLimits(Map.of(CUSTOM_LIMIT, new LimitConfig(1, 1)))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws Exception {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE.createConnection("")) {
            MigrationUtils.runDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.baseURI = "http://localhost:%d".formatted(client.getPort());
        this.client = client;

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private static void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    private static void mockSessionCookieTargetWorkspace(String sessionToken, String workspaceName, String workspaceId,
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
    void rateLimit__whenRemainingLimitIsLessThanRequestedSize__thenRejectTheRequest() {

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

            assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, response.getStatus());
            var error = response.readEntity(ErrorMessage.class);
            assertEquals("Too Many Requests", error.getMessage());
        }
    }

    @Test
    @DisplayName("Rate limit: When after reject request due to batch size, Then accept the request with remaining limit")
    void rateLimit__whenAfterRejectRequestDueToBatchSize__thenAcceptTheRequestWithRemainingLimit() {

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

            assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, response.getStatus());
            var error = response.readEntity(ErrorMessage.class);
            assertEquals("Too Many Requests", error.getMessage());
        }

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .path("batch")
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(new TraceBatch(traces.subList(0, (int) LIMIT - 1))))) {

            assertEquals(HttpStatus.SC_NO_CONTENT, response.getStatus());
        }
    }

    @ParameterizedTest
    @MethodSource
    @DisplayName("Rate limit: When batch endpoint consumer remaining limit, Then reject next request")
    void rateLimit__whenBatchEndpointConsumerRemainingLimit__thenRejectNextRequest(
            Object batch,
            Object batch2,
            String url,
            String method) {

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

            assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, response.getStatus());
            var error = response.readEntity(ErrorMessage.class);
            assertEquals("Too Many Requests", error.getMessage());
        }
    }

    @Test
    @DisplayName("Rate limit: When processing operations, Then return remaining limit as header")
    void rateLimit__whenProcessingOperations__thenReturnRemainingLimitAsHeader() {

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
                            (int) LIMIT_DURATION_IN_SECONDS);
                } else {
                    assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, response.getStatus());
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

            assertLimitHeaders(response, 0, CUSTOM_LIMIT, 1);
        }

        try (var response = client.target("%s/v1/private/test".formatted(baseURI))
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(""))) {

            assertEquals(HttpStatus.SC_TOO_MANY_REQUESTS, response.getStatus());

            assertLimitHeaders(response, 0, CUSTOM_LIMIT, 1);
        }

    }

    @Test
    @DisplayName("Rate limit: When rate limit is not set, Then set and and return limit")
    void rateLimit__whenCustomRatedBeanMethodIsCalled__thenRateLimitIsApplied(RateLimitService rateLimitService) {
        String apiKey = UUID.randomUUID().toString();
        int limit = 100;

        Long availableEvents = rateLimitService.availableEvents(apiKey, "generalLimit", new LimitConfig(limit, 1))
                .block();

        assertEquals(limit, availableEvents);
    }

    private static void assertLimitHeaders(Response response, long expected, String limitBucket, int limitDuration) {
        String remainingLimit = response.getHeaderString(RequestContext.USER_REMAINING_LIMIT);
        String userLimit = response.getHeaderString(RequestContext.USER_LIMIT);
        String remainingTtl = response.getHeaderString(RequestContext.USER_LIMIT_REMAINING_TTL);

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

                    try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                            .request()
                            .accept(MediaType.APPLICATION_JSON_TYPE)
                            .header(HttpHeaders.AUTHORIZATION, apiKey)
                            .header(WORKSPACE_HEADER, workspaceName)
                            .post(Entity.json(trace))) {

                        return Flux.just(response);
                    }
                }, 5)
                .toStream()
                .collect(groupingBy(Response::getStatus, counting()));
    }

}