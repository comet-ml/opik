package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.MediaUrlCheck;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Media URLs Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class MediaUrlsResourceTest {

    private static final String CHECK_PATH = "/v1/private/media-urls/check";
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String WORKSPACE_NAME = "test-workspace-" + UUID.randomUUID();
    private static final String API_KEY = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;
    private WireMockServer mediaHost;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    @BeforeAll
    void beforeAll() {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, WORKSPACE_NAME, WORKSPACE_ID, USER);

        mediaHost = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        mediaHost.start();
    }

    @BeforeEach
    void setUp() {
        mediaHost.resetAll();
    }

    @AfterAll
    void afterAll() {
        mediaHost.stop();
    }

    private Response check(ClientSupport client, List<String> urls) {
        return client.target(CHECK_PATH)
                .request()
                .header(RequestContext.WORKSPACE_HEADER, WORKSPACE_NAME)
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .post(Entity.json(MediaUrlCheck.builder().urls(urls).build()));
    }

    @Test
    @DisplayName("returns one result per URL, in request order")
    void checkMediaUrls(ClientSupport client) {
        mediaHost.stubFor(get(urlEqualTo("/cat.png"))
                .willReturn(aResponse().withStatus(206).withHeader("Content-Type", "image/png")));
        mediaHost.stubFor(get(urlEqualTo("/missing.png")).willReturn(aResponse().withStatus(404)));
        mediaHost.stubFor(get(urlEqualTo("/page"))
                .willReturn(aResponse().withStatus(200).withHeader("Content-Type", "text/html")));
        var image = mediaHost.baseUrl() + "/cat.png";
        var missing = mediaHost.baseUrl() + "/missing.png";
        var page = mediaHost.baseUrl() + "/page";

        try (var response = check(client, List.of(image, missing, page))) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_OK);
            assertThat(response.readEntity(MediaUrlCheck.Results.class)).isEqualTo(
                    MediaUrlCheck.Results.builder()
                            .results(List.of(
                                    MediaUrlCheck.Result.builder().url(image).status(MediaUrlCheck.Status.OK)
                                            .httpStatus(206).contentType("image/png").build(),
                                    MediaUrlCheck.Result.builder().url(missing)
                                            .status(MediaUrlCheck.Status.HTTP_ERROR).httpStatus(404).build(),
                                    MediaUrlCheck.Result.builder().url(page).status(MediaUrlCheck.Status.NOT_MEDIA)
                                            .httpStatus(200).contentType("text/html").build()))
                            .build());
        }
    }

    @Test
    @DisplayName("refuses more than 20 URLs")
    void checkMediaUrlsWhenTooManyUrlsReturnsUnprocessableEntity(ClientSupport client) {
        var urls = IntStream.rangeClosed(0, MediaUrlCheck.MAX_URLS)
                .mapToObj(index -> mediaHost.baseUrl() + "/" + index + ".png")
                .toList();

        try (var response = check(client, urls)) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
        assertThat(mediaHost.getAllServeEvents()).isEmpty();
    }

    @Test
    @DisplayName("refuses an empty list")
    void checkMediaUrlsWhenNoUrlsReturnsUnprocessableEntity(ClientSupport client) {
        try (var response = check(client, List.of())) {
            assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }
}
