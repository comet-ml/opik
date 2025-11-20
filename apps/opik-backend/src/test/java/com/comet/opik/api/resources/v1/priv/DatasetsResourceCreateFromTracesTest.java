package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.Comment;
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
import com.comet.opik.domain.TraceEnrichmentOptions;
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
 * Integration tests for creating dataset items from traces endpoint.
 * Extracted from DatasetsResourceTest to reduce file size.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Create dataset items from traces:")
@ExtendWith(DropwizardAppExtensionProvider.class)
class DatasetsResourceCreateFromTracesTest {

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

    @Test
    @DisplayName("Success - create dataset items from traces with all enrichment options")
    void createDatasetItemsFromTraces__success() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        // Create traces with spans
        String projectName = GENERATOR.generate().toString();
        var trace1 = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 1\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 1\"}"))
                .tags(Set.of("tag1", "tag2"))
                .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value1\"}"))
                .usage(Map.of("tokens", 100L))
                .build();

        var trace2 = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt 2\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response 2\"}"))
                .tags(Set.of("tag3"))
                .metadata(JsonUtils.getJsonNodeFromString("{\"key\": \"value2\"}"))
                .usage(Map.of("tokens", 200L))
                .build();

        traceResourceClient.createTrace(trace1, apiKey, workspaceName);
        traceResourceClient.createTrace(trace2, apiKey, workspaceName);

        // Add feedback scores to trace1
        var feedbackScore1 = FeedbackScoreBatchItem.builder()
                .id(trace1.id())
                .projectName(projectName)
                .name("accuracy")
                .value(BigDecimal.valueOf(0.95))
                .reason("High accuracy")
                .source(ScoreSource.SDK)
                .build();

        var feedbackScore2 = FeedbackScoreBatchItem.builder()
                .id(trace1.id())
                .projectName(projectName)
                .name("relevance")
                .value(BigDecimal.valueOf(0.88))
                .source(ScoreSource.SDK)
                .build();

        traceResourceClient.feedbackScores(List.of(feedbackScore1, feedbackScore2), apiKey, workspaceName);

        // Add comment to trace1
        var comment = new Comment(
                null,
                "Test comment",
                null,
                null,
                null,
                null);

        traceResourceClient.createComment(comment, trace1.id(), apiKey, workspaceName, 201);

        // Create spans for trace1
        var span1 = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace1.id())
                .name("span1")
                .input(JsonUtils.getJsonNodeFromString("{\"input\": \"span1 input\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"output\": \"span1 output\"}"))
                .build();

        var span2 = factory.manufacturePojo(Span.class).toBuilder()
                .projectName(projectName)
                .traceId(trace1.id())
                .parentSpanId(span1.id())
                .name("span2")
                .input(JsonUtils.getJsonNodeFromString("{\"input\": \"span2 input\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"output\": \"span2 output\"}"))
                .build();

        spanResourceClient.createSpan(span1, apiKey, workspaceName);
        spanResourceClient.createSpan(span2, apiKey, workspaceName);

        // Create request with all enrichment options
        var request = com.comet.opik.api.CreateDatasetItemsFromTracesRequest.builder()
                .traceIds(Set.of(trace1.id(), trace2.id()))
                .enrichmentOptions(TraceEnrichmentOptions.builder()
                        .includeSpans(true)
                        .includeTags(true)
                        .includeFeedbackScores(true)
                        .includeComments(true)
                        .includeUsage(true)
                        .includeMetadata(true)
                        .build())
                .build();

        // Call endpoint
        datasetResourceClient.createDatasetItemsFromTraces(datasetId, request, apiKey, workspaceName);

        // Verify dataset items were created
        var actualEntity = datasetResourceClient.getDatasetItems(datasetId, Map.of(), apiKey, workspaceName);

        assertThat(actualEntity.content()).hasSize(2);

        // Verify enriched data
        var item1 = actualEntity.content().stream()
                .filter(item -> item.traceId().equals(trace1.id()))
                .findFirst()
                .orElseThrow();

        assertThat(item1.source()).isEqualTo(DatasetItemSource.TRACE);
        assertThat(item1.data()).containsKey("input");
        assertThat(item1.data()).containsKey("expected_output");
        assertThat(item1.data()).containsKey("spans");
        assertThat(item1.data()).containsKey("tags");
        assertThat(item1.data()).containsKey("metadata");
        assertThat(item1.data()).containsKey("feedback_scores");
        assertThat(item1.data()).containsKey("comments");
        assertThat(item1.data()).containsKey("usage");

        // Verify spans are included
        JsonNode spansNode = item1.data().get("spans");
        assertThat(spansNode.isArray()).isTrue();
        assertThat(spansNode).hasSize(2);

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
    void createDatasetItemsFromTraces__withNoEnrichmentOptions__success() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        // Create trace
        String projectName = GENERATOR.generate().toString();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"test prompt\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"test response\"}"))
                .build();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        // Create request with no enrichment options
        var request = com.comet.opik.api.CreateDatasetItemsFromTracesRequest.builder()
                .traceIds(Set.of(trace.id()))
                .enrichmentOptions(
                        TraceEnrichmentOptions.builder().build())
                .build();

        // Call endpoint
        datasetResourceClient.createDatasetItemsFromTraces(datasetId, request, apiKey, workspaceName);

        // Verify dataset item was created with only input and output
        var actualEntity = datasetResourceClient.getDatasetItems(datasetId, Map.of(), apiKey, workspaceName);

        assertThat(actualEntity.content()).hasSize(1);

        var item = actualEntity.content().getFirst();
        assertThat(item.data()).containsKey("input");
        assertThat(item.data()).containsKey("expected_output");
        assertThat(item.data()).doesNotContainKey("spans");
        assertThat(item.data()).doesNotContainKey("tags");
        assertThat(item.data()).doesNotContainKey("metadata");
    }

    @Test
    @DisplayName("when dataset not found, then return 404")
    void createDatasetItemsFromTraces__whenDatasetNotFound__thenReturn404() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        UUID nonExistentDatasetId = UUID.randomUUID();

        var request = com.comet.opik.api.CreateDatasetItemsFromTracesRequest.builder()
                .traceIds(Set.of(UUID.randomUUID()))
                .enrichmentOptions(
                        TraceEnrichmentOptions.builder().build())
                .build();

        try (var actualResponse = datasetResourceClient.callCreateDatasetItemsFromTraces(nonExistentDatasetId,
                request, apiKey, workspaceName)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(404);
        }
    }

    @Test
    @DisplayName("when trace IDs are empty, then return 422")
    void createDatasetItemsFromTraces__whenTraceIdsAreEmpty__thenReturn422() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        var request = com.comet.opik.api.CreateDatasetItemsFromTracesRequest.builder()
                .traceIds(Set.of())
                .enrichmentOptions(
                        TraceEnrichmentOptions.builder().build())
                .build();

        try (var actualResponse = datasetResourceClient.callCreateDatasetItemsFromTraces(datasetId, request, apiKey,
                workspaceName)) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
        }
    }

    @Test
    @DisplayName("Success - enrichment options enabled but trace has no additional data")
    void createDatasetItemsFromTraces__withAllEnrichmentOptionsButNoData__success() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = UUID.randomUUID().toString();
        String workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Create dataset
        var dataset = factory.manufacturePojo(Dataset.class).toBuilder().id(null).build();
        var datasetId = createAndAssert(dataset, apiKey, workspaceName);

        // Create simple trace with only input and output, no tags, metadata, usage, etc.
        String projectName = GENERATOR.generate().toString();
        var trace = factory.manufacturePojo(Trace.class).toBuilder()
                .projectName(projectName)
                .input(JsonUtils.getJsonNodeFromString("{\"prompt\": \"simple prompt\"}"))
                .output(JsonUtils.getJsonNodeFromString("{\"response\": \"simple response\"}"))
                .tags(null)
                .metadata(null)
                .usage(null)
                .build();

        traceResourceClient.createTrace(trace, apiKey, workspaceName);

        // Create request with ALL enrichment options enabled
        var request = com.comet.opik.api.CreateDatasetItemsFromTracesRequest.builder()
                .traceIds(Set.of(trace.id()))
                .enrichmentOptions(TraceEnrichmentOptions.builder()
                        .includeSpans(true)
                        .includeTags(true)
                        .includeFeedbackScores(true)
                        .includeComments(true)
                        .includeUsage(true)
                        .includeMetadata(true)
                        .build())
                .build();

        // Call endpoint
        datasetResourceClient.createDatasetItemsFromTraces(datasetId, request, apiKey, workspaceName);

        // Verify dataset item was created with only input and output
        var actualEntity = datasetResourceClient.getDatasetItems(datasetId, Map.of(), apiKey, workspaceName);

        assertThat(actualEntity.content()).hasSize(1);

        var item = actualEntity.content().getFirst();
        assertThat(item.source()).isEqualTo(DatasetItemSource.TRACE);
        assertThat(item.data()).containsKey("input");
        assertThat(item.data()).containsKey("expected_output");

        // Verify enrichment fields are NOT included when trace has no data for them
        assertThat(item.data()).doesNotContainKey("spans");
        assertThat(item.data()).doesNotContainKey("tags");
        assertThat(item.data()).doesNotContainKey("metadata");
        assertThat(item.data()).doesNotContainKey("feedback_scores");
        assertThat(item.data()).doesNotContainKey("comments");
        assertThat(item.data()).doesNotContainKey("usage");
    }
}
