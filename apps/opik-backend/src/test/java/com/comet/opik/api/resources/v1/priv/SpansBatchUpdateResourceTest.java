package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.ErrorInfo;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.uuid.Generators;
import com.fasterxml.uuid.impl.TimeBasedEpochGenerator;
import com.redis.testcontainers.RedisContainer;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.domain.ProjectService.DEFAULT_PROJECT;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
@DisplayName("Spans Batch Update Resource Test")
class SpansBatchUpdateResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer redisContainer = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer mySqlContainer = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> zookeeperContainer = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer clickHouseContainer = ClickHouseContainerUtils
            .newClickHouseContainer(zookeeperContainer);
    private final GenericContainer<?> minIOContainer = MinIOContainerUtils.newMinIOContainer();
    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension app;

    {
        Startables.deepStart(redisContainer, mySqlContainer, clickHouseContainer, zookeeperContainer, minIOContainer)
                .join();
        String minioUrl = "http://%s:%d".formatted(minIOContainer.getHost(), minIOContainer.getMappedPort(9000));

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                clickHouseContainer, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(mySqlContainer);
        MigrationUtils.runClickhouseDbMigration(clickHouseContainer);
        MinIOContainerUtils.setupBucketAndCredentials(minioUrl);

        app = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(mySqlContainer.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(redisContainer.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .isMinIO(true)
                        .minioUrl(minioUrl)
                        .build());
    }

    private final PodamFactory podamFactory = PodamFactoryUtils.newPodamFactory();
    private final TimeBasedEpochGenerator generator = Generators.timeBasedEpochGenerator();

    private String baseURI;
    private ClientSupport client;
    private ProjectResourceClient projectResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) throws SQLException {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;

        ClientSupportUtils.config(client);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        this.projectResourceClient = new ProjectResourceClient(this.client, baseURI, podamFactory);
        this.traceResourceClient = new TraceResourceClient(this.client, baseURI);
        this.spanResourceClient = new SpanResourceClient(this.client, baseURI);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @Nested
    @DisplayName("Batch Update Tags:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchUpdateTags {

        private UUID traceId;

        @BeforeEach
        void setUp() {
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();
            traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
        }

        Stream<Arguments> mergeTagsTestCases() {
            return Stream.of(
                    Arguments.of(true, "merge"),
                    Arguments.of(false, "replace"));
        }

        @ParameterizedTest(name = "Success: batch update tags with {1} mode")
        @MethodSource("mergeTagsTestCases")
        @DisplayName("Success: batch update tags for multiple spans")
        void batchUpdate__success(boolean mergeTags, String mode) {
            // Create spans with existing tags
            var span1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .tags(mergeTags ? Set.of("existing-tag-1", "existing-tag-2") : Set.of("old-tag-1", "old-tag-2"))
                    .build();
            var span2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .tags(mergeTags ? Set.of("existing-tag-3") : Set.of("old-tag-3"))
                    .build();
            var span3 = mergeTags
                    ? podamFactory.manufacturePojo(Span.class).toBuilder()
                            .projectName(DEFAULT_PROJECT)
                            .traceId(traceId)
                            .parentSpanId(null)
                            .tags(null)
                            .build()
                    : null;

            var id1 = spanResourceClient.createSpan(span1, API_KEY, TEST_WORKSPACE);
            var id2 = spanResourceClient.createSpan(span2, API_KEY, TEST_WORKSPACE);
            var id3 = mergeTags ? spanResourceClient.createSpan(span3, API_KEY, TEST_WORKSPACE) : null;

            // Batch update with new tags
            var newTags = mergeTags ? Set.of("new-tag-1", "new-tag-2") : Set.of("new-tag");
            var ids = mergeTags ? Set.of(id1, id2, id3) : Set.of(id1, id2);
            var batchUpdate = SpanBatchUpdate.builder()
                    .ids(ids)
                    .update(SpanUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .traceId(traceId)
                            .tags(newTags)
                            .build())
                    .mergeTags(mergeTags)
                    .build();

            spanResourceClient.batchUpdateSpans(batchUpdate, API_KEY, TEST_WORKSPACE);

            // Verify spans were updated
            var updatedSpan1 = spanResourceClient.getById(id1, TEST_WORKSPACE, API_KEY);
            if (mergeTags) {
                assertThat(updatedSpan1.tags()).containsExactlyInAnyOrder(
                        "existing-tag-1", "existing-tag-2", "new-tag-1", "new-tag-2");
            } else {
                assertThat(updatedSpan1.tags()).containsExactly("new-tag");
            }

            var updatedSpan2 = spanResourceClient.getById(id2, TEST_WORKSPACE, API_KEY);
            if (mergeTags) {
                assertThat(updatedSpan2.tags()).containsExactlyInAnyOrder("existing-tag-3", "new-tag-1", "new-tag-2");
            } else {
                assertThat(updatedSpan2.tags()).containsExactly("new-tag");
            }

            if (mergeTags) {
                var updatedSpan3 = spanResourceClient.getById(id3, TEST_WORKSPACE, API_KEY);
                assertThat(updatedSpan3.tags()).containsExactlyInAnyOrder("new-tag-1", "new-tag-2");
            }
        }

        @Test
        @DisplayName("when batch update with empty IDs, then return 400")
        void batchUpdate__whenEmptyIds__thenReturn400() {
            var batchUpdate = SpanBatchUpdate.builder()
                    .ids(Set.of())
                    .update(SpanUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .traceId(traceId)
                            .tags(Set.of("tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            try (var actualResponse = spanResourceClient.callBatchUpdateSpans(batchUpdate, API_KEY, TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                var error = actualResponse.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anySatisfy(msg -> assertThat(msg).contains("ids"));
            }
        }

        @Test
        @DisplayName("when batch update with too many IDs, then return 400")
        void batchUpdate__whenTooManyIds__thenReturn400() {
            // Create 1001 IDs (exceeds max of 1000)
            var ids = new HashSet<UUID>();
            for (int i = 0; i < 1001; i++) {
                ids.add(generator.generate());
            }

            var batchUpdate = SpanBatchUpdate.builder()
                    .ids(ids)
                    .update(SpanUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .traceId(traceId)
                            .tags(Set.of("tag"))
                            .build())
                    .mergeTags(true)
                    .build();

            try (var actualResponse = spanResourceClient.callBatchUpdateSpans(batchUpdate, API_KEY, TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
                var error = actualResponse.readEntity(ErrorMessage.class);
                assertThat(error.errors()).anySatisfy(msg -> assertThat(msg).contains("ids"));
            }
        }

        @Test
        @DisplayName("when batch update with null update, then return 400")
        void batchUpdate__whenNullUpdate__thenReturn400() {
            var span = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .build();
            var id = spanResourceClient.createSpan(span, API_KEY, TEST_WORKSPACE);

            var batchUpdate = SpanBatchUpdate.builder()
                    .ids(Set.of(id))
                    .update(null)
                    .mergeTags(true)
                    .build();

            try (var actualResponse = spanResourceClient.callBatchUpdateSpans(batchUpdate, API_KEY, TEST_WORKSPACE)) {
                assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(422);
                assertThat(actualResponse.hasEntity()).isTrue();
            }
        }
    }

    @Nested
    @DisplayName("Batch Update All Fields:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class BatchUpdateAllFields {

        private UUID traceId;

        @BeforeEach
        void setUp() {
            var trace = podamFactory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .feedbackScores(null)
                    .build();
            traceId = traceResourceClient.createTrace(trace, API_KEY, TEST_WORKSPACE);
        }

        @Test
        @DisplayName("Success: batch update all fields simultaneously")
        void batchUpdate__updateAllFields__success() {
            var span1 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .build();
            var span2 = podamFactory.manufacturePojo(Span.class).toBuilder()
                    .projectName(DEFAULT_PROJECT)
                    .traceId(traceId)
                    .parentSpanId(null)
                    .build();

            var id1 = spanResourceClient.createSpan(span1, API_KEY, TEST_WORKSPACE);
            var id2 = spanResourceClient.createSpan(span2, API_KEY, TEST_WORKSPACE);

            Instant newEndTime = Instant.now();
            JsonNode newInput = JsonUtils.readTree(Map.of("prompt", "updated prompt"));
            JsonNode newOutput = JsonUtils.readTree(Map.of("response", "updated response"));
            JsonNode newMetadata = JsonUtils.readTree(Map.of("environment", "production", "key1", "value1"));
            var newUsage = Map.of("prompt_tokens", 500, "completion_tokens", 250);
            BigDecimal newCost = new BigDecimal("0.005");
            var newErrorInfo = ErrorInfo.builder()
                    .exceptionType("ValidationError")
                    .message("Invalid input")
                    .traceback("Stack trace here")
                    .build();
            Double newTtft = 123.45;

            var batchUpdate = SpanBatchUpdate.builder()
                    .ids(Set.of(id1, id2))
                    .update(SpanUpdate.builder()
                            .projectName(DEFAULT_PROJECT)
                            .traceId(traceId)
                            .name("updated-name")
                            .type(SpanType.llm)
                            .endTime(newEndTime)
                            .input(newInput)
                            .output(newOutput)
                            .metadata(newMetadata)
                            .tags(Set.of("new-tag"))
                            .model("gpt-4")
                            .provider("openai")
                            .usage(newUsage)
                            .totalEstimatedCost(newCost)
                            .errorInfo(newErrorInfo)
                            .ttft(newTtft)
                            .build())
                    .mergeTags(false)
                    .build();

            spanResourceClient.batchUpdateSpans(batchUpdate, API_KEY, TEST_WORKSPACE);

            var updatedSpan1 = spanResourceClient.getById(id1, TEST_WORKSPACE, API_KEY);
            assertSpanFieldsUpdated(updatedSpan1, newEndTime);

            var updatedSpan2 = spanResourceClient.getById(id2, TEST_WORKSPACE, API_KEY);
            assertSpanFieldsUpdated(updatedSpan2, newEndTime);
        }

        private void assertSpanFieldsUpdated(Span span, Instant expectedEndTime) {
            assertThat(span.name()).isEqualTo("updated-name");
            assertThat(span.type()).isEqualTo(SpanType.llm);
            assertThat(span.endTime().toEpochMilli()).isEqualTo(expectedEndTime.toEpochMilli());
            assertThat(span.input().get("prompt").asText()).isEqualTo("updated prompt");
            assertThat(span.output().get("response").asText()).isEqualTo("updated response");
            assertThat(span.metadata().get("environment").asText()).isEqualTo("production");
            assertThat(span.metadata().get("key1").asText()).isEqualTo("value1");
            assertThat(span.tags()).containsExactly("new-tag");
            assertThat(span.model()).isEqualTo("gpt-4");
            assertThat(span.provider()).isEqualTo("openai");
            assertThat(span.usage()).containsEntry("prompt_tokens", 500);
            assertThat(span.usage()).containsEntry("completion_tokens", 250);
            assertThat(span.totalEstimatedCost()).isEqualByComparingTo(new BigDecimal("0.005"));
            assertThat(span.errorInfo()).isNotNull();
            assertThat(span.errorInfo().exceptionType()).isEqualTo("ValidationError");
            assertThat(span.errorInfo().message()).isEqualTo("Invalid input");
            assertThat(span.ttft()).isEqualTo(123.45);
        }
    }
}
