package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AnnotationQueue;
import com.comet.opik.api.AnnotationQueueReviewer;
import com.comet.opik.api.AnnotationQueueUpdate;
import com.comet.opik.api.FeedbackScoreAverage;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItemThread;
import com.comet.opik.api.Project;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceThread.TraceThreadPage;
import com.comet.opik.api.filter.AnnotationQueueField;
import com.comet.opik.api.filter.AnnotationQueueFilter;
import com.comet.opik.api.filter.Operator;
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
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import org.apache.hc.core5.http.HttpStatus;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestUtils.toURLEncodedQueryParam;
import static com.comet.opik.api.resources.utils.WireMockUtils.WireMockRuntime;
import static java.util.stream.Collectors.toMap;
import static java.util.stream.Collectors.toSet;
import static org.apache.http.HttpStatus.SC_UNPROCESSABLE_ENTITY;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.params.provider.Arguments.arguments;

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
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
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
    @DisplayName("Create Annotation Queue Batch")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CreateAnnotationQueueBatch {

        @Test
        @DisplayName("should create annotation queue batch when valid request")
        void createAnnotationQueueBatch() {
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
        void createAnnotationQueueBatchWhenProjectIdNullShouldReject() {
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

            var id = annotationQueuesResourceClient.createAnnotationQueue(
                    annotationQueue, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            assertThat(id.version()).isEqualTo(7);
        }

        @Test
        @DisplayName("should reject request when project_id is null")
        void createAnnotationQueueWhenProjectIdNullShouldReject() {
            // Given
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(null) // Invalid
                    .build();

            annotationQueuesResourceClient.createAnnotationQueue(annotationQueue,
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
                    .collect(toMap(
                            FeedbackScoreAverage::name,
                            FeedbackScoreAverage::value));

            // Expected averages: quality = (0.8 + 0.7) / 2 = 0.75, relevance = (0.9 + 0.85) / 2 = 0.875
            assertThat(feedbackScoreMap.get("quality")).isEqualByComparingTo(new BigDecimal("0.75"));
            assertThat(feedbackScoreMap.get("relevance")).isEqualByComparingTo(new BigDecimal("0.875"));

            // Ensure other_metric is not included
            assertThat(feedbackScoreMap).doesNotContainKey("other_metric");

            // Verify reviewers match the original annotation queue
            verifyReviewers(retrievedQueue, 2L);
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
                    .collect(toMap(
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
                    .collect(toMap(
                            FeedbackScoreAverage::name,
                            FeedbackScoreAverage::value));

            // Expected averages: coherence = (0.9 + 0.85) / 2 = 0.875, completeness = (0.8 + 0.75) / 2 = 0.775
            assertThat(feedbackScoreMap.get("coherence")).isEqualByComparingTo(new BigDecimal("0.875"));
            assertThat(feedbackScoreMap.get("completeness")).isEqualByComparingTo(new BigDecimal("0.775"));

            // Ensure other_thread_metric is not included
            assertThat(feedbackScoreMap).doesNotContainKey("other_thread_metric");

            // Verify reviewers match the original annotation queue
            verifyReviewers(retrievedQueue, 2L);
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

        @Test
        @DisplayName("should get annotation queue with reviewers for comment-only annotations (no feedback scores)")
        void getAnnotationQueueWithCommentOnlyReviewers() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue for traces with NO feedback definitions (comment-only workflow)
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .feedbackDefinitionNames(List.of()) // Empty list - no feedback definitions required
                    .commentsEnabled(true)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create traces and add them to the queue
            var trace1 = createTrace(project.name());
            var trace2 = createTrace(project.name());
            var itemIds = Set.of(trace1, trace2);
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Add comments to traces (without any feedback scores)
            traceResourceClient.generateAndCreateComment(trace1, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);
            traceResourceClient.generateAndCreateComment(trace2, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

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
            assertThat(retrievedQueue.feedbackScores()).isNull(); // No feedback scores

            // Verify reviewers are tracked based on comments only
            verifyReviewers(retrievedQueue, 2L);
        }

        @Test
        @DisplayName("should get annotation queue with reviewers for mixed feedback and comment annotations")
        void getAnnotationQueueWithMixedFeedbackAndCommentReviewers() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue with feedback definitions
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .feedbackDefinitionNames(List.of("quality"))
                    .commentsEnabled(true)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create traces and add them to the queue
            var trace1 = createTrace(project.name()); // Will have feedback score
            var trace2 = createTrace(project.name()); // Will have comment only
            var trace3 = createTrace(project.name()); // Will have both feedback and comment
            var itemIds = Set.of(trace1, trace2, trace3);
            annotationQueuesResourceClient.addItemsToAnnotationQueue(
                    annotationQueue.id(), itemIds, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Add feedback score to trace1
            createFeedbackScoreForTrace(trace1, "quality", 0.8, project.name());

            // Add comment to trace2 (no feedback)
            traceResourceClient.generateAndCreateComment(trace2, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            // Add both feedback and comment to trace3
            createFeedbackScoreForTrace(trace3, "quality", 0.9, project.name());
            traceResourceClient.generateAndCreateComment(trace3, API_KEY, TEST_WORKSPACE, HttpStatus.SC_CREATED);

            // When
            var retrievedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            // Then
            assertThat(retrievedQueue.itemsCount()).isEqualTo(3L);

            // Verify feedback scores include only traces with feedback (trace1 and trace3)
            assertThat(retrievedQueue.feedbackScores()).hasSize(1);
            var qualityScore = retrievedQueue.feedbackScores().stream()
                    .filter(score -> score.name().equals("quality"))
                    .findFirst()
                    .orElseThrow();
            assertThat(qualityScore.value()).isEqualByComparingTo(new BigDecimal("0.85")); // (0.8 + 0.9) / 2

            // Verify reviewers count all three traces (feedback + comments)
            verifyReviewers(retrievedQueue, 3L);
        }
    }

    @Nested
    @DisplayName("Update Annotation Queue")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateAnnotationQueue {

        @Test
        @DisplayName("should update annotation queue when valid request")
        void updateAnnotationQueueWhenValidRequest() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create update request
            var updateRequest = factory.manufacturePojo(AnnotationQueueUpdate.class);

            // When
            annotationQueuesResourceClient.updateAnnotationQueue(
                    annotationQueue.id(), updateRequest, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            annotationQueue = applyUpdate(updateRequest, annotationQueue); // Apply update for comparison

            var updatedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            // Then
            assertThat(updatedQueue)
                    .usingRecursiveComparison()
                    .ignoringFields(QUEUE_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(annotationQueue);
        }

        @Test
        @DisplayName("should update only provided fields and keep others unchanged")
        void updateAnnotationQueueWhenPartialRequest() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue with initial values
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .commentsEnabled(true)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create partial update request (only updating name and comments_enabled)
            var updateRequest = AnnotationQueueUpdate.builder()
                    .description("Updated description Only")
                    .instructions("")
                    .commentsEnabled(false)
                    .build();

            // When
            annotationQueuesResourceClient.updateAnnotationQueue(
                    annotationQueue.id(), updateRequest, API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            annotationQueue = applyUpdate(updateRequest, annotationQueue); // Apply update for comparison

            var updatedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);

            // Then
            assertThat(updatedQueue)
                    .usingRecursiveComparison()
                    .ignoringFields(QUEUE_IGNORED_FIELDS)
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .isEqualTo(annotationQueue);
        }

        @Test
        @DisplayName("should fail update when provided blank name")
        void updateAnnotationQueueFailsWhenBlankName() {
            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, API_KEY, TEST_WORKSPACE);

            // Create annotation queue with initial values
            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            // Create partial update request (only updating name and comments_enabled)
            var updateRequest = factory.manufacturePojo(AnnotationQueueUpdate.class)
                    .toBuilder()
                    .name("")
                    .build();

            // When
            annotationQueuesResourceClient.updateAnnotationQueue(
                    annotationQueue.id(), updateRequest, API_KEY, TEST_WORKSPACE, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }
    }

    @Nested
    @DisplayName("Delete Annotation Queue Batch")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeleteAnnotationQueueBatch {

        @Test
        @DisplayName("should delete annotation queue batch when valid request")
        void deleteAnnotationQueueBatch() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create multiple annotation queues
            var annotationQueues = IntStream.range(0, 5)
                    .mapToObj(i -> factory.manufacturePojo(AnnotationQueue.class)
                            .toBuilder()
                            .projectId(projectId)
                            .projectName(project.name())
                            .scope(AnnotationQueue.AnnotationScope.TRACE)
                            .build())
                    .toList();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(annotationQueues), apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            var idsToDelete = annotationQueues.subList(0, 3).stream()
                    .map(AnnotationQueue::id)
                    .collect(toSet());
            var notDeletedIds = annotationQueues.subList(3, annotationQueues.size()).stream()
                    .map(AnnotationQueue::id)
                    .collect(toSet());

            // When - Delete batch of annotation queues
            annotationQueuesResourceClient.deleteAnnotationQueueBatch(
                    idsToDelete, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // Then - Verify that deleted queues are no longer available
            for (UUID deletedId : idsToDelete) {
                annotationQueuesResourceClient.getAnnotationQueueById(
                        deletedId, apiKey, workspaceName, HttpStatus.SC_NOT_FOUND);
            }

            // Verify that non-deleted queues are still available
            for (UUID notDeletedId : notDeletedIds) {
                var retrievedQueue = annotationQueuesResourceClient.getAnnotationQueueById(
                        notDeletedId, apiKey, workspaceName, HttpStatus.SC_OK);
                assertThat(retrievedQueue).isNotNull();
                assertThat(retrievedQueue.id()).isEqualTo(notDeletedId);
            }
        }

        @Test
        @DisplayName("should handle empty batch delete request")
        void deleteAnnotationQueueBatchWhenEmptySet() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Empty set of IDs
            var emptyIds = Set.<UUID>of();

            // When & Then - Should return 204 No Content even for empty set
            annotationQueuesResourceClient.deleteAnnotationQueueBatch(
                    emptyIds, apiKey, workspaceName, HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("should handle non-existent annotation queue IDs gracefully")
        void deleteAnnotationQueueBatchWhenNonExistentIds() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Non-existent queue IDs
            var nonExistentIds = Set.of(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID());

            // When & Then - Should return 204 No Content (idempotent operation)
            annotationQueuesResourceClient.deleteAnnotationQueueBatch(
                    nonExistentIds, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("should handle mixed valid and non-existent annotation queue IDs")
        void deleteAnnotationQueueBatchWhenMixedIds() {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project and annotation queue
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                    .toBuilder()
                    .projectId(projectId)
                    .projectName(project.name())
                    .scope(AnnotationQueue.AnnotationScope.TRACE)
                    .build();

            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(List.of(annotationQueue)), apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // Mix valid and non-existent IDs
            var mixedIds = Set.of(
                    annotationQueue.id(), // Valid ID
                    UUID.randomUUID(), // Non-existent ID
                    UUID.randomUUID()); // Non-existent ID

            // When - Delete batch with mixed IDs
            annotationQueuesResourceClient.deleteAnnotationQueueBatch(
                    mixedIds, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // Then - Valid queue should be deleted
            annotationQueuesResourceClient.getAnnotationQueueById(
                    annotationQueue.id(), apiKey, workspaceName, HttpStatus.SC_NOT_FOUND);
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

            int queueCount = 8;

            var queues = IntStream.range(0, queueCount)
                    .mapToObj(i -> {
                        var annotationQueue = factory.manufacturePojo(AnnotationQueue.class)
                                .toBuilder()
                                .projectId(projectId)
                                .projectName(project.name())
                                .scope(AnnotationQueue.AnnotationScope.TRACE)
                                .feedbackDefinitionNames(List.of("quality", "relevance"))
                                .itemsCount(2L) // will be used for assertion later
                                .reviewers(List.of(AnnotationQueueReviewer.builder() // will be used for assertion later
                                        .username(USER)
                                        .status(2L)
                                        .build()))
                                .feedbackScores(List.of( // will be used for assertion later
                                        FeedbackScoreAverage.builder()
                                                .name("quality")
                                                .value(new BigDecimal("0.75"))
                                                .build(),
                                        FeedbackScoreAverage.builder()
                                                .name("relevance")
                                                .value(new BigDecimal("0.875"))
                                                .build()))
                                .build();

                        annotationQueuesResourceClient.createAnnotationQueueBatch(
                                new LinkedHashSet<>(List.of(annotationQueue)), apiKey, workspaceName,
                                HttpStatus.SC_NO_CONTENT);

                        // Create traces and add them to the queue
                        var trace1 = createTrace(project.name());
                        var trace2 = createTrace(project.name());
                        var trace3 = createTrace(project.name()); // This trace won't be in the queue

                        var itemIds = Set.of(trace1, trace2);
                        annotationQueuesResourceClient.addItemsToAnnotationQueue(
                                annotationQueue.id(), itemIds, apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

                        // Create feedback scores - some for traces in the queue, some for traces not in the queue
                        createFeedbackScoreForTrace(trace1, "quality", 0.8, project.name(), apiKey, workspaceName);
                        createFeedbackScoreForTrace(trace1, "relevance", 0.9, project.name(), apiKey, workspaceName);
                        createFeedbackScoreForTrace(trace2, "quality", 0.7, project.name(), apiKey, workspaceName);
                        createFeedbackScoreForTrace(trace2, "relevance", 0.85, project.name(), apiKey, workspaceName);

                        // Feedback scores for trace3 (NOT in the queue) - should not be aggregated
                        createFeedbackScoreForTrace(trace3, "quality", 0.5, project.name(), apiKey, workspaceName);
                        createFeedbackScoreForTrace(trace3, "relevance", 0.6, project.name(), apiKey, workspaceName);

                        // Feedback scores with different names (not in feedbackDefinitionNames) - should not be aggregated
                        createFeedbackScoreForTrace(trace1, "other_metric", 0.3, project.name(), apiKey, workspaceName);

                        return annotationQueue;
                    })
                    .toList();

            // When - Request first page with size 2
            var firstPage = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 5, null, null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertPage(firstPage, queues.reversed().subList(0, 5), 1, queueCount);

            // When - Request second page
            var secondPage = annotationQueuesResourceClient.findAnnotationQueues(
                    2, 5, null, null, null, apiKey, workspaceName, HttpStatus.SC_OK);

            // Then
            assertPage(secondPage, queues.reversed().subList(5, queueCount), 2, queueCount);
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

            var queues = IntStream.range(0, 5)
                    .mapToObj(i -> prepareAnnotationQueue(project.name(), projectId))
                    .toList();

            // Create the annotation queues
            annotationQueuesResourceClient.createAnnotationQueueBatch(
                    new LinkedHashSet<>(queues),
                    apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

            // When - Filter by name containing "Alpha"
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, queues.getFirst().name().substring(0, 5), null, null, apiKey, workspaceName,
                    HttpStatus.SC_OK);

            // Then
            assertPage(page, queues.subList(0, 1), 1, 1);
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
        @MethodSource("findAnnotationQueuesWithSorting")
        @DisplayName("should sort annotation queues by different fields and directions")
        void findAnnotationQueuesWithSorting(Comparator<AnnotationQueue> comparator, SortingField sortingField) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create multiple annotation queues with different predictable values
            var annotationQueues = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        var queue = prepareAnnotationQueue(project.name(), projectId);

                        annotationQueuesResourceClient.createAnnotationQueueBatch(
                                new LinkedHashSet<>(List.of(queue)),
                                apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);

                        return queue;
                    })
                    .toList();

            // When - Get all queues with sorting
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, null, null, toURLEncodedQueryParam(List.of(sortingField)),
                    apiKey, workspaceName, HttpStatus.SC_OK);

            // Get the expected order by sorting with the comparator
            var expectedOrder = annotationQueues.stream()
                    .sorted(comparator)
                    .toList();

            assertPage(page, expectedOrder, 1, expectedOrder.size());
        }

        Stream<Arguments> findAnnotationQueuesWithSorting() {
            return Stream.of(
                    arguments(
                            Comparator.comparing(AnnotationQueue::name),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::name).reversed(),
                            SortingField.builder().field(SortableFields.NAME).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::id),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::id).reversed(),
                            SortingField.builder().field(SortableFields.ID).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::projectId)
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.PROJECT_ID).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::projectId).reversed()
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.PROJECT_ID).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::description),
                            SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::description).reversed(),
                            SortingField.builder().field(SortableFields.DESCRIPTION).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::instructions),
                            SortingField.builder().field(SortableFields.INSTRUCTIONS).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::instructions).reversed(),
                            SortingField.builder().field(SortableFields.INSTRUCTIONS).direction(Direction.DESC)
                                    .build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::createdBy)
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::createdBy).reversed()
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_BY).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::lastUpdatedBy)
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.ASC)
                                    .build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::lastUpdatedBy).reversed()
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_BY).direction(Direction.DESC)
                                    .build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::createdAt)
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.ASC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::createdAt).reversed()
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.CREATED_AT).direction(Direction.DESC).build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::lastUpdatedAt)
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.ASC)
                                    .build()),
                    arguments(
                            Comparator.comparing(AnnotationQueue::lastUpdatedAt).reversed()
                                    .thenComparing(Comparator.comparing(AnnotationQueue::id).reversed()),
                            SortingField.builder().field(SortableFields.LAST_UPDATED_AT).direction(Direction.DESC)
                                    .build()));
        }

        @ParameterizedTest
        @MethodSource("getValidFilters")
        @DisplayName("should filter annotation queues by different criteria")
        void findAnnotationQueuesWithFilters(Function<List<AnnotationQueue>, AnnotationQueueFilter> getFilter,
                Function<List<AnnotationQueue>, List<AnnotationQueue>> getExpectedQueues) {
            String workspaceName = UUID.randomUUID().toString();
            String apiKey = UUID.randomUUID().toString();
            String workspaceId = UUID.randomUUID().toString();

            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            // Given - Create a project first
            var project = factory.manufacturePojo(Project.class);
            var projectId = projectResourceClient.createProject(project, apiKey, workspaceName);

            // Create multiple annotation queues with different predictable values
            var annotationQueues = IntStream.range(0, 5)
                    .mapToObj(i -> {
                        var queue = prepareAnnotationQueue(project.name(), projectId);
                        annotationQueuesResourceClient.createAnnotationQueueBatch(
                                new LinkedHashSet<>(List.of(queue)),
                                apiKey, workspaceName, HttpStatus.SC_NO_CONTENT);
                        return queue;
                    })
                    .toList();

            // When - Get queues with filtering
            var filter = getFilter.apply(annotationQueues);
            var filters = List.of(filter);
            var page = annotationQueuesResourceClient.findAnnotationQueues(
                    1, 10, null, toURLEncodedQueryParam(filters), null,
                    apiKey, workspaceName, HttpStatus.SC_OK);

            // Then - Verify filtering worked
            var expectedQueues = getExpectedQueues.apply(annotationQueues);
            assertPage(page, expectedQueues.reversed(), 1, expectedQueues.size());
        }

        Stream<Arguments> getValidFilters() {
            return Stream.of(
                    // Filter by ID - EQUAL
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.ID)
                                    .operator(Operator.EQUAL)
                                    .value(queues.getFirst().id().toString())
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> List
                                    .of(queues.getFirst())),

                    // Filter by PROJECT_ID - EQUAL
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.PROJECT_ID)
                                    .operator(Operator.EQUAL)
                                    .value(queues.getFirst().projectId().toString())
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> queues),

                    // Filter by NAME - EQUAL
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.NAME)
                                    .operator(Operator.EQUAL)
                                    .value(queues.getFirst().name())
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> List
                                    .of(queues.getFirst())),

                    // Filter by NAME - STARTS_WITH
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.NAME)
                                    .operator(Operator.STARTS_WITH)
                                    .value("Alpha")
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> queues.stream()
                                    .filter(q -> q.name().startsWith("Alpha"))
                                    .toList()),

                    // Filter by DESCRIPTION - CONTAINS
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.DESCRIPTION)
                                    .operator(Operator.CONTAINS)
                                    .value("Beta")
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> queues.stream()
                                    .filter(q -> q.description().contains("Beta"))
                                    .toList()),

                    // Filter by INSTRUCTIONS - EQUAL
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.INSTRUCTIONS)
                                    .operator(Operator.EQUAL)
                                    .value(queues.get(1).instructions())
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> List.of(queues.get(1))),

                    // Filter by SCOPE - EQUAL (TRACE)
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.SCOPE)
                                    .operator(Operator.EQUAL)
                                    .value("trace")
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> queues.stream()
                                    .filter(q -> q.scope() == AnnotationQueue.AnnotationScope.TRACE)
                                    .toList()),

                    // Filter by FEEDBACK_DEFINITION_NAMES
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.FEEDBACK_DEFINITION_NAMES)
                                    .operator(Operator.CONTAINS)
                                    .value(queues.getFirst().feedbackDefinitionNames().getFirst())
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> List
                                    .of(queues.getFirst())),

                    // Filter by CREATED_BY - EQUAL
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.CREATED_BY)
                                    .operator(Operator.EQUAL)
                                    .value(USER)
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> queues),

                    // Filter by LAST_UPDATED_BY - CONTAINS
                    arguments(
                            (Function<List<AnnotationQueue>, AnnotationQueueFilter>) queues -> AnnotationQueueFilter
                                    .builder()
                                    .field(AnnotationQueueField.LAST_UPDATED_BY)
                                    .operator(Operator.CONTAINS)
                                    .value(USER.substring(0, 3))
                                    .build(),
                            (Function<List<AnnotationQueue>, List<AnnotationQueue>>) queues -> queues));
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
        createFeedbackScoreForTrace(traceId, scoreName, scoreValue, projectName, API_KEY, TEST_WORKSPACE);
    }

    private void createFeedbackScoreForTrace(UUID traceId, String scoreName, double scoreValue,
            String projectName, String apiKey, String workspaceName) {
        var feedbackScore = factory.manufacturePojo(FeedbackScoreBatchItem.class).toBuilder()
                .id(traceId)
                .name(scoreName)
                .value(BigDecimal.valueOf(scoreValue))
                .projectName(projectName)
                .build();
        traceResourceClient.feedbackScores(List.of(feedbackScore), apiKey, workspaceName);
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

    private AnnotationQueue prepareAnnotationQueue(String projectName, UUID projectId) {
        return factory.manufacturePojo(AnnotationQueue.class)
                .toBuilder()
                .projectId(projectId)
                .projectName(projectName)
                .itemsCount(null)
                .feedbackScores(null)
                .reviewers(null)
                .createdBy(USER)
                .lastUpdatedBy(USER)
                .build();
    }

    private void assertPage(AnnotationQueue.AnnotationQueuePage actualPage, List<AnnotationQueue> expectedQueues,
            int expectedPage, int expectedTotal) {
        assertThat(actualPage.page()).isEqualTo(expectedPage);
        assertThat(actualPage.size()).isEqualTo(expectedQueues.size());
        assertThat(actualPage.total()).isEqualTo(expectedTotal);

        assertThat(actualPage.content())
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringFields(QUEUE_IGNORED_FIELDS)
                .isEqualTo(expectedQueues);

        // verify ignored field if present
        for (int i = 0; i < expectedQueues.size(); i++) {
            var expectedQueue = expectedQueues.get(i);
            var actualQueue = actualPage.content().get(i);

            if (expectedQueue.itemsCount() != null) {
                assertThat(actualQueue.itemsCount()).isEqualTo(expectedQueue.itemsCount());
            }

            if (expectedQueue.reviewers() != null) {
                assertThat(actualQueue.reviewers())
                        .usingRecursiveComparison()
                        .ignoringCollectionOrder()
                        .isEqualTo(expectedQueue.reviewers());
            }

            if (expectedQueue.feedbackScores() != null) {
                assertThat(actualQueue.feedbackScores())
                        .usingRecursiveComparison(
                                RecursiveComparisonConfiguration.builder()
                                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                        .build())
                        .ignoringCollectionOrder()
                        .isEqualTo(expectedQueue.feedbackScores());
            }
        }
    }

    private AnnotationQueue applyUpdate(AnnotationQueueUpdate updateRequest, AnnotationQueue existingQueue) {
        return existingQueue.toBuilder()
                .name(updateRequest.name() != null ? updateRequest.name() : existingQueue.name())
                .description(
                        updateRequest.description() != null ? updateRequest.description() : existingQueue.description())
                .instructions(updateRequest.instructions() != null
                        ? updateRequest.instructions()
                        : existingQueue.instructions())
                .commentsEnabled(updateRequest.commentsEnabled() != null
                        ? updateRequest.commentsEnabled()
                        : existingQueue.commentsEnabled())
                .feedbackDefinitionNames(updateRequest.feedbackDefinitionNames() != null
                        ? updateRequest.feedbackDefinitionNames()
                        : existingQueue.feedbackDefinitionNames())
                .build();
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
