package com.comet.opik.infrastructure.ratelimit;

import com.comet.opik.api.Trace;
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
import io.reactivex.rxjava3.internal.operators.single.SingleDelay;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.testcontainers.containers.ClickHouseContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import reactor.core.publisher.Flux;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.Trace.TracePage;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.AppContextConfig;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static java.util.stream.Collectors.counting;
import static java.util.stream.Collectors.groupingBy;

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

    private static final long LIMIT = 10L;
    private static final long LIMIT_DURATION_IN_SECONDS = 1L;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

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
    @DisplayName("Rate limit: When using apiKey and limit is exceeded Then block remaining calls")
    void rateLimit__whenUsingApiKeyAndLimitIsExceeded__shouldBlockRemainingCalls() {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithApiKey(LIMIT * 2, projectName, apiKey, workspaceName);

        // Verify that the rate limit is exceeded
        Assertions.assertEquals(LIMIT, responseMap.get(429));
        Assertions.assertEquals(LIMIT, responseMap.get(201));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // Verify that traces created are equal to the limit
            Assertions.assertEquals(200, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            Assertions.assertEquals(LIMIT, page.content().size());
            Assertions.assertEquals(LIMIT, page.total());
            Assertions.assertEquals(LIMIT, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When using apiKey and limit is not exceeded given duration Then allow all calls")
    void rateLimit__whenUsingApiKeyAndLimitIsNotExceededGivenDuration__thenAllowAllCalls() {

        String apiKey = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithApiKey(LIMIT, projectName, apiKey, workspaceName);

        // Verify that the rate limit is not exceeded
        Assertions.assertEquals(LIMIT, responseMap.get(201));

        SingleDelay.timer(LIMIT_DURATION_IN_SECONDS, TimeUnit.SECONDS).blockingGet();

        responseMap = triggerCallsWithApiKey(LIMIT, projectName, apiKey, workspaceName);

        // Verify that the rate limit is not exceeded
        Assertions.assertEquals(LIMIT, responseMap.get(201));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // Verify that traces created are equal to the limit
            Assertions.assertEquals(200, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            Assertions.assertEquals(LIMIT * 2, page.content().size());
            Assertions.assertEquals(LIMIT * 2, page.total());
            Assertions.assertEquals(LIMIT * 2, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When using sessionToken and limit is exceeded Then block remaining calls")
    void rateLimit__whenUsingSessionTokenAndLimitIsExceeded__shouldBlockRemainingCalls() {

        String sessionToken = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockSessionCookieTargetWorkspace(sessionToken, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithCookie(LIMIT * 2, projectName, sessionToken, workspaceName);

        // Verify that the rate limit is exceeded
        Assertions.assertEquals(LIMIT, responseMap.get(429));
        Assertions.assertEquals(LIMIT, responseMap.get(201));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // Verify that traces created are equal to the limit
            Assertions.assertEquals(200, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            Assertions.assertEquals(LIMIT, page.content().size());
            Assertions.assertEquals(LIMIT, page.total());
            Assertions.assertEquals(LIMIT, page.size());
        }

    }

    @Test
    @DisplayName("Rate limit: When using sessionToken and limit is not exceeded given duration Then allow all calls")
    void rateLimit__whenUsingSessionTokenAndLimitIsNotExceededGivenDuration__thenAllowAllCalls() {

        String sessionToken = UUID.randomUUID().toString();
        String user = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();

        mockSessionCookieTargetWorkspace(sessionToken, workspaceName, workspaceId, user);

        String projectName = UUID.randomUUID().toString();

        Map<Integer, Long> responseMap = triggerCallsWithCookie(LIMIT, projectName, sessionToken, workspaceName);

        // Verify that the rate limit is not exceeded
        Assertions.assertEquals(LIMIT, responseMap.get(201));

        SingleDelay.timer(LIMIT_DURATION_IN_SECONDS, TimeUnit.SECONDS).blockingGet();

        responseMap = triggerCallsWithCookie(LIMIT, projectName, sessionToken, workspaceName);

        // Verify that the rate limit is not exceeded
        Assertions.assertEquals(LIMIT, responseMap.get(201));

        try (var response = client.target(BASE_RESOURCE_URI.formatted(baseURI))
                .queryParam("project_name", projectName)
                .queryParam("size", LIMIT * 2)
                .request()
                .accept(MediaType.APPLICATION_JSON_TYPE)
                .cookie(RequestContext.SESSION_COOKIE, sessionToken)
                .header(WORKSPACE_HEADER, workspaceName)
                .get()) {

            // Verify that traces created are equal to the limit
            Assertions.assertEquals(200, response.getStatus());
            TracePage page = response.readEntity(TracePage.class);

            Assertions.assertEquals(LIMIT * 2, page.content().size());
            Assertions.assertEquals(LIMIT * 2, page.total());
            Assertions.assertEquals(LIMIT * 2, page.size());
        }

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