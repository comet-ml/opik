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
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.MigrationUtils.CLICKHOUSE_CHANGELOG_FILE;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Attachment Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
@Slf4j
class AttachmentResourceMinIOTest {

    private static final String USER = UUID.randomUUID().toString();

    public static final String FILE_NAME = "test.jpg";

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

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private AttachmentResourceClient attachmentResourceClient;
    private String baseURI;
    public static final int MULTI_UPLOAD_CHUNK_SIZE = 6 * 1048576;//6M

    @BeforeAll
    void setUpAll(ClientSupport client, Jdbi jdbi) throws SQLException {

        MigrationUtils.runDbMigration(jdbi, MySQLContainerUtils.migrationParameters());

        try (var connection = CLICKHOUSE_CONTAINER.createConnection("")) {
            MigrationUtils.runClickhouseDbMigration(connection, CLICKHOUSE_CHANGELOG_FILE,
                    ClickHouseContainerUtils.migrationParameters());
        }

        this.attachmentResourceClient = new AttachmentResourceClient(client);
        this.baseURI = "http://localhost:%d".formatted(client.getPort());

        ClientSupportUtils.config(client);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Test
    @DisplayName("Upload attachment with MultiPart Presign URL")
    void uploadAttachmentWithMultiPartPresignUrl() throws IOException {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Initiate upload
        StartMultipartUploadRequest startUploadRequest = prepareStartUploadRequest();

        log.info("Start attachment upload for workspaceId {}, projectName {}, entityId {}", workspaceId,
                startUploadRequest.projectName(),
                startUploadRequest.entityId());

        StartMultipartUploadResponse startUploadResponse = attachmentResourceClient
                .startMultiPartUpload(startUploadRequest, apiKey, workspaceName, 200);

        // Upload file using presigned url, will be done on client side (SDK)
        InputStream is = getClass().getClassLoader().getResourceAsStream(FILE_NAME);
        byte[] fileData = IOUtils.toByteArray(is);

        attachmentResourceClient.uploadFile(startUploadResponse, fileData, apiKey, workspaceName);

        log.info("Complete attachment upload for workspaceId {}, projectName {}, entityId {}", workspaceId,
                startUploadRequest.projectName(),
                startUploadRequest.entityId());

        // TODO: proper verification that the file was uploaded will be done once we prepare corresponding endpoint in OPIK-728
    }

    private StartMultipartUploadRequest prepareStartUploadRequest() {
        return factory.manufacturePojo(StartMultipartUploadRequest.class)
                .toBuilder()
                .path(Base64.getUrlEncoder().withoutPadding().encodeToString(baseURI.getBytes()))
                .build();
    }

    private CompleteMultipartUploadRequest prepareCompleteUploadRequest(StartMultipartUploadRequest startUploadRequest,
            long fileSize, String uploadId, List<String> eTags) {
        List<MultipartUploadPart> uploadedFileParts = new ArrayList<>();
        for (int i = 1; i <= eTags.size(); i++) {
            uploadedFileParts.add(MultipartUploadPart.builder()
                    .eTag(eTags.get(i - 1))
                    .partNumber(i)
                    .build());
        }

        return CompleteMultipartUploadRequest.builder()
                .fileName(startUploadRequest.fileName())
                .entityType(startUploadRequest.entityType())
                .entityId(startUploadRequest.entityId())
                .projectName(startUploadRequest.projectName())
                .fileSize(fileSize)
                .uploadId(uploadId)
                .uploadedFileParts(uploadedFileParts)
                .build();
    }
}