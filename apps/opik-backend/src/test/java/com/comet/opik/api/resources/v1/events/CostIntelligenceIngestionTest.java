package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
import com.comet.opik.api.SpanBatchUpdate;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceUpdate;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.retention.RetentionUtils;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@DisplayName("Cost Intelligence Ingestion Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@ExtendWith(DropwizardAppExtensionProvider.class)
class CostIntelligenceIngestionTest {

    private static final String USER = UUID.randomUUID().toString();

    private static final JsonNode NON_CIPX_METADATA = JsonUtils.getJsonNodeFromString("{\"foo\":\"bar\"}");

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE = ClickHouseContainerUtils.newClickHouseContainer(ZOOKEEPER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL, CLICKHOUSE, ZOOKEEPER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(CLICKHOUSE, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private TransactionTemplateAsync clickHouseTemplate;
    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.clickHouseTemplate = clickHouseTemplate;

        ClientSupportUtils.config(client);

        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
    }

    @Nested
    @DisplayName("cipx_spend ingestion")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Spend {

        @Test
        @DisplayName("span created with a cipx call lands in cipx_spend; a non-cipx sibling does not")
        void spanCreatedWithCipxCallLands() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            var cipxSpan = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(spanCipxMetadata("claude-sonnet-4-6", 100, 20, 5, 40))
                    .build();
            var plainSpan = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(NON_CIPX_METADATA)
                    .build();

            spanResourceClient.batchCreateSpans(List.of(cipxSpan, plainSpan), ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxSpend(cipxSpan.id(), ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().model()).isEqualTo("claude-sonnet-4-6");
                assertThat(row.get().uInput()).isEqualTo(100L);
                assertThat(row.get().uCacheRead()).isEqualTo(20L);
                assertThat(row.get().uCacheCreation()).isEqualTo(5L);
                assertThat(row.get().uOutput()).isEqualTo(40L);
                assertThat(row.get().projectId()).isNotBlank();
                assertThat(row.get().startMs()).isEqualTo(cipxSpan.startTime().toEpochMilli());
                // identity_context block dropped in Java; the two real blocks remain, in order
                assertThat(row.get().blockCount()).isEqualTo(2L);
                assertThat(row.get().blockCategories()).isEqualTo("user_prompt,tool_call");
            });

            // The non-cipx span shared the same create event, so once the cipx row is present the
            // listener has already decided this one: it must not have produced a row.
            assertThat(getCipxSpend(plainSpan.id(), ws.workspaceId())).isEmpty();
        }

        @Test
        @DisplayName("cipx appearing only on a span update inserts the row")
        void spanCipxAppearingOnUpdateInserts() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(NON_CIPX_METADATA)
                    .build();
            var spanId = spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            var update = SpanUpdate.builder()
                    .projectName(projectName)
                    .traceId(span.traceId())
                    .parentSpanId(span.parentSpanId())
                    .metadata(spanCipxMetadata("claude-opus-4-8", 200, 0, 0, 80))
                    .build();
            spanResourceClient.updateSpan(spanId, update, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxSpend(spanId, ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().model()).isEqualTo("claude-opus-4-8");
                assertThat(row.get().uInput()).isEqualTo(200L);
                assertThat(row.get().uOutput()).isEqualTo(80L);
                // no real start_time on update -> derived from the span's UUIDv7
                assertThat(row.get().startMs()).isEqualTo(RetentionUtils.extractInstant(spanId).toEpochMilli());
            });
        }

        @Test
        @DisplayName("a cipx span updated with new cipx merges to a single row")
        void spanCipxUpdateMergesToSingleRow() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(spanCipxMetadata("model-a", 100, 0, 0, 10))
                    .build();
            var spanId = spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(
                    () -> assertThat(getCipxSpend(spanId, ws.workspaceId())).isPresent());

            var update = SpanUpdate.builder()
                    .projectName(projectName)
                    .traceId(span.traceId())
                    .parentSpanId(span.parentSpanId())
                    .metadata(spanCipxMetadata("model-b", 300, 0, 0, 90))
                    .build();
            spanResourceClient.updateSpan(spanId, update, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxSpend(spanId, ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().model()).isEqualTo("model-b");
                assertThat(row.get().uInput()).isEqualTo(300L);
                assertThat(row.get().uOutput()).isEqualTo(90L);
            });

            assertThat(countCipxSpend(spanId, ws.workspaceId())).isEqualTo(1L);
        }

        @Test
        @DisplayName("cipx appearing on a batch span update lands a row for every span, project resolved server-side")
        void spanCipxBatchUpdateLandsForEverySpan() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            // Three plain spans sharing a trace; none lands on create (no cipx call).
            var span1 = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(NON_CIPX_METADATA)
                    .build();
            UUID traceId = span1.traceId();
            var span2 = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(traceId)
                    .metadata(NON_CIPX_METADATA)
                    .build();
            var span3 = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .traceId(traceId)
                    .metadata(NON_CIPX_METADATA)
                    .build();
            spanResourceClient.batchCreateSpans(List.of(span1, span2, span3), ws.apiKey(), ws.workspaceName());

            // A batch update carries no project id (bulkUpdate matches by id + workspace only), so the listener
            // must resolve each span's project from the persisted rows — otherwise nothing would land.
            var update = SpanUpdate.builder()
                    .traceId(traceId)
                    .metadata(spanCipxMetadata("claude-haiku-4-5", 150, 10, 2, 60))
                    .build();
            var batch = SpanBatchUpdate.builder()
                    .ids(Set.of(span1.id(), span2.id(), span3.id()))
                    .update(update)
                    .build();
            spanResourceClient.batchUpdateSpans(batch, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                for (var span : List.of(span1, span2, span3)) {
                    var row = getCipxSpend(span.id(), ws.workspaceId());
                    assertThat(row).as("cipx_spend row for span '%s'", span.id()).isPresent();
                    assertThat(row.get().model()).isEqualTo("claude-haiku-4-5");
                    assertThat(row.get().uInput()).isEqualTo(150L);
                    assertThat(row.get().uCacheRead()).isEqualTo(10L);
                    assertThat(row.get().uCacheCreation()).isEqualTo(2L);
                    assertThat(row.get().uOutput()).isEqualTo(60L);
                    // project_id was not on the update; it was resolved from the persisted span.
                    assertThat(row.get().projectId()).isNotBlank();
                    // identity_context block dropped in Java; the two real blocks remain, in order.
                    assertThat(row.get().blockCount()).isEqualTo(2L);
                    assertThat(row.get().blockCategories()).isEqualTo("user_prompt,tool_call");
                }
            });
        }
    }

    @Nested
    @DisplayName("cipx_trace_identity ingestion")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Identity {

        @Test
        @DisplayName("trace created with a cipx identity lands in cipx_trace_identity; a non-cipx sibling does not")
        void traceCreatedWithIdentityLands() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();
            String userUuid = UUID.randomUUID().toString();

            var cipxTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(
                            traceCipxMetadata(userUuid, "dev@acme.com", "Dev User", "git@github.com:acme/repo.git", 3))
                    .build();
            var plainTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(NON_CIPX_METADATA)
                    .build();

            traceResourceClient.batchCreateTraces(List.of(cipxTrace, plainTrace), ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxIdentity(cipxTrace.id(), ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().userUuid()).isEqualTo(userUuid);
                assertThat(row.get().userEmail()).isEqualTo("dev@acme.com");
                assertThat(row.get().userDisplayName()).isEqualTo("Dev User");
                assertThat(row.get().repository()).isEqualTo("git@github.com:acme/repo.git");
                assertThat(row.get().schemaVersion()).isEqualTo(3);
                assertThat(row.get().projectId()).isNotBlank();
                assertThat(row.get().startMs()).isEqualTo(cipxTrace.startTime().toEpochMilli());
            });

            assertThat(getCipxIdentity(plainTrace.id(), ws.workspaceId())).isEmpty();
        }

        @Test
        @DisplayName("a cipx trace updated with new identity merges to a single row")
        void traceIdentityUpdateMergesToSingleRow() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(UUID.randomUUID().toString(), "old@acme.com", "Old", "repo-a", 1))
                    .build();
            var traceId = traceResourceClient.createTrace(trace, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(
                    () -> assertThat(getCipxIdentity(traceId, ws.workspaceId())).isPresent());

            String newUuid = UUID.randomUUID().toString();
            var update = TraceUpdate.builder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(newUuid, "new@acme.com", "New", "repo-b", 2))
                    .build();
            traceResourceClient.updateTrace(traceId, update, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxIdentity(traceId, ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().userUuid()).isEqualTo(newUuid);
                assertThat(row.get().userEmail()).isEqualTo("new@acme.com");
                assertThat(row.get().schemaVersion()).isEqualTo(2);
                // update path derives start_time from the trace's UUIDv7, which wins the merge
                assertThat(row.get().startMs()).isEqualTo(RetentionUtils.extractInstant(traceId).toEpochMilli());
            });

            assertThat(countCipxIdentity(traceId, ws.workspaceId())).isEqualTo(1L);
        }
    }

    private WorkspaceContext newWorkspace() {
        String apiKey = UUID.randomUUID().toString();
        String workspaceName = "test-workspace-" + UUID.randomUUID();
        String workspaceId = UUID.randomUUID().toString();
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
        return new WorkspaceContext(apiKey, workspaceName, workspaceId);
    }

    private static JsonNode spanCipxMetadata(String model, long input, long cacheRead, long cacheCreation,
            long output) {
        return JsonUtils.getJsonNodeFromString(
                """
                        {
                          "cipx": {
                            "call": {
                              "model": "%s",
                              "usage": {
                                "input_tokens": %d,
                                "cache_read_input_tokens": %d,
                                "cache_creation_input_tokens": %d,
                                "output_tokens": %d
                              }
                            },
                            "blocks": [
                              {"category":"user_prompt","side":"input","cache_status":"none","parent_category":"conversation","chars":120,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"identity_context","side":"input","cache_status":"none","parent_category":"identity_context","chars":50,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"tool_call","side":"output","cache_status":"none","parent_category":"assistant","chars":30,"tool_name":"search","tool_server":"srv","tool_use_id":"tu1","resource":"res","kind":"tool"}
                            ]
                          }
                        }
                        """
                        .formatted(model, input, cacheRead, cacheCreation, output));
    }

    private static JsonNode traceCipxMetadata(String userUuid, String email, String displayName, String repository,
            int schemaVersion) {
        return JsonUtils.getJsonNodeFromString("""
                {
                  "cipx": {
                    "session": {
                      "schema_version": %d,
                      "repository": {"remote": "%s"},
                      "identity": {
                        "user_uuid": "%s",
                        "email": "%s",
                        "display_name": "%s"
                      }
                    }
                  }
                }
                """.formatted(schemaVersion, repository, userUuid, email, displayName));
    }

    private Optional<CipxSpendRow> getCipxSpend(UUID spanId, String workspaceId) {
        String sql = """
                SELECT
                    project_id AS project_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    model AS model,
                    u_input, u_cache_read, u_cache_creation, u_output,
                    toInt64(length(blocks)) AS block_count,
                    arrayStringConcat(arrayMap(x -> x.1, blocks), ',') AS block_categories
                FROM cipx_spend FINAL
                WHERE workspace_id = :workspace_id AND span_id = :span_id
                """;
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("span_id", spanId.toString());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> new CipxSpendRow(
                            row.get("project_id", String.class),
                            row.get("start_ms", Long.class),
                            row.get("model", String.class),
                            row.get("u_input", Long.class),
                            row.get("u_cache_read", Long.class),
                            row.get("u_cache_creation", Long.class),
                            row.get("u_output", Long.class),
                            row.get("block_count", Long.class),
                            row.get("block_categories", String.class)))));
        }).blockOptional();
    }

    private long countCipxSpend(UUID spanId, String workspaceId) {
        String sql = "SELECT toInt64(count()) AS c FROM cipx_spend FINAL "
                + "WHERE workspace_id = :workspace_id AND span_id = :span_id";
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("span_id", spanId.toString());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("c", Long.class))));
        }).block();
    }

    private Optional<CipxIdentityRow> getCipxIdentity(UUID traceId, String workspaceId) {
        String sql = """
                SELECT
                    project_id AS project_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    user_uuid, user_email, user_display_name, repository, schema_version
                FROM cipx_trace_identity FINAL
                WHERE workspace_id = :workspace_id AND trace_id = :trace_id
                """;
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("trace_id", traceId.toString());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> new CipxIdentityRow(
                            row.get("project_id", String.class),
                            row.get("start_ms", Long.class),
                            row.get("user_uuid", String.class),
                            row.get("user_email", String.class),
                            row.get("user_display_name", String.class),
                            row.get("repository", String.class),
                            row.get("schema_version", Integer.class)))));
        }).blockOptional();
    }

    private long countCipxIdentity(UUID traceId, String workspaceId) {
        String sql = "SELECT toInt64(count()) AS c FROM cipx_trace_identity FINAL "
                + "WHERE workspace_id = :workspace_id AND trace_id = :trace_id";
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("trace_id", traceId.toString());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("c", Long.class))));
        }).block();
    }

    private record WorkspaceContext(String apiKey, String workspaceName, String workspaceId) {
    }

    private record CipxSpendRow(String projectId, Long startMs, String model, Long uInput, Long uCacheRead,
            Long uCacheCreation, Long uOutput, Long blockCount, String blockCategories) {
    }

    private record CipxIdentityRow(String projectId, Long startMs, String userUuid, String userEmail,
            String userDisplayName, String repository, Integer schemaVersion) {
    }
}
