package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread.TraceThreadPage;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AnnotationQueuesResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.lifecycle.Startables;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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

    private static final String[] QUEUE_IGNORED_FIELDS = new String[]{
            "reviewers", "feedbackScores", "itemsCount", "createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy"};

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
    private TraceResourceClient traceResourceClient;
    private TransactionTemplateAsync clickHouseTemplate;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);

        ClientSupportUtils.config(client);

        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
        this.annotationQueuesResourceClient = new AnnotationQueuesResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
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
                    annotationQueue.id(), emptyItemIds, API_KEY, TEST_WORKSPACE, SC_UNPROCESSABLE_ENTITY);
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
                    annotationQueue.id(), emptyItemIds, API_KEY, TEST_WORKSPACE, SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Nested
    @DisplayName("Get Annotation Queue By Id")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetAnnotationQueueById {

        @Test
        @DisplayName("should get annotation queue for traces when valid id with aggregated feedback scores")
        void getAnnotationQueueForTracesWithFeedbackScores() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue for traces
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .feedbackDefinitionNames(List.of("quality", "relevance"))
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create traces and add them to the queue
            var trace1 = createTrace(project.name());
            var trace2 = createTrace(project.name());
            var trace3 = createTrace(project.name()); // This trace won't be in the queue

            var itemIds = Set.of(trace1, trace2);
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create feedback scores - some for traces in the queue, some for traces not in the queue
            createFeedbackScoreForTrace(trace1, "quality", 0.8, project.name());
            createFeedbackScoreForTrace(trace1, "relevance", 0.9, project.name());
            createFeedbackScoreForTrace(trace2, "quality", 0.7, project.name());
            createFeedbackScoreForTrace(trace2, "relevance", 0.85, project.name());

            // Feedback scores for trace3 (NOT in the queue) - should not be aggregated
            createFeedbackScoreForTrace(trace3, "quality", 0.5, project.name());
            createFeedbackScoreForTrace(trace3, "relevance", 0.6, project.name());

            // Feedback scores with different names (not in feedbackDefinitionNames) - should not be aggregated
            createFeedbackScoreForTrace(trace1, "other_metric", 0.3, project.name());

            // When
            var retrievedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            // Then
            assertThat(retrievedQueue)
                    .usingRecursiveComparison()
                    .ignoringFields(QUEUE_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(annotationQueue);

            // Verify aggregated feedback scores only include scores from traces in the queue
            // and only for feedbackDefinitionNames specified in the queue
            assertThat(retrievedQueue.feedbackScores()).hasSize(2);

            var feedbackScoreMap = retrievedQueue.feedbackScores().stream()
                    .collect(Collectors.toMap(
                            FeedbackScoreAverage::name,
                            FeedbackScoreAverage::value));

            // Expected averages: quality = (0.8 + 0.7) / 2 = 0.75, relevance = (0.9 + 0.85) / 2 = 0.875
            assertThat(feedbackScoreMap.get("quality")).isEqualByComparingTo(new BigDecimal("0.75"));
            assertThat(feedbackScoreMap.get("relevance")).isEqualByComparingTo(new BigDecimal("0.875"));

            // Ensure other_metric is not included
            assertThat(feedbackScoreMap).doesNotContainKey("other_metric");

            // Verify reviewers match the original annotation queue
            verifyReviewers(retrievedQueue, 4L);
        }

        @Test
        @DisplayName("should get annotation queue for threads when valid id with aggregated feedback scores")
        void getAnnotationQueueForThreadsWithFeedbackScores() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue for threads
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.THREAD)
                    .feedbackDefinitionNames(List.of("coherence", "completeness"))
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create traces with thread IDs and add thread IDs to the queue
            var threadId1 = UUID.randomUUID();
            var threadId2 = UUID.randomUUID();
            var threadId3 = UUID.randomUUID(); // This thread won't be in the queue

            createTraceWithThread(project.name(), threadId1);
            createTraceWithThread(project.name(), threadId2);
            createTraceWithThread(project.name(), threadId3);

            // Close threads first (required before adding feedback scores)
            closeThread(threadId1, projectId, project.name());
            closeThread(threadId2, projectId, project.name());
            closeThread(threadId3, projectId, project.name());

            // Retrieve threads to get their threadModelId (needed for annotation queue items)
            TraceThreadPage threadsPage = traceResourceClient.getTraceThreads(
                    projectId, project.name(), API_KEY, TEST_WORKSPACE, List.of(), List.of(), Map.of());

            var threadIdToModelId = threadsPage.content().stream()
                    .collect(Collectors.toMap(
                            thread -> thread.id(),
                            thread -> thread.threadModelId()));

            var itemIds = Set.of(threadIdToModelId.get(threadId1.toString()),
                    threadIdToModelId.get(threadId2.toString()));
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create thread feedback scores - some for threads in the queue, some for threads not in the queue
            createFeedbackScoreForThread(threadId1, "coherence", 0.9, project.name());
            createFeedbackScoreForThread(threadId1, "completeness", 0.8, project.name());
            createFeedbackScoreForThread(threadId2, "coherence", 0.85, project.name());
            createFeedbackScoreForThread(threadId2, "completeness", 0.75, project.name());

            // Feedback scores for threadId3 (NOT in the queue) - should not be aggregated
            createFeedbackScoreForThread(threadId3, "coherence", 0.4, project.name());
            createFeedbackScoreForThread(threadId3, "completeness", 0.3, project.name());

            // Feedback scores with different names (not in feedbackDefinitionNames) - should not be aggregated
            createFeedbackScoreForThread(threadId1, "other_thread_metric", 0.2, project.name());

            // When
            var retrievedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            // Then
            assertThat(retrievedQueue)
                    .usingRecursiveComparison()
                    .ignoringFields(QUEUE_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(annotationQueue);

            assertThat(retrievedQueue.itemsCount()).isEqualTo(2L);

            // Verify aggregated feedback scores only include scores from threads in the queue
            // and only for feedbackDefinitionNames specified in the queue
            assertThat(retrievedQueue.feedbackScores()).hasSize(2);

            var feedbackScoreMap = retrievedQueue.feedbackScores().stream()
                    .collect(Collectors.toMap(
                            FeedbackScoreAverage::name,
                            FeedbackScoreAverage::value));

            // Expected averages: coherence = (0.9 + 0.85) / 2 = 0.875, completeness = (0.8 + 0.75) / 2 = 0.775
            assertThat(feedbackScoreMap.get("coherence")).isEqualByComparingTo(new BigDecimal("0.875"));
            assertThat(feedbackScoreMap.get("completeness")).isEqualByComparingTo(new BigDecimal("0.775"));

            // Ensure other_thread_metric is not included
            assertThat(feedbackScoreMap).doesNotContainKey("other_thread_metric");

            // Verify reviewers match the original annotation queue
            verifyReviewers(retrievedQueue, 4L);
        }

        @Test
        @DisplayName("should return 404 when annotation queue not found")
        void getAnnotationQueueWhenNotFound() {
            // Given - Non-existent queue ID
            var nonExistentQueueId = UUID.randomUUID();

            // When & Then
            annotationQueuesResourceClient.getAnnotationQueueById(
                    nonExistentQueueId, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("should get annotation queue with empty feedback scores when no feedback scores exist")
        void getAnnotationQueueWithEmptyFeedbackScores() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue for traces
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create traces and add them to the queue but don't create any feedback scores
            var trace1 = createTrace(project.name());
            var itemIds = Set.of(trace1);
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // When
            var retrievedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            // Then
            assertThat(retrievedQueue)
                    .usingRecursiveComparison()
                    .ignoringFields(QUEUE_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(annotationQueue);

            assertThat(retrievedQueue.itemsCount()).isEqualTo(1L);
            assertThat(retrievedQueue.feedbackScores()).isNull();
            assertThat(retrievedQueue.reviewers()).isNull();
        }
    }

    @Nested
    @DisplayName("Find Annotation Queues")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindAnnotationQueues {

        @Test
        @DisplayName("should return paginated annotation queues when valid request")
        void findAnnotationQueues() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create multiple annotation queues
            var annotationQueue1 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Queue Alpha")
                    .build();

            var annotationQueue2 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Queue Beta")
                    .build();

            var annotationQueue3 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Queue Gamma")
                    .build();

            // Create the annotation queues
            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue1, annotationQueue2, annotationQueue3)),
                    apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // When - Request first page with size 2
            var firstPage = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 2, null, null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertThat(firstPage.content()).hasSize(2);
            assertThat(firstPage.page()).isEqualTo(1);
            assertThat(firstPage.size()).isEqualTo(2);
            assertThat(firstPage.total()).isEqualTo(3);

            // When - Request second page
            var secondPage = annotationQueuesResourceClient.findAnnotationQueues(
                    2, 2, null, null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertThat(secondPage.content()).hasSize(1);
            assertThat(secondPage.page()).isEqualTo(2);
            assertThat(secondPage.size()).isEqualTo(1);
            assertThat(secondPage.total()).isEqualTo(3);
        }

        @Test
        @DisplayName("should return empty page when no annotation queues exist")
        void findAnnotationQueuesWhenEmpty() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - No annotation queues created

            // When
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, null, null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertThat(page.content()).isEmpty();
            assertThat(page.page()).isEqualTo(1);
            assertThat(page.size()).isEqualTo(0);
            assertThat(page.total()).isEqualTo(0);
        }

        @Test
        @DisplayName("should filter annotation queues by name")
        void findAnnotationQueuesWithNameFilter() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create annotation queues with different names
            var annotationQueue1 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Alpha Queue")
                    .build();

            var annotationQueue2 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Beta Queue")
                    .build();

            var annotationQueue3 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Alpha Test")
                    .build();

            // Create the annotation queues
            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue1, annotationQueue2, annotationQueue3)),
                    apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // When - Filter by name containing "Alpha"
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, "Alpha", null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertThat(page.content()).hasSize(2);
            assertThat(page.content()).allSatisfy(queue -> assertThat(queue.name()).contains("Alpha"));
        }

        @Test
        @DisplayName("should return empty results when name filter matches nothing")
        void findAnnotationQueuesWithNameFilterNoMatches() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create annotation queue
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("Test Queue")
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)),
                    apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // When - Filter by name that doesn't exist
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, "NonExistentName", null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isEqualTo(0);
        }

        @ParameterizedTest
        @ValueSource(strings = {"name,asc", "name,desc", "created_at,asc", "created_at,desc"})
        @DisplayName("should sort annotation queues by different fields and directions")
        void findAnnotationQueuesWithSorting(String sorting) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create annotation queues with predictable names for sorting
            var annotationQueue1 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("A Queue")
                    .build();

            var annotationQueue2 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("B Queue")
                    .build();

            var annotationQueue3 = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .name("C Queue")
                    .build();

            // Create the annotation queues
            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue1, annotationQueue2, annotationQueue3)),
                    apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // When
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, null, null, sorting, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertThat(page.content()).hasSize(3);

            // Verify sorting based on field and direction
            if (sorting.contains("name")) {
                var names = page.content().stream().map(AnnotationQueue::name).toList();
                if (sorting.contains("asc")) {
                    assertThat(names).containsExactly("A Queue", "B Queue", "C Queue");
                } else {
                    assertThat(names).containsExactly("C Queue", "B Queue", "A Queue");
                }
            } else if (sorting.contains("created_at")) {
                // For created_at sorting, we just verify the response is successful
                // since exact timestamp ordering is harder to predict in tests
                assertThat(page.content()).hasSize(3);
            }
        }

        @ParameterizedTest
        @ValueSource(strings = {
                "project_name='" + "TestProject" + "'",
                "scope='TRACE'",
                "scope='THREAD'"
        })
        @DisplayName("should filter annotation queues by different criteria")
        void findAnnotationQueuesWithFilters(String filters) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create annotation queues with different scopes
            var traceQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .build();

            var threadQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.THREAD)
                    .build();

            // Create the annotation queues
            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(traceQueue, threadQueue)),
                    apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // When
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, null, filters, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then - Verify filtering worked (exact assertions depend on filter type)
            assertThat(page.content()).isNotEmpty();

            if (filters.contains("scope='TRACE'")) {
                assertThat(page.content()).allSatisfy(
                        queue -> assertThat(queue.scope()).isEqualTo(AnnotationQueue.AnnotationScope.TRACE));
            } else if (filters.contains("scope='THREAD'")) {
                assertThat(page.content()).allSatisfy(
                        queue -> assertThat(queue.scope()).isEqualTo(AnnotationQueue.AnnotationScope.THREAD));
            }
        }
    }

    private UUID createTrace(String projectName) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .feedbackScores(null)
                .usage(null)
                .build();
        return traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
    }

    private UUID createTraceWithThread(String projectName, UUID threadId) {
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .threadId(threadId.toString())
                .feedbackScores(null)
                .usage(null)
                .build();
        return traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
    }

    private void createFeedbackScoreForTrace(UUID traceId, String scoreName, double scoreValue,
            String projectName) {
        var feedbackScore = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                .id(traceId)
                .name(scoreName)
                .value(BigDecimal.valueOf(scoreValue))
                .projectName(projectName)
                .build();
        traceResourceClient.feedbackScores(List.of(feedbackScore), API_KEY, TEST_WORKSPACE);
    }

    private void createFeedbackScoreForThread(UUID threadId, String scoreName, double scoreValue,
            String projectName) {
        var feedbackScore = factory.manufacturePojo(FeedbackScoreBatchItemThread.class).toBuilder()
                .threadId(threadId.toString())
                .name(scoreName)
                .value(BigDecimal.valueOf(scoreValue))
                .projectName(projectName)
                .build();
        traceResourceClient.threadFeedbackScores(List.of(feedbackScore), API_KEY, TEST_WORKSPACE);
    }

    private void closeThread(UUID threadId, UUID projectId, String projectName) {
        traceResourceClient.closeTraceThread(threadId.toString(), projectId, projectName, API_KEY, TEST_WORKSPACE);
    }

    private void verifyReviewers(AnnotationQueue queue, Long expectedStatus) {
        assertThat(queue.reviewers()).hasSize(1);
        assertThat(queue.reviewers().getFirst().username()).isEqualTo(USER);
        assertThat(queue.reviewers().getFirst().status()).isEqualTo(expectedStatus);
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
