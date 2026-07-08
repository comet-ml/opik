package com.comet.opik.api.resources.v1.events;

import com.comet.opik.api.Span;
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
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.READ_ONLY;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
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
    private TransactionTemplate mySqlTemplate;
    private SpanResourceClient spanResourceClient;
    private TraceResourceClient traceResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate,
            TransactionTemplate mySqlTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.clickHouseTemplate = clickHouseTemplate;
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);

        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
    }

    @Nested
    @DisplayName("cipx_spends + cipx_spend_blocks ingestion")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Spend {

        @Test
        @DisplayName("span created with a cipx call lands in cipx_spends; a non-cipx sibling does not")
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
            });

            // The non-cipx span shared the same create event, so once the cipx row is present the
            // listener has already decided this one: it must not have produced a row.
            assertThat(getCipxSpend(plainSpan.id(), ws.workspaceId())).isEmpty();
            assertThat(getCipxBlocks(plainSpan.id(), ws.workspaceId())).isEmpty();
        }

        @Test
        @DisplayName("blocks land with derived allocation, residual rows, and identity_context dropped")
        void blocksLandWithDerivedAllocationAndResiduals() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            // usage: input=100 (no fresh blocks -> residual), cache_read=20 (split across the two read
            // blocks by chars: 120/480 and 360/480), cache_creation=5 (no write blocks -> residual),
            // output=40 (one output block absorbs it all).
            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(spanCipxMetadata("claude-sonnet-4-6", 100, 20, 5, 40))
                    .build();
            spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var rows = getCipxBlocks(span.id(), ws.workspaceId());
                assertThat(rows).hasSize(6);

                // idx 0: memory block, cache_read tier; identity_context at raw idx 1 is dropped but
                // does not shift the following indexes.
                var memory = rows.get(0);
                assertThat(memory.blockIdx()).isZero();
                assertThat(memory.src()).isEqualTo("a");
                assertThat(memory.category()).isEqualTo("memory");
                assertThat(memory.tier()).isEqualTo("cache_read");
                assertThat(memory.lane()).isEqualTo("memory");
                assertThat(memory.bdLane()).isEqualTo("memory");
                assertThat(memory.label()).isEqualTo("CLAUDE.md");
                assertThat(memory.isDefinition()).isEqualTo(1);
                assertThat(memory.alloc()).isCloseTo(5.0, within(1e-9)); // 120 * 20 / 480

                var skills = rows.get(1);
                assertThat(skills.blockIdx()).isEqualTo(2);
                assertThat(skills.category()).isEqualTo("skills_loaded");
                assertThat(skills.tier()).isEqualTo("cache_read");
                assertThat(skills.lane()).isEqualTo("skills");
                assertThat(skills.label()).isEqualTo("dataviz");
                assertThat(skills.isDefinition()).isZero();
                assertThat(skills.alloc()).isCloseTo(15.0, within(1e-9)); // 360 * 20 / 480

                var mcpCall = rows.get(2);
                assertThat(mcpCall.blockIdx()).isEqualTo(3);
                assertThat(mcpCall.category()).isEqualTo("mcp_tool_calls");
                assertThat(mcpCall.tier()).isEqualTo("output");
                assertThat(mcpCall.lane()).isEqualTo("mcp_tool_calls");
                assertThat(mcpCall.label()).isEqualTo("srv");
                assertThat(mcpCall.alloc()).isCloseTo(40.0, within(1e-9)); // 30 * 40 / 30
                // raw passthrough columns, pinned on the block with every field populated.
                assertThat(mcpCall.side()).isEqualTo("output");
                assertThat(mcpCall.cacheStatus()).isEqualTo("none");
                assertThat(mcpCall.parentCategory()).isEqualTo("assistant");
                assertThat(mcpCall.chars()).isEqualTo(30L);
                assertThat(mcpCall.toolName()).isEqualTo("search");
                assertThat(mcpCall.toolServer()).isEqualTo("srv");
                assertThat(mcpCall.toolUseId()).isEqualTo("tu1");
                assertThat(mcpCall.resource()).isEqualTo("res");
                assertThat(mcpCall.kind()).isEqualTo("tool");

                // idx 4: (side, cache_status) matches no tier -> still lands, counted by breakdowns
                // with zero allocation.
                var noTier = rows.get(3);
                assertThat(noTier.blockIdx()).isEqualTo(4);
                assertThat(noTier.src()).isEqualTo("a");
                assertThat(noTier.category()).isEqualTo("tool_io");
                assertThat(noTier.tier()).isEmpty();
                assertThat(noTier.lane()).isEqualTo("built_in_tools");
                assertThat(noTier.bdLane()).isEqualTo("built_in_tools");
                assertThat(noTier.label()).isEqualTo("Bash");
                assertThat(noTier.alloc()).isZero();

                // residuals: billed tiers with no blocks (input, cache_creation), deterministic idx.
                var residualInput = rows.get(4);
                assertThat(residualInput.blockIdx()).isEqualTo(65531);
                assertThat(residualInput.src()).isEqualTo("r");
                assertThat(residualInput.category()).isEmpty();
                assertThat(residualInput.tier()).isEqualTo("input");
                assertThat(residualInput.lane()).isEqualTo("unattributed");
                assertThat(residualInput.bdLane()).isEmpty();
                assertThat(residualInput.label()).isEmpty();
                assertThat(residualInput.alloc()).isCloseTo(100.0, within(1e-9));

                var residualCacheCreation = rows.get(5);
                assertThat(residualCacheCreation.blockIdx()).isEqualTo(65533);
                assertThat(residualCacheCreation.src()).isEqualTo("r");
                assertThat(residualCacheCreation.tier()).isEqualTo("cache_creation");
                assertThat(residualCacheCreation.alloc()).isCloseTo(5.0, within(1e-9));

                // model and start_time ride on every block row.
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.model()).isEqualTo("claude-sonnet-4-6");
                    assertThat(row.startMs()).isEqualTo(span.startTime().toEpochMilli());
                });
            });
        }
    }

    @Nested
    @DisplayName("cipx_trace_identities + cipx_user_mappings ingestion")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Identity {

        @Test
        @DisplayName("trace created with a cipx identity lands in cipx_trace_identities and cipx_user_mappings")
        void traceCreatedWithIdentityLands() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();
            String userUuid = UUID.randomUUID().toString();
            String email = "dev-" + UUID.randomUUID() + "@acme.com";

            var cipxTrace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(userUuid, email, "Dev User", "git@github.com:acme/repo.git", 3))
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
                assertThat(row.get().userEmail()).isEqualTo(email);
                assertThat(row.get().userDisplayName()).isEqualTo("Dev User");
                assertThat(row.get().repository()).isEqualTo("git@github.com:acme/repo.git");
                assertThat(row.get().sessionId()).isEqualTo("cc-session-abc");
                assertThat(row.get().schemaVersion()).isEqualTo(3);
                assertThat(row.get().projectId()).isNotBlank();
                assertThat(row.get().startMs()).isEqualTo(cipxTrace.startTime().toEpochMilli());

                assertThat(getUserMappings(email)).containsExactly(userUuid);
            });

            assertThat(getCipxIdentity(plainTrace.id(), ws.workspaceId())).isEmpty();
        }

        @Test
        @DisplayName("cipx identity appearing only on a trace update inserts the row and the mapping")
        void traceIdentityAppearingOnUpdateInserts() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();
            String userUuid = UUID.randomUUID().toString();
            String email = "dev-" + UUID.randomUUID() + "@acme.com";

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(NON_CIPX_METADATA)
                    .build();
            var traceId = traceResourceClient.createTrace(trace, ws.apiKey(), ws.workspaceName());

            var update = TraceUpdate.builder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(userUuid, email, "Dev User", "repo-a", 2))
                    .build();
            traceResourceClient.updateTrace(traceId, update, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxIdentity(traceId, ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().userUuid()).isEqualTo(userUuid);
                assertThat(row.get().userEmail()).isEqualTo(email);
                assertThat(row.get().schemaVersion()).isEqualTo(2);
                // start_time is resolved from the stored trace, not derived from the UUIDv7
                assertThat(row.get().startMs()).isEqualTo(trace.startTime().toEpochMilli());

                assertThat(getUserMappings(email)).containsExactly(userUuid);
            });
        }

        @Test
        @DisplayName("a cipx trace updated for the same user merges to a single row and maps the new email")
        void traceIdentityUpdateMergesToSingleRow() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();
            String userUuid = UUID.randomUUID().toString();
            String oldEmail = "old-" + UUID.randomUUID() + "@acme.com";
            String newEmail = "new-" + UUID.randomUUID() + "@acme.com";

            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(userUuid, oldEmail, "Old", "repo-a", 1))
                    .build();
            var traceId = traceResourceClient.createTrace(trace, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(
                    () -> assertThat(getCipxIdentity(traceId, ws.workspaceId())).isPresent());

            // user_uuid is part of the sorting key, so only a same-user update collapses to one row.
            var update = TraceUpdate.builder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(userUuid, newEmail, "New", "repo-b", 2))
                    .build();
            traceResourceClient.updateTrace(traceId, update, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxIdentity(traceId, ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().userEmail()).isEqualTo(newEmail);
                assertThat(row.get().schemaVersion()).isEqualTo(2);

                assertThat(countCipxIdentity(traceId, ws.workspaceId())).isEqualTo(1L);
                // mappings are append-only: both emails resolve to the user
                assertThat(getUserMappings(oldEmail)).containsExactly(userUuid);
                assertThat(getUserMappings(newEmail)).containsExactly(userUuid);
            });
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
                              {"category":"memory","side":"input","cache_status":"read","parent_category":"context","chars":120,"tool_name":"","tool_server":"","tool_use_id":"","resource":"CLAUDE.md","kind":"text"},
                              {"category":"identity_context","side":"input","cache_status":"none","parent_category":"identity_context","chars":50,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"skills_loaded","side":"input","cache_status":"read","parent_category":"context","chars":360,"tool_name":"","tool_server":"","tool_use_id":"","resource":"dataviz","kind":"text"},
                              {"category":"mcp_tool_calls","side":"output","cache_status":"none","parent_category":"assistant","chars":30,"tool_name":"search","tool_server":"srv","tool_use_id":"tu1","resource":"res","kind":"tool"},
                              {"category":"tool_io","side":"input","cache_status":"unknown","parent_category":"context","chars":75,"tool_name":"Bash","tool_server":"","tool_use_id":"tu2","resource":"","kind":"tool"}
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
                      "session_id": "cc-session-abc",
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
                    u_input, u_cache_read, u_cache_creation, u_output
                FROM cipx_spends FINAL
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
                            row.get("u_output", Long.class)))));
        }).blockOptional();
    }

    private List<CipxBlockRow> getCipxBlocks(UUID spanId, String workspaceId) {
        String sql = """
                SELECT
                    toInt32(block_idx) AS block_idx,
                    src, category, tier, lane, bd_lane, label,
                    toInt32(is_definition) AS is_definition,
                    alloc,
                    model,
                    side, cache_status, parent_category, chars,
                    tool_name, tool_server, tool_use_id, resource, kind,
                    toUnixTimestamp64Milli(start_time) AS start_ms
                FROM cipx_spend_blocks FINAL
                WHERE workspace_id = :workspace_id AND span_id = :span_id
                ORDER BY block_idx
                """;
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("span_id", spanId.toString());
            return Flux.from(statement.execute())
                    .flatMap(result -> result.map((row, meta) -> new CipxBlockRow(
                            row.get("block_idx", Integer.class),
                            row.get("src", String.class),
                            row.get("category", String.class),
                            row.get("tier", String.class),
                            row.get("lane", String.class),
                            row.get("bd_lane", String.class),
                            row.get("label", String.class),
                            row.get("is_definition", Integer.class),
                            row.get("alloc", Double.class),
                            row.get("model", String.class),
                            row.get("side", String.class),
                            row.get("cache_status", String.class),
                            row.get("parent_category", String.class),
                            row.get("chars", Long.class),
                            row.get("tool_name", String.class),
                            row.get("tool_server", String.class),
                            row.get("tool_use_id", String.class),
                            row.get("resource", String.class),
                            row.get("kind", String.class),
                            row.get("start_ms", Long.class))))
                    .collectList();
        }).block();
    }

    private Optional<CipxIdentityRow> getCipxIdentity(UUID traceId, String workspaceId) {
        String sql = """
                SELECT
                    project_id AS project_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    user_uuid, user_email, user_display_name, repository, session_id, schema_version
                FROM cipx_trace_identities FINAL
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
                            row.get("session_id", String.class),
                            row.get("schema_version", Integer.class)))));
        }).blockOptional();
    }

    private long countCipxIdentity(UUID traceId, String workspaceId) {
        String sql = "SELECT toInt64(count()) AS c FROM cipx_trace_identities FINAL "
                + "WHERE workspace_id = :workspace_id AND trace_id = :trace_id";
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("trace_id", traceId.toString());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> row.get("c", Long.class))));
        }).block();
    }

    private List<String> getUserMappings(String email) {
        return mySqlTemplate.inTransaction(READ_ONLY, handle -> handle
                .createQuery("SELECT user_uuid FROM cipx_user_mappings WHERE user_email = :email")
                .bind("email", email)
                .mapTo(String.class)
                .list());
    }

    private record WorkspaceContext(String apiKey, String workspaceName, String workspaceId) {
    }

    private record CipxSpendRow(String projectId, Long startMs, String model, Long uInput, Long uCacheRead,
            Long uCacheCreation, Long uOutput) {
    }

    private record CipxBlockRow(Integer blockIdx, String src, String category, String tier, String lane,
            String bdLane, String label, Integer isDefinition, Double alloc, String model, String side,
            String cacheStatus, String parentCategory, Long chars, String toolName, String toolServer,
            String toolUseId, String resource, String kind, Long startMs) {
    }

    private record CipxIdentityRow(String projectId, Long startMs, String userUuid, String userEmail,
            String userDisplayName, String repository, String sessionId, Integer schemaVersion) {
    }
}
