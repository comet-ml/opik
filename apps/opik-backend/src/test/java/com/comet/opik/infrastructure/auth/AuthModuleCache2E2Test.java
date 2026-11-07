package com.comet.opik.infrastructure.auth;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.utils.AsyncUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.matching.RequestPatternBuilder;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.commons.lang3.RandomStringUtils;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class AuthModuleCache2E2Test {

    public static final String URL_TEMPLATE = "%s/v1/private/projects";
    public static final String AUTH_PATH = "/opik/auth";
    private static final String ORGANIZATION_PATH = "/opik/workspace-organization";

    private static final int CACHE_TTL_IN_SECONDS = 1;

    public static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE,
                ClickHouseContainerUtils.DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .runtimeInfo(wireMock.runtimeInfo())
                        .redisUrl(REDIS.getRedisURI())
                        .authCacheTtlInSeconds(CACHE_TTL_IN_SECONDS)
                        .customConfigs(List.of(new TestDropwizardAppExtensionUtils.CustomConfig(
                                "serviceToggles.modelPricesEnabled", "true")))
                        .build());
    }

    private String baseURI;
    private ClientSupport client;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
    }

    @Test
    void testAuthCache__whenApiKeyAndWorkspaceAreCached__thenUseTheCacheUntilTTLExpire() {

        try (var response = callEndpoint()) {

            assertThat(response.getStatus()).isEqualTo(200);

            wireMock.server().verify(1, authRequests());
        }

        try (var response = callEndpoint()) {

            assertThat(response.getStatus()).isEqualTo(200);

            wireMock.server().verify(1, authRequests());
        }

        Mono.delay(Duration.ofMillis((CACHE_TTL_IN_SECONDS * 1000) + 100)).block();

        try (var response = callEndpoint()) {

            assertThat(response.getStatus()).isEqualTo(200);

            wireMock.server().verify(2, authRequests());
        }
    }

    @Test
    void testOrganizationResolver__whenAuthIsEnabled__thenAnswerNoOrganizationWithoutCallingComet(
            OrganizationResolver resolver) {
        assertThat(resolver).isExactlyInstanceOf(RemoteOrganizationResolver.class);
        var cometRequestsBefore = wireMock.server().getAllServeEvents().size();

        StepVerifier.create(resolver.organizationId()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)))
                .verifyComplete();
        StepVerifier.create(resolver.requireOrganizationAdmin()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, WORKSPACE_ID)))
                .expectErrorSatisfies(error -> assertThat(error).isExactlyInstanceOf(ForbiddenException.class))
                .verify();

        assertThat(wireMock.server().getAllServeEvents()).hasSize(cometRequestsBefore);
    }

    @Test
    void requestsThatDoNotAskMakeNoCometCallAndAJobFindsWhatALookupCached(
            OrganizationResolver resolver) {
        var apiKey = UUID.randomUUID().toString();
        var workspaceName = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();
        var organizationId = UUID.randomUUID().toString();
        var organizationName = RandomStringUtils.secure().nextAlphanumeric(10);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
        wireMock.server().stubFor(WireMock.post(WireMock.urlEqualTo(ORGANIZATION_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo(apiKey))
                .withRequestBody(WireMock.matchingJsonPath("$.workspaceName", WireMock.equalTo(workspaceName)))
                .willReturn(WireMock.okJson("""
                        {"workspaceId": "%s", "workspaceName": "%s", "organizationId": "%s",
                         "organizationName": "%s", "isOrganizationAdmin": false}
                        """.formatted(workspaceId, workspaceName, organizationId, organizationName))));

        for (int i = 0; i < 2; i++) {
            try (var response = callEndpoint(apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(200);
            }
        }

        wireMock.server().verify(0, WireMock.postRequestedFor(WireMock.urlEqualTo(ORGANIZATION_PATH)));

        var request = RequestContext.builder()
                .userName(USER)
                .workspaceId(workspaceId)
                .workspaceName(workspaceName)
                .cometCredentials(CometCredentials.apiKey(apiKey))
                .build();
        StepVerifier.create(resolver.organizationId()
                .contextWrite(ctx -> AsyncUtils.setRequestContext(ctx, request)))
                .expectNext(organizationId)
                .verifyComplete();
        StepVerifier.create(resolver.organizationId()
                .contextWrite(ctx -> ctx.put(RequestContext.WORKSPACE_ID, workspaceId)))
                .expectNext(organizationId)
                .verifyComplete();
        wireMock.server().verify(1, WireMock.postRequestedFor(WireMock.urlEqualTo(ORGANIZATION_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo(apiKey)));
    }

    private static RequestPatternBuilder authRequests() {
        return WireMock.postRequestedFor(WireMock.urlEqualTo(AUTH_PATH))
                .withHeader(HttpHeaders.AUTHORIZATION, WireMock.equalTo(API_KEY));
    }

    private Response callEndpoint() {
        return callEndpoint(API_KEY, TEST_WORKSPACE);
    }

    private Response callEndpoint(String apiKey, String workspaceName) {
        return client.target(URL_TEMPLATE.formatted(baseURI))
                .request()
                .header(WORKSPACE_HEADER, workspaceName)
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .get();
    }
}
