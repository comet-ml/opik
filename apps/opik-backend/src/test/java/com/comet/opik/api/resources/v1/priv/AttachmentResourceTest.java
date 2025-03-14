package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MinIOContainerUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AttachmentResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.jdbi.v3.core.Jdbi;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.attachment.EntityType.TRACE;
import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Attachment Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
@Slf4j
class AttachmentResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    public static final String FILE_NAME = "test.jpg";
    public static final String MIME_TYPE = "image/jpeg";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils.newClickHouseContainer();
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
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

        MinIOContainerUtils.createTestBucket(minioUrl);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .authCacheTtlInSeconds(null)
                        .minioUrl(minioUrl)
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private TransactionTemplate mySqlTemplate;
    private AttachmentResourceClient attachmentResourceClient;
    private HttpClient httpClient;

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi,
            TransactionTemplate mySqlTemplate) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.mySqlTemplate = mySqlTemplate;
        this.attachmentResourceClient = new AttachmentResourceClient(client);
        this.httpClient = HttpClient.newHttpClient();

        ClientSupportUtils.config(client);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
        httpClient.close();
    }

    @Test
    @DisplayName("Upload attachment with MultiPart Presign URL")
    void uploadAttachmentWithMultiPartPresignUrl() throws IOException, InterruptedException {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        UUID traceId = UUID.randomUUID();
        UUID projectId = UUID.randomUUID();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        log.info("Start attachment upload for workspaceId {}, projectId {}, traceId {}", workspaceId, projectId,
                traceId);

        // Initiate upload
        StartMultipartUploadRequest startUploadRequest = StartMultipartUploadRequest.builder()
                .fileName(FILE_NAME)
                .mimeType(MIME_TYPE)
                .numOfFileParts(1)
                .entityType(TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .build();

        StartMultipartUploadResponse startUploadResponse = attachmentResourceClient
                .startMultiPartUpload(startUploadRequest, apiKey, workspaceName, 200);

        // Upload file using presigned url, will be done on client side (SDK)
        InputStream is = getClass().getClassLoader().getResourceAsStream(FILE_NAME);
        byte[] fileData = IOUtils.toByteArray(is);
        List<String> eTags = uploadParts(startUploadResponse, fileData);

        // Complete upload
        CompleteMultipartUploadRequest completeUploadRequest = CompleteMultipartUploadRequest.builder()
                .fileName(FILE_NAME)
                .mimeType(MIME_TYPE)
                .entityType(TRACE)
                .entityId(traceId)
                .containerId(projectId)
                .fileSize((long) fileData.length)
                .uploadId(startUploadResponse.uploadId())
                .uploadedFileParts(List.of(MultipartUploadPart.builder()
                        .eTag(eTags.getFirst())
                        .partNumber(1)
                        .build()))
                .build();

        attachmentResourceClient.completeMultiPartUpload(completeUploadRequest, apiKey, workspaceName, 204);

        log.info("Completed attachment upload for workspaceId {}, projectId {}, traceId {}", workspaceId, projectId,
                traceId);
    }

    private List<String> uploadParts(StartMultipartUploadResponse startUploadResponse, byte[] data)
            throws IOException, InterruptedException {
        String url = startUploadResponse.preSignUrls().getFirst();

        var request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .PUT(HttpRequest.BodyPublishers.ofByteArray(data))
                .build();

        var response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

        String eTag = response.headers().firstValue("ETag").orElseThrow();

        return List.of(eTag);
    }
}