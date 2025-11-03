package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.BatchDeleteByProject;
import com.comet.opik.api.Project;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.attachment.Attachment;
import com.comet.opik.api.attachment.DeleteAttachmentsRequest;
import com.comet.opik.api.attachment.EntityType;
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
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AttachmentResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.ProjectService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.AttachmentUtilsTest;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.IOUtils;
import org.apache.commons.lang3.tuple.Pair;
import org.awaitility.Awaitility;
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
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.io.IOException;
import java.io.InputStream;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Attachment Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
@Slf4j
class AttachmentResourceMinIOTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

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
                        .minioUrl(minioUrl)
                        .isMinIO(true)
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private AttachmentResourceClient attachmentResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;
    private String baseURI;
    private String baseURIEncoded;
    private ProjectService projectService;

    @BeforeAll
    void setUpAll(ClientSupport client, ProjectService projectService) {

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.baseURI = TestUtils.getBaseUrl(client);
        this.baseURIEncoded = Base64.getUrlEncoder().withoutPadding().encodeToString(baseURI.getBytes());
        this.projectService = projectService;

        this.attachmentResourceClient = new AttachmentResourceClient(client);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);

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
        var uploadResult = uploadFile(null, null, null);
        StartMultipartUploadRequest startUploadRequest = uploadResult.getLeft();
        byte[] fileData = uploadResult.getRight();

        // To verify that the file was uploaded we get the list of attachments
        Project project = projectService.findByNames(WORKSPACE_ID, List.of(startUploadRequest.projectName()))
                .getFirst();
        var page = attachmentResourceClient.attachmentList(project.id(), startUploadRequest.entityType(),
                startUploadRequest.entityId(), baseURIEncoded, API_KEY, TEST_WORKSPACE, 200);

        var expectedPage = AttachmentUtilsTest.prepareExpectedPage(startUploadRequest, fileData.length);
        AttachmentUtilsTest.verifyPage(page, expectedPage);

        // To verify the link, we download attachment using that link
        String downloadLink = page.content().get(0).link();
        var downloadedFileData = attachmentResourceClient.downloadFile(downloadLink, API_KEY, 200);
        assertThat(downloadedFileData).isEqualTo(fileData);

        // Delete attachment and verify it's not available
        DeleteAttachmentsRequest deleteAttachmentsRequest = AttachmentUtilsTest.prepareDeleteRequest(startUploadRequest,
                project.id());
        attachmentResourceClient.deleteAttachments(deleteAttachmentsRequest, API_KEY, TEST_WORKSPACE, 204);

        page = attachmentResourceClient.attachmentList(project.id(), startUploadRequest.entityType(),
                startUploadRequest.entityId(), UUID.randomUUID().toString(), API_KEY, TEST_WORKSPACE, 200);
        AttachmentUtilsTest.verifyPage(page, Attachment.AttachmentPage.empty(1));

        // Try to download deleted file, should fail
        attachmentResourceClient.downloadFile(downloadLink, API_KEY, 404);
    }

    @Test
    @DisplayName("Invalid base URL format returns error for MinIO attachment upload")
    void invalidBaseUrlFormatReturnsError() {
        StartMultipartUploadRequest startUploadRequest = prepareStartUploadRequest("https://www.comet.com/");
        attachmentResourceClient
                .startMultiPartUpload(startUploadRequest, API_KEY, TEST_WORKSPACE, 400);
    }

    @ParameterizedTest
    @MethodSource
    void deleteTraceDeletesTraceAndSpanAttachments(Consumer<UUID> deleteTrace) throws IOException {
        Trace trace = createTrace();
        UUID traceId = traceResourceClient.createTrace(trace, API_KEY,
                TEST_WORKSPACE);

        // Create span for above trace
        UUID spanId = spanResourceClient.createSpan(
                factory.manufacturePojo(Span.class).toBuilder().traceId(traceId).projectName(trace.projectName())
                        .build(),
                API_KEY,
                TEST_WORKSPACE);

        Project project = projectService.findByNames(WORKSPACE_ID, List.of(trace.projectName())).getFirst();

        // Upload attachment for trace
        var traceAttachmentUploadResult = uploadFile(trace.projectName(), EntityType.TRACE, traceId);

        // Verify it's uploaded by getting list and downloading file
        var tracePage = attachmentResourceClient.attachmentList(project.id(), EntityType.TRACE,
                traceId, baseURIEncoded, API_KEY, TEST_WORKSPACE, 200);
        assertThat(tracePage.total()).isEqualTo(1);
        String traceDownloadLink = tracePage.content().getFirst().link();
        var traceFileData = attachmentResourceClient.downloadFile(traceDownloadLink, API_KEY, 200);
        assertThat(traceFileData).isEqualTo(traceAttachmentUploadResult.getRight());

        // Upload attachment for span
        var spanAttachmentUploadResult = uploadFile(trace.projectName(), EntityType.SPAN, spanId);

        // Verify it's uploaded by getting list and downloading file
        var spanPage = attachmentResourceClient.attachmentList(project.id(), EntityType.SPAN,
                spanId, baseURIEncoded, API_KEY, TEST_WORKSPACE, 200);
        assertThat(spanPage.total()).isEqualTo(1);
        String spanDownloadLink = spanPage.content().getFirst().link();
        var spanFileData = attachmentResourceClient.downloadFile(spanDownloadLink, API_KEY, 200);
        assertThat(spanFileData).isEqualTo(spanAttachmentUploadResult.getRight());

        // Delete trace
        deleteTrace.accept(traceId);

        // Verify trace attachments were actually deleted via list endpoint and download link
        Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var tracePageUpdated = attachmentResourceClient.attachmentList(project.id(), EntityType.TRACE,
                    traceId, baseURIEncoded, API_KEY, TEST_WORKSPACE, 200);
            assertThat(tracePageUpdated).isEqualTo(Attachment.AttachmentPage.empty(1));
            attachmentResourceClient.downloadFile(traceDownloadLink, API_KEY, 404);
        });

        // Verify span attachments were actually deleted via list endpoint and download link
        Awaitility.await().pollInterval(500, TimeUnit.MILLISECONDS).untilAsserted(() -> {
            var spanPageUpdated = attachmentResourceClient.attachmentList(project.id(), EntityType.SPAN,
                    spanId, baseURIEncoded, API_KEY, TEST_WORKSPACE, 200);
            assertThat(spanPageUpdated).isEqualTo(Attachment.AttachmentPage.empty(1));
            attachmentResourceClient.downloadFile(spanDownloadLink, API_KEY, 404);
        });
    }

    Stream<Arguments> deleteTraceDeletesTraceAndSpanAttachments() {
        return Stream.of(
                Arguments.of(
                        (Consumer<UUID>) traceId -> traceResourceClient.deleteTrace(traceId, TEST_WORKSPACE, API_KEY)),
                Arguments.of((Consumer<UUID>) traceId -> traceResourceClient
                        .deleteTraces(BatchDeleteByProject.builder().ids(Set.of(traceId)).build(), TEST_WORKSPACE,
                                API_KEY)));
    }

    Pair<StartMultipartUploadRequest, byte[]> uploadFile(String projectName, EntityType type, UUID entityId)
            throws IOException {
        // Initiate upload
        StartMultipartUploadRequest startUploadRequest = prepareStartUploadRequest(baseURIEncoded);
        if (projectName != null) {
            startUploadRequest = startUploadRequest.toBuilder()
                    .projectName(projectName)
                    .entityType(type)
                    .entityId(entityId)
                    .build();
        }

        log.info("Start attachment upload for workspaceId {}, projectName {}, entityId {}", WORKSPACE_ID,
                startUploadRequest.projectName(),
                startUploadRequest.entityId());

        StartMultipartUploadResponse startUploadResponse = attachmentResourceClient
                .startMultiPartUpload(startUploadRequest, API_KEY, TEST_WORKSPACE, 200);

        // Upload file using presigned url, will be done on client side (SDK)
        InputStream is = getClass().getClassLoader().getResourceAsStream(FILE_NAME);
        byte[] fileData = IOUtils.toByteArray(is);

        attachmentResourceClient.uploadFile(startUploadResponse, fileData, API_KEY, TEST_WORKSPACE);

        log.info("Complete attachment upload for workspaceId {}, projectName {}, entityId {}", WORKSPACE_ID,
                startUploadRequest.projectName(),
                startUploadRequest.entityId());

        return Pair.of(startUploadRequest, fileData);
    }

    private StartMultipartUploadRequest prepareStartUploadRequest(String baseURIEncoded) {
        return factory.manufacturePojo(StartMultipartUploadRequest.class)
                .toBuilder()
                .path(baseURIEncoded)
                .build();
    }

    private Trace createTrace() {
        return factory.manufacturePojo(Trace.class).toBuilder()
                .feedbackScores(null)
                .threadId(null)
                .comments(null)
                .totalEstimatedCost(null)
                .duration(null)
                .usage(null)
                .build();
    }
}