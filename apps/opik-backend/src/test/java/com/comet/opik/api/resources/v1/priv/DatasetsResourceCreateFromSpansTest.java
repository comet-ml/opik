package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Comment;
import com.comet.opik.api.CreateDatasetItemsFromSpansRequest;
import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.SpanEnrichmentOptions;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.redis.testcontainers.RedisContainer;
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

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for creating dataset items from spans endpoint.
 * Follows the same pattern as DatasetsResourceCreateFromTracesTest.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Create dataset items from spans:")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsResourceCreateFromSpansTest {

    private static final TimeBasedEpochGenerator GENERATOR = Generators.timeBasedEpochGenerator();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private DatasetResourceClient datasetResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    @BeforeAll
    void setUpAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.datasetResourceClient = new DatasetResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.spanResourceClient = new SpanResourceClient(client, baseURI);

        ClientSupportUtils.config(client);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId,
                UUID.randomUUID().toString());
    }

    private UUID createAndAssert(Dataset dataset, String apiKey, String workspaceName) {
        return datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
    }

    private void assertMapKeysPresence(Map<String, JsonNode> data, Set<String> expectedKeys,
            Set<String> unexpectedKeys) {
        expectedKeys.forEach(key -> assertThat(data).containsKey(key));
        unexpectedKeys.forEach(key -> assertThat(data).doesNotContainKey(key));
    }

    @Test
    @DisplayName("Success - create dataset items from spans with all enrichment options")
    void createDatasetItemsFromSpans__success() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        // Create trace and spans
        String projectName = GENERATOR.generate().toString();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        var span1 = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace.id())
                .name("span1")
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 1\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 1\"}"))
                .tags(Set.of("tag1", "tag2"))
                .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value1\"}"))
                .usage(Map.of("tokens", 100))
                .build();

        var span2 = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace.id())
                .name("span2")
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 2\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 2\"}"))
                .tags(Set.of("tag3"))
                .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value2\"}"))
                .usage(Map.of("tokens", 200))
                .build();

        spanResourceClient.createSpan(span1, apiKey, workspaceName);
        spanResourceClient.createSpan(span2, apiKey, workspaceName);

        // Add feedback scores to span1
        var feedbackScore1 = FeedbackScoreBatchItem.builder()
                .id(span1.id())
                .projectName(projectName)
                .name("accuracy")
                .value(BigDecimal.valueOf(0.95))
                .reason("High accuracy")
                .source(ScoreSource.SDK)
                .build();

        var feedbackScore2 = FeedbackScoreBatchItem.builder()
                .id(span1.id())
                .projectName(projectName)
                .name("relevance")
                .value(BigDecimal.valueOf(0.88))
                .source(ScoreSource.SDK)
                .build();

        spanResourceClient.feedbackScores(List.of(feedbackScore1, feedbackScore2), apiKey, workspaceName);

        // Add comment to span1
        var comment = new Comment(
                null,
                "Test comment",
                null,
                null,
                null,
                null);

        spanResourceClient.createComment(comment, span1.id(), apiKey, workspaceName, 201);

        // Create request with all enrichment options
        var request = CreateDatasetItemsFromSpansRequest.builder()
                .spanIds(Set.of(span1.id(), span2.id()))
                .enrichmentOptions(SpanEnrichmentOptions.builder()
                        .includeTags(true)
                        .includeFeedbackScores(true)
                        .includeComments(true)
                        .includeUsage(true)
                        .includeMetadata(true)
                        .build())
                .build();

        // Call endpoint
        datasetResourceClient.createDatasetItemsFromSpans(datasetId, request, apiKey, workspaceName);

        // Verify dataset items were created
        var actualEntity = datasetResourceClient.getDatasetItems(datasetId, Map.of(), apiKey, workspaceName);

        assertThat(actualEntity.content()).hasSize(2);

        // Verify enriched data
        var item1 = actualEntity.content().stream()
                .filter(item -> item.spanId() != null && item.spanId().equals(span1.id()))
                .findFirst()
                .orElseThrow();

        assertThat(item1.source()).isEqualTo(DatasetItemSource.SPAN);
        assertMapKeysPresence(item1.data(),
                Set.of("input", "expected_output", "tags", "metadata", "feedback_scores", "comments", "usage"),
                Set.of());

        // Verify feedback scores are included and properly serialized (without timestamps)
        JsonNode feedbackScoresNode = item1.data().get("feedback_scores");
        assertThat(feedbackScoresNode.isArray()).isTrue();
        assertThat(feedbackScoresNode).hasSize(2);

        // Verify feedback score structure
        JsonNode score1Node = feedbackScoresNode.get(0);
        assertThat(score1Node.has("name")).isTrue();
        assertThat(score1Node.has("value")).isTrue();
        assertThat(score1Node.has("source")).isTrue();
        // Verify timestamps are NOT included
        assertThat(score1Node.has("created_at")).isFalse();
        assertThat(score1Node.has("last_updated_at")).isFalse();
        assertThat(score1Node.has("created_by")).isFalse();
        assertThat(score1Node.has("last_updated_by")).isFalse();

        // Verify comments are included
        JsonNode commentsNode = item1.data().get("comments");
        assertThat(commentsNode.isArray()).isTrue();
        assertThat(commentsNode).hasSize(1);

        // Verify usage is included
        JsonNode usageNode = item1.data().get("usage");
        assertThat(usageNode.isObject()).isTrue();
    }

    @Test
    @DisplayName("Success - create dataset items with no enrichment options")
    void createDatasetItemsFromSpans__withNoEnrichmentOptions__success() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        // Create trace and span WITH metadata
        String projectName = GENERATOR.generate().toString();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        var span = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace.id())
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                .tags(Set.of("tag1", "tag2"))
                .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value\"}"))
                .usage(Map.of("tokens", 100))
                .build();

        spanResourceClient.createSpan(span, apiKey, workspaceName);

        // Add feedback scores to span
        var feedbackScore = FeedbackScoreBatchItem.builder()
                .id(span.id())
                .projectName(projectName)
                .name("accuracy")
                .value(BigDecimal.valueOf(0.95))
                .reason("High accuracy")
                .source(ScoreSource.SDK)
                .build();

        spanResourceClient.feedbackScores(List.of(feedbackScore), apiKey, workspaceName);

        // Add comment to span
        var comment = new Comment(
                null,
                "Test comment",
                null,
                null,
                null,
                null);

        spanResourceClient.createComment(comment, span.id(), apiKey, workspaceName, 201);

        // Create request with no enrichment options (all disabled)
        var request = CreateDatasetItemsFromSpansRequest.builder()
                .spanIds(Set.of(span.id()))
                .enrichmentOptions(
                        SpanEnrichmentOptions.builder().build())
                .build();

        // Call endpoint
        datasetResourceClient.createDatasetItemsFromSpans(datasetId, request, apiKey, workspaceName);

        // Verify dataset item was created with only input and output
        var actualEntity = datasetResourceClient.getDatasetItems(datasetId, Map.of(), apiKey, workspaceName);

        assertThat(actualEntity.content()).hasSize(1);

        var item = actualEntity.content().getFirst();

        // Verify enrichment fields are NOT included even though they exist on the span
        assertMapKeysPresence(item.data(),
                Set.of("input", "expected_output"),
                Set.of("tags", "metadata", "feedback_scores", "comments", "usage"));
    }

    @Test
    @DisplayName("when dataset not found, then return 404")
    void createDatasetItemsFromSpans__whenDatasetNotFound__thenReturn404() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        UUID nonExistentDatasetId = UUID.randomUUID();

        var request = CreateDatasetItemsFromSpansRequest.builder()
                .spanIds(Set.of(UUID.randomUUID()))
                .enrichmentOptions(
                        SpanEnrichmentOptions.builder().build())
                .build();

        try (var actualResponse = datasetResourceClient.callCreateDatasetItemsFromSpans(nonExistentDatasetId,
                request, apiKey, workspaceName)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("when span IDs are empty, then return 422")
    void createDatasetItemsFromSpans__whenSpanIdsAreEmpty__thenReturn422() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        var request = CreateDatasetItemsFromSpansRequest.builder()
                .spanIds(Set.of())
                .enrichmentOptions(
                        SpanEnrichmentOptions.builder().build())
                .build();

        try (var actualResponse = datasetResourceClient.callCreateDatasetItemsFromSpans(datasetId, request, apiKey,
                workspaceName)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
        }
    }

    @Test
    @DisplayName("Success - enrichment options enabled but span has no additional data")
    void createDatasetItemsFromSpans__withAllEnrichmentOptionsButNoData__success() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        // Create trace and simple span with only input and output, no tags, metadata, usage, etc.
        String projectName = GENERATOR.generate().toString();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .build();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        var span = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace.id())
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"simple prompt\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"simple response\"}"))
                .tags(null)
                .metadata(null)
                .usage(null)
                .provider(null)
                .build();

        spanResourceClient.createSpan(span, apiKey, workspaceName);

        // Create request with ALL enrichment options enabled
        var request = CreateDatasetItemsFromSpansRequest.builder()
                .spanIds(Set.of(span.id()))
                .enrichmentOptions(SpanEnrichmentOptions.builder()
                        .includeTags(true)
                        .includeFeedbackScores(true)
                        .includeComments(true)
                        .includeUsage(true)
                        .includeMetadata(true)
                        .build())
                .build();

        // Call endpoint
        datasetResourceClient.createDatasetItemsFromSpans(datasetId, request, apiKey, workspaceName);

        // Verify dataset item was created with only input and output
        var actualEntity = datasetResourceClient.getDatasetItems(datasetId, Map.of(), apiKey, workspaceName);

        assertThat(actualEntity.content()).hasSize(1);

        var item = actualEntity.content().getFirst();
        assertThat(item.source()).isEqualTo(DatasetItemSource.SPAN);

        // Verify enrichment fields are NOT included when span has no data for them
        assertMapKeysPresence(item.data(),
                Set.of("input", "expected_output"),
                Set.of("tags", "metadata", "feedback_scores", "comments", "usage"));
    }
}
