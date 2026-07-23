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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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
                    .metadata(spanCipxMetadata("claude-sonnet-4-6", 100, 20, 5, 2, 3, 40))
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
                assertThat(row.get().uCacheCreation5m()).isEqualTo(2L);
                assertThat(row.get().uCacheCreation1h()).isEqualTo(3L);
                assertThat(row.get().uOutput()).isEqualTo(40L);
                assertThat(row.get().projectId()).isNotBlank();
                assertThat(row.get().startMs()).isEqualTo(cipxSpan.startTime().toEpochMilli());
                // config knobs (thinking level + settings) parsed from cipx.call.config
                assertThat(row.get().effort()).isEqualTo("high");
                assertThat(row.get().thinkingType()).isEqualTo("adaptive");
                assertThat(row.get().maxTokens()).isEqualTo(64000L);
                assertThat(row.get().contextManagement()).isEqualTo("clear_thinking_20251015");
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
                    .metadata(spanCipxMetadata("claude-sonnet-4-6", 100, 20, 5, 2, 3, 40))
                    .build();
            spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var rows = getCipxBlocks(span.id(), ws.workspaceId());
                assertThat(rows).hasSize(7);

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
                assertThat(memory.contentSha256()).isEqualTo("a1b2c3"); // block sha256 persisted verbatim

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

                // idx 5: agent_overhead (harness-injected user-role text) folds into the
                // static_overhead lane, so it never counts as a user prompt (OPIK-7457).
                var agentOverhead = rows.get(4);
                assertThat(agentOverhead.blockIdx()).isEqualTo(5);
                assertThat(agentOverhead.category()).isEqualTo("agent_overhead");
                assertThat(agentOverhead.lane()).isEqualTo("static_overhead");
                assertThat(agentOverhead.bdLane()).isEqualTo("static_overhead");
                assertThat(agentOverhead.label()).isEqualTo("agent_overhead");
                assertThat(agentOverhead.isDefinition()).isZero();

                // residuals: billed tiers with no blocks (input, cache_creation), deterministic idx.
                var residualInput = rows.get(5);
                assertThat(residualInput.blockIdx()).isEqualTo(65531);
                assertThat(residualInput.src()).isEqualTo("r");
                assertThat(residualInput.category()).isEmpty();
                assertThat(residualInput.tier()).isEqualTo("input");
                assertThat(residualInput.lane()).isEqualTo("unattributed");
                assertThat(residualInput.bdLane()).isEmpty();
                assertThat(residualInput.label()).isEmpty();
                assertThat(residualInput.alloc()).isCloseTo(100.0, within(1e-9));

                // cache_creation residual inherits the span's TTL: the usage split has 1h > 0, so the
                // write tier is labeled cache_creation_1h (OPIK-7392).
                var residualCacheCreation = rows.get(6);
                assertThat(residualCacheCreation.blockIdx()).isEqualTo(65533);
                assertThat(residualCacheCreation.src()).isEqualTo("r");
                assertThat(residualCacheCreation.tier()).isEqualTo("cache_creation_1h");
                assertThat(residualCacheCreation.alloc()).isCloseTo(5.0, within(1e-9));

                // content_sha256 is persisted per row in order: only the memory block
                // carried a sha256 in the fixture, so every other row must read "" —
                // guards against a dropped or misordered hash across the batch.
                assertThat(rows).filteredOn(row -> !row.contentSha256().isEmpty())
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.category()).isEqualTo("memory");
                            assertThat(row.contentSha256()).isEqualTo("a1b2c3");
                        });

                // model and start_time ride on every block row.
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.model()).isEqualTo("claude-sonnet-4-6");
                    assertThat(row.startMs()).isEqualTo(span.startTime().toEpochMilli());
                });
            });
        }

        @DisplayName("write blocks inherit the span's cache TTL (1h vs 5m)")
        @ParameterizedTest
        @CsvSource({
                "0, 50, cache_creation_1h", // usage split: 1h -> whole span is 1h
                "50, 0, cache_creation_5m", // usage split: 5m only -> whole span is 5m
                "0, 0, cache_creation_1h", // no split reported -> fall back to 1h (lump forced to 50)
        })
        void writeBlocksInheritSpanCacheTtl(long cacheCreation5m, long cacheCreation1h, String expectedTier) {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(spanCipxWriteBlockMetadata("claude-sonnet-4-6", cacheCreation5m, cacheCreation1h))
                    .build();
            spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var rows = getCipxBlocks(span.id(), ws.workspaceId());
                assertThat(rows).hasSize(1);
                var writeBlock = rows.getFirst();
                assertThat(writeBlock.cacheStatus()).isEqualTo("write");
                assertThat(writeBlock.tier()).isEqualTo(expectedTier);
                assertThat(writeBlock.alloc()).isCloseTo(50.0, within(1e-9)); // sole write block absorbs the lump
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
                    .metadata(traceCipxMetadata(userUuid, email, "Dev User", "git@github.com:acme/repo.git", "codex",
                            3))
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
                assertThat(row.get().harness()).isEqualTo("codex");
                assertThat(row.get().schemaVersion()).isEqualTo(3);
                assertThat(row.get().projectId()).isNotBlank();
                assertThat(row.get().startMs()).isEqualTo(cipxTrace.startTime().toEpochMilli());
                // payment-plan fields parsed from cipx.session.identity
                assertThat(row.get().billingMode()).isEqualTo("subscription");
                assertThat(row.get().plan()).isEqualTo("max");
                assertThat(row.get().planUsageStatus()).isEqualTo("within");
                // git info + per-turn committed delta parsed from cipx.session.repository (OPIK-7345)
                assertThat(row.get().branch()).isEqualTo("main");
                assertThat(row.get().headShaStart()).isEqualTo("aaaa1111");
                assertThat(row.get().headShaEnd()).isEqualTo("bbbb2222");
                assertThat(row.get().dirty()).isTrue();
                assertThat(row.get().commitsInTrace()).isEqualTo(2);
                assertThat(row.get().filesAdded()).isEqualTo(3);
                assertThat(row.get().filesDeleted()).isEqualTo(1);
                assertThat(row.get().linesAdded()).isEqualTo(40);
                assertThat(row.get().linesDeleted()).isEqualTo(5);

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
                    .metadata(traceCipxMetadata(userUuid, email, "Dev User", "repo-a", "claude_code", 2))
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
                    .metadata(traceCipxMetadata(userUuid, oldEmail, "Old", "repo-a", "claude_code", 1))
                    .build();
            var traceId = traceResourceClient.createTrace(trace, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(
                    () -> assertThat(getCipxIdentity(traceId, ws.workspaceId())).isPresent());

            // user_uuid is part of the sorting key, so only a same-user update collapses to one row.
            var update = TraceUpdate.builder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadata(userUuid, newEmail, "New", "repo-b", "claude_code", 2))
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
            long cacheCreation5m, long cacheCreation1h, long output) {
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
                                "cache_creation": {
                                  "ephemeral_5m_input_tokens": %d,
                                  "ephemeral_1h_input_tokens": %d
                                },
                                "output_tokens": %d
                              },
                              "config": {
                                "effort": "high",
                                "thinking_type": "adaptive",
                                "max_tokens": 64000,
                                "context_management": "clear_thinking_20251015"
                              }
                            },
                            "blocks": [
                              {"category":"memory","side":"input","cache_status":"read","parent_category":"context","chars":120,"tool_name":"","tool_server":"","tool_use_id":"","resource":"CLAUDE.md","kind":"text","sha256":"a1b2c3"},
                              {"category":"identity_context","side":"input","cache_status":"none","parent_category":"identity_context","chars":50,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"skills_loaded","side":"input","cache_status":"read","parent_category":"context","chars":360,"tool_name":"","tool_server":"","tool_use_id":"","resource":"dataviz","kind":"text"},
                              {"category":"mcp_tool_calls","side":"output","cache_status":"none","parent_category":"assistant","chars":30,"tool_name":"search","tool_server":"srv","tool_use_id":"tu1","resource":"res","kind":"tool"},
                              {"category":"tool_io","side":"input","cache_status":"unknown","parent_category":"context","chars":75,"tool_name":"Bash","tool_server":"","tool_use_id":"tu2","resource":"","kind":"tool"},
                              {"category":"agent_overhead","side":"input","cache_status":"unknown","parent_category":"context","chars":40,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"}
                            ]
                          }
                        }
                        """
                        .formatted(model, input, cacheRead, cacheCreation, cacheCreation5m, cacheCreation1h, output));
    }

    // A cipx span with a single write block (side=input, cache_status=write), so the whole cache-creation
    // lump lands on it. The usage split (5m/1h) drives which TTL tier the write block inherits.
    private static JsonNode spanCipxWriteBlockMetadata(String model, long cacheCreation5m, long cacheCreation1h) {
        long lump = cacheCreation5m + cacheCreation1h;
        return JsonUtils.getJsonNodeFromString(
                """
                        {
                          "cipx": {
                            "call": {
                              "model": "%s",
                              "usage": {
                                "input_tokens": 0,
                                "cache_read_input_tokens": 0,
                                "cache_creation_input_tokens": %d,
                                "cache_creation": {
                                  "ephemeral_5m_input_tokens": %d,
                                  "ephemeral_1h_input_tokens": %d
                                },
                                "output_tokens": 0
                              }
                            },
                            "blocks": [
                              {"category":"system_prompt","side":"input","cache_status":"write","parent_category":"context","chars":200,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"}
                            ]
                          }
                        }
                        """
                        .formatted(model, lump == 0 ? 50 : lump, cacheCreation5m, cacheCreation1h));
    }

    private static JsonNode traceCipxMetadata(String userUuid, String email, String displayName, String repository,
            String harness, int schemaVersion) {
        return JsonUtils.getJsonNodeFromString("""
                {
                  "cipx": {
                    "session": {
                      "schema_version": %d,
                      "session_id": "cc-session-abc",
                      "harness": "%s",
                      "repository": {
                        "remote": "%s",
                        "branch": "main",
                        "head_sha": "aaaa1111",
                        "head_sha_end": "bbbb2222",
                        "dirty": true,
                        "commits_in_trace": 2,
                        "files_added": 3,
                        "files_deleted": 1,
                        "lines_added": 40,
                        "lines_deleted": 5
                      },
                      "identity": {
                        "user_uuid": "%s",
                        "email": "%s",
                        "display_name": "%s",
                        "billing_mode": "subscription",
                        "plan": "max",
                        "plan_usage_status": "within"
                      }
                    }
                  }
                }
                """.formatted(schemaVersion, harness, repository, userUuid, email, displayName));
    }

    private Optional<CipxSpendRow> getCipxSpend(UUID spanId, String workspaceId) {
        String sql = """
                SELECT
                    project_id AS project_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    model AS model,
                    u_input, u_cache_read, u_cache_creation, u_cache_creation_5m, u_cache_creation_1h, u_output,
                    effort, thinking_type, max_tokens, context_management
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
                            row.get("u_cache_creation_5m", Long.class),
                            row.get("u_cache_creation_1h", Long.class),
                            row.get("u_output", Long.class),
                            row.get("effort", String.class),
                            row.get("thinking_type", String.class),
                            row.get("max_tokens", Long.class),
                            row.get("context_management", String.class)))));
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
                    content_sha256,
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
                            row.get("content_sha256", String.class),
                            row.get("start_ms", Long.class))))
                    .collectList();
        }).block();
    }

    private Optional<CipxIdentityRow> getCipxIdentity(UUID traceId, String workspaceId) {
        String sql = """
                SELECT
                    project_id AS project_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    user_uuid, user_email, user_display_name, repository, session_id, harness, schema_version,
                    billing_mode, plan, plan_usage_status,
                    branch, head_sha_start, head_sha_end, dirty, commits_in_trace,
                    files_added, files_deleted, lines_added, lines_deleted
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
                            row.get("harness", String.class),
                            row.get("schema_version", Integer.class),
                            row.get("billing_mode", String.class),
                            row.get("plan", String.class),
                            row.get("plan_usage_status", String.class),
                            row.get("branch", String.class),
                            row.get("head_sha_start", String.class),
                            row.get("head_sha_end", String.class),
                            row.get("dirty", Boolean.class),
                            row.get("commits_in_trace", Integer.class),
                            row.get("files_added", Integer.class),
                            row.get("files_deleted", Integer.class),
                            row.get("lines_added", Integer.class),
                            row.get("lines_deleted", Integer.class)))));
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
            Long uCacheCreation, Long uCacheCreation5m, Long uCacheCreation1h, Long uOutput, String effort,
            String thinkingType, Long maxTokens, String contextManagement) {
    }

    private record CipxBlockRow(Integer blockIdx, String src, String category, String tier, String lane,
            String bdLane, String label, Integer isDefinition, Double alloc, String model, String side,
            String cacheStatus, String parentCategory, Long chars, String toolName, String toolServer,
            String toolUseId, String resource, String kind, String contentSha256, Long startMs) {
    }

    private record CipxIdentityRow(String projectId, Long startMs, String userUuid, String userEmail,
            String userDisplayName, String repository, String sessionId, String harness, Integer schemaVersion,
            String billingMode, String plan, String planUsageStatus,
            String branch, String headShaStart, String headShaEnd, Boolean dirty, Integer commitsInTrace,
            Integer filesAdded, Integer filesDeleted, Integer linesAdded, Integer linesDeleted) {
    }
}
