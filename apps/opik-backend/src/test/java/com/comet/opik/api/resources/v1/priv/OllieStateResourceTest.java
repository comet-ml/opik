package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.UUID;
import java.util.zip.GZIPOutputStream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Ollie State Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
@Slf4j
class OllieStateResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final String RESOURCE_PATH = "%s/v1/private/ollie/state";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> MINIO = MinIOContainerUtils.newMinIOContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, MINIO).join();
        String minioUrl = "http://%s:%d".formatted(MINIO.getHost(), MINIO.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .authCacheTtlInSeconds(null)
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }

    private ClientSupport client;
    private String baseURI;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);

        this.client = client;
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Upload, download, and delete ollie state")
    void uploadDownloadDeleteOllieState() throws IOException {
        byte[] gzipData = createGzipData("ollie-state-test-content");

        // Upload
        try (Response uploadResponse = uploadState(gzipData)) {
            assertThat(uploadResponse.getStatus()).isEqualTo(204);
        }

        // Download and verify content matches
        byte[] downloadedData;
        try (Response downloadResponse = downloadState()) {
            assertThat(downloadResponse.getStatus()).isEqualTo(200);
            assertThat(downloadResponse.getHeaderString("Content-Type")).isEqualTo("application/gzip");
            downloadedData = downloadResponse.readEntity(byte[].class);
        }
        assertThat(downloadedData).isEqualTo(gzipData);

        // Delete
        try (Response deleteResponse = deleteState()) {
            assertThat(deleteResponse.getStatus()).isEqualTo(204);
        }

        // Verify download returns 404 after delete
        try (Response notFoundResponse = downloadState()) {
            assertThat(notFoundResponse.getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("Upload replaces existing state")
    void uploadReplacesExistingState() throws IOException {
        byte[] firstData = createGzipData("first-state");
        byte[] secondData = createGzipData("second-state-replaced");

        // Upload first version
        try (Response response = uploadState(firstData)) {
            assertThat(response.getStatus()).isEqualTo(204);
        }

        // Upload second version (replace)
        try (Response response = uploadState(secondData)) {
            assertThat(response.getStatus()).isEqualTo(204);
        }

        // Download should return second version
        byte[] downloadedData;
        try (Response response = downloadState()) {
            assertThat(response.getStatus()).isEqualTo(200);
            downloadedData = response.readEntity(byte[].class);
        }
        assertThat(downloadedData).isEqualTo(secondData);

        // Cleanup
        try (Response response = deleteState()) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }

    @Test
    @DisplayName("Download returns 404 when no state exists")
    void downloadReturns404WhenNoState() {
        // Ensure clean state
        try (Response cleanup = deleteState()) {
            // ignore status
        }

        try (Response response = downloadState()) {
            assertThat(response.getStatus()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("Upload rejects non-gzip data")
    void uploadRejectsNonGzipData() {
        byte[] plainData = "not gzip data".getBytes();

        try (Response response = uploadState(plainData)) {
            assertThat(response.getStatus()).isEqualTo(400);
        }
    }

    @Test
    @DisplayName("Delete succeeds even when no state exists")
    void deleteSucceedsWhenNoState() {
        try (Response response = deleteState()) {
            assertThat(response.getStatus()).isEqualTo(204);
        }
    }

    private Response uploadState(byte[] data) {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .put(Entity.entity(data, "application/gzip"));
    }

    private Response downloadState() {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .accept("application/gzip")
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .get();
    }

    private Response deleteState() {
        return client.target(RESOURCE_PATH.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, API_KEY)
                .header(WORKSPACE_HEADER, TEST_WORKSPACE)
                .delete();
    }

    private static byte[] createGzipData(String content) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzipOut = new GZIPOutputStream(baos)) {
            gzipOut.write(content.getBytes());
        }
        return baos.toByteArray();
    }
}
