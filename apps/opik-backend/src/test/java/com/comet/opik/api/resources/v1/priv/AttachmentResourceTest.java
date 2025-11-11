package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Project;
import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.AttachmentInfo;
import com.comet.opik.api.attachment.CompleteMultipartUploadRequest;
import com.comet.opik.api.attachment.DeleteAttachmentsRequest;
import com.comet.opik.api.attachment.MultipartUploadPart;
import com.comet.opik.api.attachment.StartMultipartUploadRequest;
import com.comet.opik.api.attachment.StartMultipartUploadResponse;
import com.comet.opik.api.resources.utils.AWSUtils;
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
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AttachmentUtilsTest;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
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
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Attachment Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
@Slf4j
class AttachmentResourceTest {

    private static final String USER = UUID.randomUUID().toString();

    public static final String LARGE_FILE_NAME = "large.txt";
    public static final String FILE_NAME = "test.jpg";

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
                        .isMinIO(false)
                        .modules(List.of(AWSUtils.testClients(minioUrl)))
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private AttachmentResourceClient attachmentResourceClient;
    private ProjectService projectService;
    public static final int MULTI_UPLOAD_CHUNK_SIZE = 6 * 1048576; //6M

    @BeforeAll
    void setUpAll(ClientSupport client, ProjectService projectService) throws SQLException {

        this.attachmentResourceClient = new AttachmentResourceClient(client);
        this.projectService = projectService;

        ClientSupportUtils.config(client);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
        attachmentResourceClient.close();
    }

    @Test
    @DisplayName("Upload attachment with MultiPart Presign URL")
    void uploadAttachmentWithMultiPartPresignUrl() throws IOException {

        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Initiate upload
        StartMultipartUploadRequest startUploadRequest = factory.manufacturePojo(StartMultipartUploadRequest.class);

        log.info("Start attachment upload for workspaceId {}, projectName {}, entityId {}", workspaceId,
                startUploadRequest.projectName(),
                startUploadRequest.entityId());

        StartMultipartUploadResponse startUploadResponse = attachmentResourceClient
                .startMultiPartUpload(startUploadRequest, apiKey, workspaceName, 200);

        // Upload file using presigned url, will be done on client side (SDK)
        InputStream is = getClass().getClassLoader().getResourceAsStream(LARGE_FILE_NAME);
        byte[] fileData = IOUtils.toByteArray(is);
        List<String> eTags = uploadParts(startUploadResponse, fileData);

        CompleteMultipartUploadRequest completeUploadRequest = prepareCompleteUploadRequest(startUploadRequest,
                fileData.length, startUploadResponse.uploadId(), eTags);

        attachmentResourceClient.completeMultiPartUpload(completeUploadRequest, apiKey, workspaceName, 204);

        log.info("Complete attachment upload for workspaceId {}, projectName {}, entityId {}", workspaceId,
                startUploadRequest.projectName(),
                startUploadRequest.entityId());

        // To verify that the file was uploaded we get the list of attachments
        Project project = projectService.findByNames(workspaceId, List.of(startUploadRequest.projectName())).getFirst();
        var page = attachmentResourceClient.attachmentList(project.id(), startUploadRequest.entityType(),
                startUploadRequest.entityId(), UUID.randomUUID().toString(), apiKey, workspaceName, 200);

        var expectedPage = AttachmentUtilsTest.prepareExpectedPage(startUploadRequest, fileData.length);
        AttachmentUtilsTest.verifyPage(page, expectedPage);

        // To verify the link, we download attachment using that link
        String downloadLink = page.content().get(0).link();
        var downloadedFileData = attachmentResourceClient.downloadFileExternal(downloadLink, 200);
        assertThat(downloadedFileData).isEqualTo(fileData);

        // Delete attachment and verify it's not available
        DeleteAttachmentsRequest deleteAttachmentsRequest = AttachmentUtilsTest.prepareDeleteRequest(startUploadRequest,
                project.id());
        attachmentResourceClient.deleteAttachments(deleteAttachmentsRequest, apiKey, workspaceName, 204);

        page = attachmentResourceClient.attachmentList(project.id(), startUploadRequest.entityType(),
                startUploadRequest.entityId(), UUID.randomUUID().toString(), apiKey, workspaceName, 200);
        AttachmentUtilsTest.verifyPage(page, Attachment.AttachmentPage.empty(1));

        // Try to download deleted file, should fail
        attachmentResourceClient.downloadFileExternal(downloadLink, 404);
    }

    @Test
    @DisplayName("Direct upload for AWS S3 should fail")
    void directS3UploadShouldFailTest() throws IOException {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Initiate upload
        AttachmentInfo attachmentInfo = factory.manufacturePojo(AttachmentInfo.class);
        InputStream is = getClass().getClassLoader().getResourceAsStream(FILE_NAME);
        byte[] fileData = IOUtils.toByteArray(is);
        attachmentResourceClient.uploadAttachment(attachmentInfo, fileData, apiKey, workspaceName, 403);
    }

    @Test
    @DisplayName("Direct download for AWS S3 should fail")
    void directS3DownloadShouldFailTest() throws IOException {
        String workspaceName = UUID.randomUUID().toString();
        String apiKey = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Initiate upload
        AttachmentInfo attachmentInfo = factory.manufacturePojo(AttachmentInfo.class);
        attachmentResourceClient.downloadAttachment(attachmentInfo.fileName(), attachmentInfo.containerId(),
                attachmentInfo.mimeType(), attachmentInfo.entityType(), attachmentInfo.entityId(), apiKey,
                workspaceName, 403);
    }

    private List<String> uploadParts(StartMultipartUploadResponse startUploadResponse, byte[] data) {
        List<String> eTags = new ArrayList<>();
        for (int i = 0; i < startUploadResponse.preSignUrls().size(); i++) {
            int chunkToUpload = i == startUploadResponse.preSignUrls().size() - 1
                    ? data.length - i * MULTI_UPLOAD_CHUNK_SIZE
                    : MULTI_UPLOAD_CHUNK_SIZE;
            byte[] partData = new byte[chunkToUpload];
            System.arraycopy(data, i * MULTI_UPLOAD_CHUNK_SIZE, partData, 0, chunkToUpload);
            String eTag = attachmentResourceClient.uploadFileExternal(startUploadResponse.preSignUrls().get(i),
                    partData);

            eTags.add(eTag);
        }

        return eTags;
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