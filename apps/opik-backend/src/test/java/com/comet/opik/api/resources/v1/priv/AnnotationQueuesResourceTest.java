package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.Project;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AnnotationQueuesResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static org.apache.http.HttpStatus.SC_UNPROCESSABLE_ENTITY;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Annotation Queues Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AnnotationQueuesResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer<?> MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(),
                databaseAnalyticsFactory,
                wireMock.runtimeInfo(),
                REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();
    private String baseURI;
    private ProjectResourceClient projectResourceClient;
    private AnnotationQueuesResourceClient annotationQueuesResourceClient;
    private TransactionTemplateAsync clickHouseTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.annotationQueuesResourceClient = new AnnotationQueuesResourceClient(client, baseURI);
        this.clickHouseTemplate = clickHouseTemplate;

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Create Annotation Queue")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateAnnotationQueue {

        @Test
        @DisplayName("should create annotation queue when valid request")
        void createAnnotationQueue() {
            // Given - Create a project first for the annotation queue
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .id(null) // Will be generated
                    .projectId(projectId)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("should reject request when project_id is null")
        void createAnnotationQueueWhenProjectIdNullShouldReject() {
            // Given
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(null) // Invalid
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(new LinkedHashSet<>(List.of(annotationQueue)),
                    API_KEY, TEST_WORKSPACE,
                    SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Nested
    @DisplayName("Annotation Queue Item Management")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class AnnotationQueueItemManagement {

        @Test
        @DisplayName("should add items to annotation queue when valid request")
        void addItemsToAnnotationQueue() {
            // Given - Create a project and annotation queue first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Generate some item IDs to add
            var itemIds = Set.of(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());

            // When & Then
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            assertThat(getItemsCount(WORKSPACE_ID, annotationQueue.id())).isEqualTo(itemIds.size());
        }

        @Test
        @DisplayName("should remove items from annotation queue when valid request")
        void removeItemsFromAnnotationQueue() {
            // Given - Create a project and annotation queue first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Generate some item IDs to add first, then remove
            var itemIds = Set.of(
                    UUID.randomUUID(),
                    UUID.randomUUID());

            // Add items first
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            assertThat(getItemsCount(WORKSPACE_ID, annotationQueue.id())).isEqualTo(itemIds.size());

            // When & Then - Remove the items
            annotationQueuesResourceClient.removeItemsFromAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            assertThat(getItemsCount(WORKSPACE_ID, annotationQueue.id())).isEqualTo(0);
        }

        @Test
        @DisplayName("should return 404 when adding items to non-existent annotation queue")
        void addItemsToAnnotationQueueWhenQueueNotExistsShouldReturn404() {
            // Given - Non-existent queue ID
            var nonExistentQueueId = UUID.randomUUID();
            var itemIds = Set.of(UUID.randomUUID());

            // When & Then
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    nonExistentQueueId, itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("should return 404 when removing items from non-existent annotation queue")
        void removeItemsFromAnnotationQueueWhenQueueNotExistsShouldReturn404() {
            // Given - Non-existent queue ID
            var nonExistentQueueId = UUID.randomUUID();
            var itemIds = Set.of(UUID.randomUUID());

            // When & Then
            annotationQueuesResourceClient.removeItemsFromAnnotationQueue(
                    nonExistentQueueId, itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        void addItemsToAnnotationQueueWhenEmptyItemList() {
            // Given - Create a project and annotation queue first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Empty item list
            var emptyItemIds = Set.<UUID>of();

            // When & Then
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), emptyItemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        void removeItemsFromAnnotationQueueWhenEmptyItemList() {
            // Given - Create a project and annotation queue first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Empty item list
            var emptyItemIds = Set.<UUID>of();

            // When & Then
            annotationQueuesResourceClient.removeItemsFromAnnotationQueue(
                    annotationQueue.id(), emptyItemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    private int getItemsCount(String workspaceId, UUID queueId) {
        String itemsCountQuery = "SELECT count(*) as cnt FROM annotation_queue_items WHERE workspace_id=:workspace_id AND queue_id=:queue_id";

        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(itemsCountQuery)
                    .bind("workspace_id", workspaceId)
                    .bind("queue_id", queueId.toString());
            return Mono.from(statement.execute())
                    .flatMapMany(result -> result.map((row, metadata) -> row.get("cnt", Integer.class)))
                    .singleOrEmpty();
        }).block();
    }
}
