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
import com.comet.opik.domain.CipxSpendDAO;
import com.comet.opik.domain.CipxTraceIdentityDAO;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.redis.testcontainers.RedisContainer;
import lombok.Builder;
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

import java.time.Instant;
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
    private CipxSpendDAO cipxSpendDAO;
    private CipxTraceIdentityDAO cipxTraceIdentityDAO;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate,
            TransactionTemplate mySqlTemplate, CipxSpendDAO cipxSpendDAO,
            CipxTraceIdentityDAO cipxTraceIdentityDAO) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.clickHouseTemplate = clickHouseTemplate;
        this.mySqlTemplate = mySqlTemplate;
        this.cipxSpendDAO = cipxSpendDAO;
        this.cipxTraceIdentityDAO = cipxTraceIdentityDAO;

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
                // speed: selects the rate table, so it must survive ingestion
                assertThat(row.get().speed()).isEqualTo("fast");
                // attribution fields: what caused the call, which agent ran it, which turn it
                // belongs to, and which Agent tool_use spawned it. Without these the spend tables
                // cannot separate subagent spend from the main agent's, nor name the agent.
                assertThat(row.get().trigger()).isEqualTo("subagent");
                assertThat(row.get().triggerDetail()).isEqualTo("code-reviewer");
                assertThat(row.get().turnKey()).isEqualTo("abc123turnkey");
                assertThat(row.get().parentToolUseId()).isEqualTo("toolu_parent_agent");
                // This subagent linked, so cipx ships no link_failure_reason at all and the column
                // must read '' — "nothing to report", which is the healthy state. cipx only stamps
                // a reason on a subagent whose parent it could NOT resolve.
                assertThat(row.get().linkFailureReason()).isEmpty();
            });

            // Carried on every block row too.
            var blocks = getCipxBlocks(cipxSpan.id(), ws.workspaceId());
            assertThat(blocks).isNotEmpty();
            assertThat(blocks).allSatisfy(block -> assertThat(block.speed()).isEqualTo("fast"));

            // The non-cipx span shared the same create event, so once the cipx row is present the
            // listener has already decided this one: it must not have produced a row.
            assertThat(getCipxSpend(plainSpan.id(), ws.workspaceId())).isEmpty();
            assertThat(getCipxBlocks(plainSpan.id(), ws.workspaceId())).isEmpty();
        }

        @Test
        @DisplayName("a call carrying no trigger fields ingests with empty attribution columns")
        void spanWithoutTriggerFieldsLandsWithEmptyAttribution() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            // systemToolsCipxMetadata carries no
            // trigger/turn_key/parent_tool_use_id — the shape every span
            // written before the proxy shipped them has. Ingestion must still
            // land the row and must leave the attribution columns empty
            // rather than substituting a default: "" means unknown, and a
            // guessed agent name would book real spend against an agent that
            // never ran. Scope, deliberately: this covers the half that is
            // ours — the DAO supplying "" for a field the metadata omits.
            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(systemToolsCipxMetadata("claude-sonnet-4-6", 200))
                    .build();
            spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxSpend(span.id(), ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().trigger()).isEmpty();
                assertThat(row.get().triggerDetail()).isEmpty();
                assertThat(row.get().turnKey()).isEmpty();
                assertThat(row.get().parentToolUseId()).isEmpty();
                assertThat(row.get().linkFailureReason()).isEmpty();
            });
        }

        @Test
        @DisplayName("a fail-closed subagent and a lost-dispatch subagent stay distinguishable")
        void unattributedSubagentsKeepTheirLinkFailureReason() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            // Both spans are subagent calls with NO parent_tool_use_id, so a per-agent rollup files
            // both under "(unattributed)". They are not the same problem: ambiguous_prompt is cipx
            // deliberately refusing to guess between two byte-identical peer dispatches (working as
            // designed, nothing to chase), while no_dispatch_captured is cipx losing a dispatch it
            // should have observed (a real defect). link_failure_reason is the only thing that keeps
            // them apart — without it a live incident and normal operation look identical.
            var failClosed = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(unattributedSubagentCipxMetadata("ambiguous_prompt"))
                    .build();
            var lostDispatch = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(unattributedSubagentCipxMetadata("no_dispatch_captured"))
                    .build();

            spanResourceClient.batchCreateSpans(List.of(failClosed, lostDispatch), ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var refused = getCipxSpend(failClosed.id(), ws.workspaceId());
                var lost = getCipxSpend(lostDispatch.id(), ws.workspaceId());
                assertThat(refused).isPresent();
                assertThat(lost).isPresent();
                // Same trigger, same empty parent — only the reason separates them.
                assertThat(refused.get().trigger()).isEqualTo("subagent");
                assertThat(lost.get().trigger()).isEqualTo("subagent");
                assertThat(refused.get().parentToolUseId()).isEmpty();
                assertThat(lost.get().parentToolUseId()).isEmpty();
                assertThat(refused.get().linkFailureReason()).isEqualTo("ambiguous_prompt");
                assertThat(lost.get().linkFailureReason()).isEqualTo("no_dispatch_captured");
            });
        }

        @Test
        @DisplayName("every positional bind lands in its own column, across a multi-row insert")
        void everyPositionalBindLandsInItsOwnColumn() {
            var ws = newWorkspace();

            // CipxSpendDAO binds by position and nothing checks the bind
            // order against the INSERT tuple at compile time. A mismatch does
            // not fail the insert — it writes each value into the
            // neighbouring column, which is invisible unless the columns hold
            // values that can be told apart. Two rows, deliberately: the bind
            // index accumulates across rows while workspace_id is bound once
            // at index 0 and its repeats dedup.
            var rowOne = sentinelRow(1);
            var rowTwo = sentinelRow(2);

            cipxSpendDAO.insert(List.of(rowOne, rowTwo), ws.workspaceId(), USER).block();

            assertEveryColumnHoldsItsOwnValue(ws.workspaceId(), rowOne);
            assertEveryColumnHoldsItsOwnValue(ws.workspaceId(), rowTwo);
        }

        @Test
        @DisplayName("a call carrying fields the DAO does not know still ingests")
        void unknownFieldsDoNotRejectTheRow() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            // cipx adds fields to metadata.cipx.call on its own release cadence and ships to laptops
            // independently of this service, so a newer proxy talking to an older backend is the
            // normal state, not an edge case. Ingestion must ignore what it does not recognize
            // rather than reject the row — dropping the row would lose the spend entirely, and spend
            // totals are the one thing that is correct today.
            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(unknownFieldsCipxMetadata("claude-sonnet-4-6"))
                    .build();
            spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxSpend(span.id(), ws.workspaceId());
                assertThat(row).isPresent();
                // Every known field still parsed correctly alongside the unknown ones.
                assertThat(row.get().model()).isEqualTo("claude-sonnet-4-6");
                assertThat(row.get().uInput()).isEqualTo(11L);
                assertThat(row.get().uCacheRead()).isEqualTo(22L);
                assertThat(row.get().uCacheCreation()).isEqualTo(33L);
                assertThat(row.get().uCacheCreation5m()).isEqualTo(44L);
                assertThat(row.get().uCacheCreation1h()).isEqualTo(55L);
                assertThat(row.get().uOutput()).isEqualTo(66L);
                assertThat(row.get().effort()).isEqualTo("high");
                assertThat(row.get().speed()).isEqualTo("fast");
                assertThat(row.get().trigger()).isEqualTo("subagent");
                assertThat(row.get().triggerDetail()).isEqualTo("Explore");
                assertThat(row.get().turnKey()).isEqualTo("unknown-fields-turnkey");
                assertThat(row.get().parentToolUseId()).isEqualTo("toolu_unknown_fields");
                // parent_unresolved is the one reason that appears on a call that DID link: the
                // spend is attributed, only the trace tree shape is wrong.
                assertThat(row.get().linkFailureReason()).isEqualTo("parent_unresolved");
                // The block writer sees the same metadata, so an unknown field on a block must not
                // drop the blocks either. Asserted inside the same await: the listener subscribes to
                // the spend insert and the block insert independently
                // (CostIntelligenceIngestionListener), so the spend row landing says nothing about
                // whether the blocks have landed.
                assertThat(getCipxBlocks(span.id(), ws.workspaceId())).isNotEmpty();
            });
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
                // Claude Code's `autoMemoryEnabled: false` removes only the auto-memory slice,
                // not the CLAUDE.md / rules files sharing this lane. Neither that setting nor
                // the savings lever pricing it lives here — see ai-cost-backend's auto_memory
                // policy; this repo's job is just to persist the distinction.
                assertThat(memory.subcategory()).isEqualTo("auto_memory");

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

                // subcategory is persisted per row in order: only the memory block carried one in
                // the fixture, so every other row -- residuals included -- must read "".
                // Guards against a dropped or misordered subcategory across the batch, and pins
                // that '' stays the "unknown" sentinel rather than leaking a real value.
                assertThat(rows).filteredOn(row -> !row.subcategory().isEmpty())
                        .singleElement()
                        .satisfies(row -> {
                            assertThat(row.category()).isEqualTo("memory");
                            assertThat(row.subcategory()).isEqualTo("auto_memory");
                        });

                // model and start_time ride on every block row.
                assertThat(rows).allSatisfy(row -> {
                    assertThat(row.model()).isEqualTo("claude-sonnet-4-6");
                    assertThat(row.startMs()).isEqualTo(span.startTime().toEpochMilli());
                });
            });
        }

        @Test
        @DisplayName("system_tools/system_tools_deferred land in built_in_tools; system_prompt/env_info stay in static_overhead")
        void systemToolsLandInBuiltInToolsLane() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();

            var span = factory.manufacturePojo(Span.class).toBuilder()
                    .projectName(projectName)
                    .metadata(systemToolsCipxMetadata("claude-sonnet-4-6", 200))
                    .build();
            spanResourceClient.createSpan(span, ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var rows = getCipxBlocks(span.id(), ws.workspaceId());
                assertThat(rows).hasSize(4);

                // schema block for a named tool: reclassified out of static_overhead, keyed by
                // tool_name instead of the bare category string (mirrors tool_io's own labeling).
                var bash = rows.getFirst();
                assertThat(bash.category()).isEqualTo("system_tools");
                assertThat(bash.lane()).isEqualTo("built_in_tools");
                assertThat(bash.bdLane()).isEqualTo("built_in_tools");
                assertThat(bash.label()).isEqualTo("Bash");
                assertThat(bash.isDefinition()).isEqualTo(1);

                // deferred-tools reminder: one multi-tool text block, no single tool_name.
                var deferred = rows.get(1);
                assertThat(deferred.category()).isEqualTo("system_tools_deferred");
                assertThat(deferred.lane()).isEqualTo("built_in_tools");
                assertThat(deferred.bdLane()).isEqualTo("built_in_tools");
                assertThat(deferred.label()).isEqualTo("(unattributed)");
                assertThat(deferred.isDefinition()).isEqualTo(1);

                // Regression guard: the other static_overhead categories (untouched by this
                // change, but sharing the same dispatch table) still map correctly.
                var systemPrompt = rows.get(2);
                assertThat(systemPrompt.category()).isEqualTo("system_prompt");
                assertThat(systemPrompt.lane()).isEqualTo("static_overhead");
                assertThat(systemPrompt.bdLane()).isEqualTo("static_overhead");
                assertThat(systemPrompt.label()).isEqualTo("system_prompt");
                assertThat(systemPrompt.isDefinition()).isEqualTo(1);

                var envInfo = rows.getLast();
                assertThat(envInfo.category()).isEqualTo("env_info");
                assertThat(envInfo.lane()).isEqualTo("static_overhead");
                assertThat(envInfo.bdLane()).isEqualTo("static_overhead");
                assertThat(envInfo.label()).isEqualTo("env_info");
                assertThat(envInfo.isDefinition()).isEqualTo(1);
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
                // seat-pricing fields parsed from cipx.session.identity (org seat class + cadence)
                assertThat(row.get().organizationType()).isEqualTo("team");
                assertThat(row.get().seatTier()).isEqualTo("priority");
                assertThat(row.get().billingType()).isEqualTo("stripe_subscription_contracted");
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
                // Session-grain subagent link rollup parsed from cipx.session. Every value is
                // distinct from every other integer above, so a positional-bind rotation in
                // CipxTraceIdentityDAO shows up here as a value reported under the wrong name.
                // These are session running totals re-stamped on each trace of the session — a
                // reader aggregates them with max() per session_id, never sum() (migration 000118).
                assertThat(row.get().agentsDispatched()).isEqualTo(17);
                assertThat(row.get().agentsLinked()).isEqualTo(12);
                assertThat(row.get().agentsAmbiguous()).isEqualTo(4);
                // missed = 17 - 4 - 12 = 1: the counters stay disjoint at dispatch grain, which is
                // what makes "we lost one" separable from "we correctly refused four".
                assertThat(row.get().agentsDispatched() - row.get().agentsAmbiguous()
                        - row.get().agentsLinked()).isEqualTo(1);
                assertThat(row.get().cipxVersion()).isEqualTo("0.0.56");

                assertThat(getUserMappings(email)).containsExactly(userUuid);
            });

            assertThat(getCipxIdentity(plainTrace.id(), ws.workspaceId())).isEmpty();
        }

        @Test
        @DisplayName("a session carrying no agent rollup lands as zeros with an empty cipx_version")
        void traceWithoutAgentRollupLandsAsZeros() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();
            String userUuid = UUID.randomUUID().toString();
            String email = "dev-" + UUID.randomUUID() + "@acme.com";

            // A proxy older than these fields is the normal state, not an edge case: cipx ships to
            // laptops on its own cadence. The counters are omitempty on the wire and absence reads
            // as zero by design, so 0 here is faithful — it is NOT a "we don't know" sentinel. The
            // empty cipx_version is what lets a reader tell this row (a daemon too old to report)
            // from a genuine session that dispatched no agents.
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadataWithoutAgentRollup(userUuid, email))
                    .build();
            traceResourceClient.batchCreateTraces(List.of(trace), ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxIdentity(trace.id(), ws.workspaceId());
                assertThat(row).isPresent();
                assertThat(row.get().userUuid()).isEqualTo(userUuid);
                assertThat(row.get().agentsDispatched()).isZero();
                assertThat(row.get().agentsLinked()).isZero();
                assertThat(row.get().agentsAmbiguous()).isZero();
                assertThat(row.get().cipxVersion()).isEmpty();
            });
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

        @Test
        @DisplayName("every positional bind lands in its own column, across a multi-row identity insert")
        void everyIdentityPositionalBindLandsInItsOwnColumn() {
            var ws = newWorkspace();

            // CipxTraceIdentityDAO binds 30 parameters per row by position and
            // nothing checks that order against the INSERT tuple at compile
            // time. A mismatch does not fail the insert — it writes each value
            // into the neighbouring column. Two rows, deliberately: the bind
            // index accumulates across rows, so a wrong stride corrupts the
            // second tuple while a single-row test still passes.
            var rowOne = sentinelIdentityRow(1);
            var rowTwo = sentinelIdentityRow(2);

            cipxTraceIdentityDAO.upsert(List.of(rowOne, rowTwo), ws.workspaceId(), USER).block();

            assertEveryIdentityColumnHoldsItsOwnValue(ws.workspaceId(), rowOne);
            assertEveryIdentityColumnHoldsItsOwnValue(ws.workspaceId(), rowTwo);
        }

        @Test
        @DisplayName("a reordered re-upsert of one trace keeps the newer snapshot's counters")
        void reorderedReUpsertKeepsTheNewerSnapshot() {
            var ws = newWorkspace();

            // A trace is upserted again whenever its identity changes on an update, and the two
            // inserts race in the async queue. Both rows share the merge key, so ReplacingMergeTree
            // keeps one — the one with the greater last_updated_at. Insert them in the wrong order:
            // with an ingestion-time version the older snapshot would win and the session's running
            // totals would go backwards, with no second source to recover them from.
            var base = sentinelIdentityRow(3);
            var newer = base.toBuilder()
                    .agentsDispatched(90L)
                    .lastUpdatedAt(base.lastUpdatedAt().plusMillis(10))
                    .build();
            var older = base.toBuilder().agentsDispatched(80L).build();

            cipxTraceIdentityDAO.upsert(List.of(newer), ws.workspaceId(), USER).block();
            cipxTraceIdentityDAO.upsert(List.of(older), ws.workspaceId(), USER).block();

            var stored = getCipxIdentityAllColumns(base.traceId(), ws.workspaceId());
            assertThat(stored).isPresent();
            assertThat(stored.get().agentsDispatched())
                    .as("agents_dispatched after the older snapshot was inserted last")
                    .isEqualTo(90L);
        }

        @Test
        @DisplayName("agent counters above Integer.MAX_VALUE land unnarrowed, past UInt32 they clamp")
        void agentCountersAboveIntMaxLandUnnarrowed() {
            var ws = newWorkspace();
            String projectName = "cipx-" + UUID.randomUUID();
            String userUuid = UUID.randomUUID().toString();
            String email = "dev-" + UUID.randomUUID() + "@acme.com";

            // The counters are UInt32 columns and ClickHouse wraps an out-of-range literal mod 2^32
            // instead of rejecting it, so a narrowing read corrupts them in silence. The values
            // straddle the two boundaries rather than describe a plausible session: 2^32-1 is the
            // column ceiling (asInt() would carry it as -1), 3e9 is past int, and 9999999999 is
            // past the column and has to clamp instead of wrapping to 1410065407.
            var trace = factory.manufacturePojo(Trace.class).toBuilder()
                    .projectName(projectName)
                    .metadata(traceCipxMetadataWithAgentCounters(userUuid, email, "4294967295", "3000000000",
                            "9999999999"))
                    .build();
            traceResourceClient.batchCreateTraces(List.of(trace), ws.apiKey(), ws.workspaceName());

            await().atMost(30, SECONDS).untilAsserted(() -> {
                var row = getCipxIdentity(trace.id(), ws.workspaceId());
                assertThat(row).as("identity row for a trace whose counters exceed Integer.MAX_VALUE").isPresent();
                assertThat(row.get().agentsDispatched()).as("agents_dispatched at the UInt32 ceiling")
                        .isEqualTo(4294967295L);
                assertThat(row.get().agentsLinked()).as("agents_linked above Integer.MAX_VALUE")
                        .isEqualTo(3000000000L);
                assertThat(row.get().agentsAmbiguous()).as("agents_ambiguous clamped to the UInt32 ceiling")
                        .isEqualTo(4294967295L);
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

    // One distinct value per column, derived from n so two rows never collide. The ids are UUIDs
    // because project_id/trace_id/span_id are FixedString(36) — a rotation among those three is
    // silently accepted by ClickHouse, which is precisely why they need telling apart.
    private CipxSpendDAO.SpanRow sentinelRow(int n) {
        long base = n * 1_000_000L;
        return CipxSpendDAO.SpanRow.builder()
                .projectId(UUID.randomUUID().toString())
                .traceId(UUID.randomUUID().toString())
                .spanId(UUID.randomUUID().toString())
                .startTime(Instant.ofEpochMilli(1_800_000_000_000L + n))
                .model("sentinel-" + n + "-model")
                .uInput(base + 1)
                .uCacheRead(base + 2)
                .uCacheCreation(base + 3)
                .uCacheCreation5m(base + 4)
                .uCacheCreation1h(base + 5)
                .uOutput(base + 6)
                .effort("sentinel-" + n + "-effort")
                .thinkingType("sentinel-" + n + "-thinking-type")
                .maxTokens(base + 7)
                .contextManagement("sentinel-" + n + "-context-management")
                .speed("sentinel-" + n + "-speed")
                .trigger("sentinel-" + n + "-trigger")
                .triggerDetail("sentinel-" + n + "-trigger-detail")
                .turnKey("sentinel-" + n + "-turn-key")
                .parentToolUseId("sentinel-" + n + "-parent-tool-use-id")
                .linkFailureReason("sentinel-" + n + "-link-failure-reason")
                .build();
    }

    // Asserts column by column with the column name as the description, so a bind-order regression
    // reports which column received the wrong value rather than just "expected X but was Y".
    private void assertEveryColumnHoldsItsOwnValue(String workspaceId, CipxSpendDAO.SpanRow expected) {
        var stored = getCipxSpendAllColumns(expected.spanId(), workspaceId);
        assertThat(stored).as("row for span_id %s", expected.spanId()).isPresent();
        var actual = stored.get();

        assertThat(actual.workspaceId()).as("workspace_id").isEqualTo(workspaceId);
        assertThat(actual.projectId()).as("project_id").isEqualTo(expected.projectId());
        assertThat(actual.traceId()).as("trace_id").isEqualTo(expected.traceId());
        assertThat(actual.spanId()).as("span_id").isEqualTo(expected.spanId());
        assertThat(actual.startMs()).as("start_time").isEqualTo(expected.startTime().toEpochMilli());
        assertThat(actual.model()).as("model").isEqualTo(expected.model());
        assertThat(actual.uInput()).as("u_input").isEqualTo(expected.uInput());
        assertThat(actual.uCacheRead()).as("u_cache_read").isEqualTo(expected.uCacheRead());
        assertThat(actual.uCacheCreation()).as("u_cache_creation").isEqualTo(expected.uCacheCreation());
        assertThat(actual.uCacheCreation5m()).as("u_cache_creation_5m").isEqualTo(expected.uCacheCreation5m());
        assertThat(actual.uCacheCreation1h()).as("u_cache_creation_1h").isEqualTo(expected.uCacheCreation1h());
        assertThat(actual.uOutput()).as("u_output").isEqualTo(expected.uOutput());
        assertThat(actual.effort()).as("effort").isEqualTo(expected.effort());
        assertThat(actual.thinkingType()).as("thinking_type").isEqualTo(expected.thinkingType());
        assertThat(actual.maxTokens()).as("max_tokens").isEqualTo(expected.maxTokens());
        assertThat(actual.contextManagement()).as("context_management").isEqualTo(expected.contextManagement());
        assertThat(actual.speed()).as("speed").isEqualTo(expected.speed());
        assertThat(actual.trigger()).as("trigger").isEqualTo(expected.trigger());
        assertThat(actual.triggerDetail()).as("trigger_detail").isEqualTo(expected.triggerDetail());
        assertThat(actual.turnKey()).as("turn_key").isEqualTo(expected.turnKey());
        assertThat(actual.parentToolUseId()).as("parent_tool_use_id").isEqualTo(expected.parentToolUseId());
        assertThat(actual.linkFailureReason()).as("link_failure_reason").isEqualTo(expected.linkFailureReason());
    }

    // Reads every column the DAO writes, including workspace_id and trace_id which the narrower
    // getCipxSpend does not project. Bind-order coverage is only as wide as the read.
    private Optional<SentinelSpendRow> getCipxSpendAllColumns(String spanId, String workspaceId) {
        String sql = """
                SELECT
                    workspace_id AS workspace_id,
                    project_id AS project_id,
                    trace_id AS trace_id,
                    span_id AS span_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    model AS model,
                    u_input, u_cache_read, u_cache_creation, u_cache_creation_5m, u_cache_creation_1h, u_output,
                    effort, thinking_type, max_tokens, context_management, speed,
                    `trigger` AS trigger_kind, trigger_detail, turn_key, parent_tool_use_id,
                    link_failure_reason
                FROM cipx_spends FINAL
                WHERE workspace_id = :workspace_id AND span_id = :span_id
                """;
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("span_id", spanId);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> new SentinelSpendRow(
                            row.get("workspace_id", String.class),
                            row.get("project_id", String.class),
                            row.get("trace_id", String.class),
                            row.get("span_id", String.class),
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
                            row.get("context_management", String.class),
                            row.get("speed", String.class),
                            row.get("trigger_kind", String.class),
                            row.get("trigger_detail", String.class),
                            row.get("turn_key", String.class),
                            row.get("parent_tool_use_id", String.class),
                            row.get("link_failure_reason", String.class)))));
        }).blockOptional();
    }

    // One distinct value per column, derived from n so two rows never collide. project_id / trace_id
    // / user_uuid are UUIDs because they are the merge key, and dirty alternates so a swap between
    // the two rows shows up in it as well as in the counters.
    private CipxTraceIdentityDAO.TraceIdentityRow sentinelIdentityRow(int n) {
        long base = n * 1_000_000L;
        return CipxTraceIdentityDAO.TraceIdentityRow.builder()
                .projectId(UUID.randomUUID().toString())
                .traceId(UUID.randomUUID().toString())
                .startTime(Instant.ofEpochMilli(1_800_000_000_000L + n))
                .userUuid(UUID.randomUUID().toString())
                .userEmail("sentinel-" + n + "-email")
                .userDisplayName("sentinel-" + n + "-display-name")
                .repository("sentinel-" + n + "-repository")
                .sessionId("sentinel-" + n + "-session-id")
                .harness("sentinel-" + n + "-harness")
                .schemaVersion(n * 100 + 1)
                .billingMode("sentinel-" + n + "-billing-mode")
                .plan("sentinel-" + n + "-plan")
                .planUsageStatus("sentinel-" + n + "-plan-usage-status")
                .organizationType("sentinel-" + n + "-organization-type")
                .seatTier("sentinel-" + n + "-seat-tier")
                .billingType("sentinel-" + n + "-billing-type")
                .branch("sentinel-" + n + "-branch")
                .headShaStart("sentinel-" + n + "-head-sha-start")
                .headShaEnd("sentinel-" + n + "-head-sha-end")
                .dirty(n % 2 == 1)
                .commitsInTrace(base + 1)
                .filesAdded(base + 2)
                .filesDeleted(base + 3)
                .linesAdded(base + 4)
                .linesDeleted(base + 5)
                .agentsDispatched(base + 6)
                .agentsLinked(base + 7)
                .agentsAmbiguous(base + 8)
                .cipxVersion("sentinel-" + n + "-cipx-version")
                .lastUpdatedAt(Instant.ofEpochMilli(1_700_000_000_000L + n))
                .build();
    }

    // Asserts column by column with the column name as the description, so a bind-order regression
    // reports which column received the wrong value rather than just "expected X but was Y".
    private void assertEveryIdentityColumnHoldsItsOwnValue(String workspaceId,
            CipxTraceIdentityDAO.TraceIdentityRow expected) {
        var stored = getCipxIdentityAllColumns(expected.traceId(), workspaceId);
        assertThat(stored).as("row for trace_id %s", expected.traceId()).isPresent();
        var actual = stored.get();

        assertThat(actual.workspaceId()).as("workspace_id").isEqualTo(workspaceId);
        assertThat(actual.projectId()).as("project_id").isEqualTo(expected.projectId());
        assertThat(actual.traceId()).as("trace_id").isEqualTo(expected.traceId());
        assertThat(actual.startMs()).as("start_time").isEqualTo(expected.startTime().toEpochMilli());
        assertThat(actual.userUuid()).as("user_uuid").isEqualTo(expected.userUuid());
        assertThat(actual.userEmail()).as("user_email").isEqualTo(expected.userEmail());
        assertThat(actual.userDisplayName()).as("user_display_name").isEqualTo(expected.userDisplayName());
        assertThat(actual.repository()).as("repository").isEqualTo(expected.repository());
        assertThat(actual.sessionId()).as("session_id").isEqualTo(expected.sessionId());
        assertThat(actual.harness()).as("harness").isEqualTo(expected.harness());
        assertThat(actual.schemaVersion()).as("schema_version").isEqualTo(expected.schemaVersion());
        assertThat(actual.billingMode()).as("billing_mode").isEqualTo(expected.billingMode());
        assertThat(actual.plan()).as("plan").isEqualTo(expected.plan());
        assertThat(actual.planUsageStatus()).as("plan_usage_status").isEqualTo(expected.planUsageStatus());
        assertThat(actual.organizationType()).as("organization_type").isEqualTo(expected.organizationType());
        assertThat(actual.seatTier()).as("seat_tier").isEqualTo(expected.seatTier());
        assertThat(actual.billingType()).as("billing_type").isEqualTo(expected.billingType());
        assertThat(actual.branch()).as("branch").isEqualTo(expected.branch());
        assertThat(actual.headShaStart()).as("head_sha_start").isEqualTo(expected.headShaStart());
        assertThat(actual.headShaEnd()).as("head_sha_end").isEqualTo(expected.headShaEnd());
        assertThat(actual.dirty()).as("dirty").isEqualTo(expected.dirty());
        assertThat(actual.commitsInTrace()).as("commits_in_trace").isEqualTo(expected.commitsInTrace());
        assertThat(actual.filesAdded()).as("files_added").isEqualTo(expected.filesAdded());
        assertThat(actual.filesDeleted()).as("files_deleted").isEqualTo(expected.filesDeleted());
        assertThat(actual.linesAdded()).as("lines_added").isEqualTo(expected.linesAdded());
        assertThat(actual.linesDeleted()).as("lines_deleted").isEqualTo(expected.linesDeleted());
        assertThat(actual.agentsDispatched()).as("agents_dispatched").isEqualTo(expected.agentsDispatched());
        assertThat(actual.agentsLinked()).as("agents_linked").isEqualTo(expected.agentsLinked());
        assertThat(actual.agentsAmbiguous()).as("agents_ambiguous").isEqualTo(expected.agentsAmbiguous());
        assertThat(actual.cipxVersion()).as("cipx_version").isEqualTo(expected.cipxVersion());
        assertThat(actual.lastUpdatedMs()).as("last_updated_at").isEqualTo(expected.lastUpdatedAt().toEpochMilli());
    }

    // Reads every column the DAO writes, including workspace_id and last_updated_at which the
    // narrower getCipxIdentity does not project. Bind-order coverage is only as wide as the read.
    private Optional<SentinelIdentityRow> getCipxIdentityAllColumns(String traceId, String workspaceId) {
        String sql = """
                SELECT
                    workspace_id AS workspace_id,
                    project_id AS project_id,
                    trace_id AS trace_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    user_uuid, user_email, user_display_name, repository, session_id, harness, schema_version,
                    billing_mode, plan, plan_usage_status, organization_type, seat_tier, billing_type,
                    branch, head_sha_start, head_sha_end, dirty, commits_in_trace,
                    files_added, files_deleted, lines_added, lines_deleted,
                    agents_dispatched, agents_linked, agents_ambiguous, cipx_version,
                    toUnixTimestamp64Milli(last_updated_at) AS last_updated_ms
                FROM cipx_trace_identities FINAL
                WHERE workspace_id = :workspace_id AND trace_id = :trace_id
                """;
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("trace_id", traceId);
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> SentinelIdentityRow.builder()
                            .workspaceId(row.get("workspace_id", String.class))
                            .projectId(row.get("project_id", String.class))
                            .traceId(row.get("trace_id", String.class))
                            .startMs(row.get("start_ms", Long.class))
                            .userUuid(row.get("user_uuid", String.class))
                            .userEmail(row.get("user_email", String.class))
                            .userDisplayName(row.get("user_display_name", String.class))
                            .repository(row.get("repository", String.class))
                            .sessionId(row.get("session_id", String.class))
                            .harness(row.get("harness", String.class))
                            .schemaVersion(row.get("schema_version", Integer.class))
                            .billingMode(row.get("billing_mode", String.class))
                            .plan(row.get("plan", String.class))
                            .planUsageStatus(row.get("plan_usage_status", String.class))
                            .organizationType(row.get("organization_type", String.class))
                            .seatTier(row.get("seat_tier", String.class))
                            .billingType(row.get("billing_type", String.class))
                            .branch(row.get("branch", String.class))
                            .headShaStart(row.get("head_sha_start", String.class))
                            .headShaEnd(row.get("head_sha_end", String.class))
                            .dirty(row.get("dirty", Boolean.class))
                            .commitsInTrace(row.get("commits_in_trace", Long.class))
                            .filesAdded(row.get("files_added", Long.class))
                            .filesDeleted(row.get("files_deleted", Long.class))
                            .linesAdded(row.get("lines_added", Long.class))
                            .linesDeleted(row.get("lines_deleted", Long.class))
                            .agentsDispatched(row.get("agents_dispatched", Long.class))
                            .agentsLinked(row.get("agents_linked", Long.class))
                            .agentsAmbiguous(row.get("agents_ambiguous", Long.class))
                            .cipxVersion(row.get("cipx_version", String.class))
                            .lastUpdatedMs(row.get("last_updated_ms", Long.class))
                            .build())));
        }).blockOptional();
    }

    // A cipx call carrying fields this backend has never heard of, at every level a newer proxy could
    // add them: on the call, inside usage (inference_geo is the real pending one — OPIK-7757), inside
    // cache_creation, inside config, as a sibling of call under cipx, on a block, and beside cipx in
    // metadata. Every known field is still present and must still parse.
    private static JsonNode unknownFieldsCipxMetadata(String model) {
        return JsonUtils.getJsonNodeFromString(
                """
                        {
                          "unrelated_top_level": {"anything": true},
                          "cipx": {
                            "future_section": {"whatever": [1, 2, 3]},
                            "call": {
                              "model": "%s",
                              "future_scalar": "ignored",
                              "future_object": {"nested": {"deep": 1}},
                              "future_array": [{"a": 1}, {"b": 2}],
                              "usage": {
                                "input_tokens": 11,
                                "cache_read_input_tokens": 22,
                                "cache_creation_input_tokens": 33,
                                "cache_creation": {
                                  "ephemeral_5m_input_tokens": 44,
                                  "ephemeral_1h_input_tokens": 55,
                                  "ephemeral_7d_input_tokens": 77
                                },
                                "output_tokens": 66,
                                "service_tier": "standard",
                                "inference_geo": "not_available"
                              },
                              "config": {
                                "effort": "high",
                                "thinking_type": "adaptive",
                                "max_tokens": 64000,
                                "context_management": "clear_thinking_20251015",
                                "speed": "fast",
                                "future_knob": true
                              },
                              "trigger": "subagent",
                              "trigger_detail": "Explore",
                              "turn_key": "unknown-fields-turnkey",
                              "parent_tool_use_id": "toolu_unknown_fields",
                              "link_failure_reason": "parent_unresolved",
                              "spawn_depth": 2
                            },
                            "blocks": [
                              {"category":"skills_loaded","side":"input","cache_status":"read","parent_category":"context","chars":100,"tool_name":"","tool_server":"","tool_use_id":"","resource":"dataviz","kind":"text","future_block_field":"ignored"}
                            ]
                          }
                        }
                        """
                        .formatted(model));
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
                                "context_management": "clear_thinking_20251015",
                                "speed": "fast"
                              },
                              "trigger": "subagent",
                              "trigger_detail": "code-reviewer",
                              "turn_key": "abc123turnkey",
                              "parent_tool_use_id": "toolu_parent_agent"
                            },
                            "blocks": [
                              {"category":"memory","side":"input","cache_status":"read","parent_category":"context","chars":120,"tool_name":"","tool_server":"","tool_use_id":"","resource":"CLAUDE.md","kind":"text","subcategory":"auto_memory","sha256":"a1b2c3"},
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

    // A subagent call cipx could not attribute: trigger=subagent with no parent_tool_use_id, carrying
    // only the reason it gave up. The two reasons this is used with mean opposite things to an
    // operator, which is the whole point of persisting the column.
    private static JsonNode unattributedSubagentCipxMetadata(String linkFailureReason) {
        return JsonUtils.getJsonNodeFromString(
                """
                        {
                          "cipx": {
                            "call": {
                              "model": "claude-sonnet-4-6",
                              "usage": {
                                "input_tokens": 10,
                                "cache_read_input_tokens": 0,
                                "cache_creation_input_tokens": 0,
                                "output_tokens": 5
                              },
                              "trigger": "subagent",
                              "trigger_detail": "",
                              "turn_key": "unattributed-turnkey",
                              "parent_tool_use_id": "",
                              "link_failure_reason": "%s"
                            },
                            "blocks": [
                              {"category":"agent_overhead","side":"input","cache_status":"none","parent_category":"context","chars":10,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"}
                            ]
                          }
                        }
                        """
                        .formatted(linkFailureReason));
    }

    private static JsonNode systemToolsCipxMetadata(String model, long cacheRead) {
        return JsonUtils.getJsonNodeFromString(
                """
                        {
                          "cipx": {
                            "call": {
                              "model": "%s",
                              "usage": {
                                "input_tokens": 0,
                                "cache_read_input_tokens": %d,
                                "cache_creation_input_tokens": 0,
                                "output_tokens": 0
                              },
                              "config": {
                                "effort": "high",
                                "thinking_type": "adaptive",
                                "max_tokens": 64000,
                                "context_management": "clear_thinking_20251015",
                                "speed": "fast"
                              }
                            },
                            "blocks": [
                              {"category":"system_tools","side":"input","cache_status":"read","parent_category":"context","chars":150,"tool_name":"Bash","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"system_tools_deferred","side":"input","cache_status":"read","parent_category":"context","chars":50,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"system_prompt","side":"input","cache_status":"read","parent_category":"context","chars":40,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"},
                              {"category":"env_info","side":"input","cache_status":"read","parent_category":"context","chars":30,"tool_name":"","tool_server":"","tool_use_id":"","resource":"","kind":"text"}
                            ]
                          }
                        }
                        """
                        .formatted(model, cacheRead));
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
                      "agents_dispatched": 17,
                      "agents_linked": 12,
                      "agents_ambiguous": 4,
                      "cipx_version": "0.0.56",
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
                        "plan_usage_status": "within",
                        "organization_type": "team",
                        "seat_tier": "priority",
                        "billing_type": "stripe_subscription_contracted"
                      }
                    }
                  }
                }
                """.formatted(schemaVersion, harness, repository, userUuid, email, displayName));
    }

    // The counters are interpolated as raw JSON so a test can put a value past Integer.MAX_VALUE on
    // the wire, which is where the UInt32 columns' range gets lost or kept.
    private static JsonNode traceCipxMetadataWithAgentCounters(String userUuid, String email, String dispatched,
            String linked, String ambiguous) {
        return JsonUtils.getJsonNodeFromString("""
                {
                  "cipx": {
                    "session": {
                      "schema_version": 3,
                      "session_id": "cc-session-big-counters",
                      "harness": "claude_code",
                      "agents_dispatched": %s,
                      "agents_linked": %s,
                      "agents_ambiguous": %s,
                      "cipx_version": "0.0.56",
                      "identity": {
                        "user_uuid": "%s",
                        "email": "%s",
                        "display_name": "Big Counters Dev"
                      }
                    }
                  }
                }
                """.formatted(dispatched, linked, ambiguous, userUuid, email));
    }

    // The identity envelope a proxy older than the agent-link rollup ships: session + identity, no
    // agents_* counters and no cipx_version.
    private static JsonNode traceCipxMetadataWithoutAgentRollup(String userUuid, String email) {
        return JsonUtils.getJsonNodeFromString("""
                {
                  "cipx": {
                    "session": {
                      "schema_version": 1,
                      "session_id": "cc-session-legacy",
                      "harness": "claude_code",
                      "identity": {
                        "user_uuid": "%s",
                        "email": "%s",
                        "display_name": "Legacy Dev"
                      }
                    }
                  }
                }
                """.formatted(userUuid, email));
    }

    private Optional<CipxSpendRow> getCipxSpend(UUID spanId, String workspaceId) {
        String sql = """
                SELECT
                    project_id AS project_id,
                    toUnixTimestamp64Milli(start_time) AS start_ms,
                    model AS model,
                    u_input, u_cache_read, u_cache_creation, u_cache_creation_5m, u_cache_creation_1h, u_output,
                    effort, thinking_type, max_tokens, context_management, speed,
                    `trigger` AS trigger_kind, trigger_detail, turn_key, parent_tool_use_id,
                    link_failure_reason
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
                            row.get("context_management", String.class),
                            row.get("speed", String.class),
                            row.get("trigger_kind", String.class),
                            row.get("trigger_detail", String.class),
                            row.get("turn_key", String.class),
                            row.get("parent_tool_use_id", String.class),
                            row.get("link_failure_reason", String.class)))));
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
                    speed,
                    side, cache_status, parent_category, chars,
                    tool_name, tool_server, tool_use_id, resource, kind, subcategory,
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
                            row.get("speed", String.class),
                            row.get("side", String.class),
                            row.get("cache_status", String.class),
                            row.get("parent_category", String.class),
                            row.get("chars", Long.class),
                            row.get("tool_name", String.class),
                            row.get("tool_server", String.class),
                            row.get("tool_use_id", String.class),
                            row.get("resource", String.class),
                            row.get("kind", String.class),
                            row.get("subcategory", String.class),
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
                    billing_mode, plan, plan_usage_status, organization_type, seat_tier, billing_type,
                    branch, head_sha_start, head_sha_end, dirty, commits_in_trace,
                    files_added, files_deleted, lines_added, lines_deleted,
                    agents_dispatched, agents_linked, agents_ambiguous, cipx_version
                FROM cipx_trace_identities FINAL
                WHERE workspace_id = :workspace_id AND trace_id = :trace_id
                """;
        return clickHouseTemplate.nonTransaction(connection -> {
            var statement = connection.createStatement(sql)
                    .bind("workspace_id", workspaceId)
                    .bind("trace_id", traceId.toString());
            return Mono.from(statement.execute())
                    .flatMap(result -> Mono.from(result.map((row, meta) -> CipxIdentityRow.builder()
                            .projectId(row.get("project_id", String.class))
                            .startMs(row.get("start_ms", Long.class))
                            .userUuid(row.get("user_uuid", String.class))
                            .userEmail(row.get("user_email", String.class))
                            .userDisplayName(row.get("user_display_name", String.class))
                            .repository(row.get("repository", String.class))
                            .sessionId(row.get("session_id", String.class))
                            .harness(row.get("harness", String.class))
                            .schemaVersion(row.get("schema_version", Integer.class))
                            .billingMode(row.get("billing_mode", String.class))
                            .plan(row.get("plan", String.class))
                            .planUsageStatus(row.get("plan_usage_status", String.class))
                            .organizationType(row.get("organization_type", String.class))
                            .seatTier(row.get("seat_tier", String.class))
                            .billingType(row.get("billing_type", String.class))
                            .branch(row.get("branch", String.class))
                            .headShaStart(row.get("head_sha_start", String.class))
                            .headShaEnd(row.get("head_sha_end", String.class))
                            .dirty(row.get("dirty", Boolean.class))
                            .commitsInTrace(row.get("commits_in_trace", Long.class))
                            .filesAdded(row.get("files_added", Long.class))
                            .filesDeleted(row.get("files_deleted", Long.class))
                            .linesAdded(row.get("lines_added", Long.class))
                            .linesDeleted(row.get("lines_deleted", Long.class))
                            .agentsDispatched(row.get("agents_dispatched", Long.class))
                            .agentsLinked(row.get("agents_linked", Long.class))
                            .agentsAmbiguous(row.get("agents_ambiguous", Long.class))
                            .cipxVersion(row.get("cipx_version", String.class))
                            .build())));
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
            String thinkingType, Long maxTokens, String contextManagement, String speed, String trigger,
            String triggerDetail, String turnKey, String parentToolUseId, String linkFailureReason) {
    }

    private record SentinelSpendRow(String workspaceId, String projectId, String traceId, String spanId,
            Long startMs, String model, Long uInput, Long uCacheRead, Long uCacheCreation, Long uCacheCreation5m,
            Long uCacheCreation1h, Long uOutput, String effort, String thinkingType, Long maxTokens,
            String contextManagement, String speed, String trigger, String triggerDetail, String turnKey,
            String parentToolUseId, String linkFailureReason) {
    }

    private record CipxBlockRow(Integer blockIdx, String src, String category, String tier, String lane,
            String bdLane, String label, Integer isDefinition, Double alloc, String model, String speed,
            String side,
            String cacheStatus, String parentCategory, Long chars, String toolName, String toolServer,
            String toolUseId, String resource, String kind, String subcategory, String contentSha256, Long startMs) {
    }

    @Builder
    private record CipxIdentityRow(String projectId, Long startMs, String userUuid, String userEmail,
            String userDisplayName, String repository, String sessionId, String harness, Integer schemaVersion,
            String billingMode, String plan, String planUsageStatus, String organizationType, String seatTier,
            String billingType,
            String branch, String headShaStart, String headShaEnd, Boolean dirty, Long commitsInTrace,
            Long filesAdded, Long filesDeleted, Long linesAdded, Long linesDeleted,
            Long agentsDispatched, Long agentsLinked, Long agentsAmbiguous, String cipxVersion) {
    }

    @Builder
    private record SentinelIdentityRow(String workspaceId, String projectId, String traceId, Long startMs,
            String userUuid, String userEmail, String userDisplayName, String repository, String sessionId,
            String harness, Integer schemaVersion, String billingMode, String plan, String planUsageStatus,
            String organizationType, String seatTier, String billingType, String branch, String headShaStart,
            String headShaEnd, Boolean dirty, Long commitsInTrace, Long filesAdded, Long filesDeleted,
            Long linesAdded, Long linesDeleted, Long agentsDispatched, Long agentsLinked, Long agentsAmbiguous,
            String cipxVersion, Long lastUpdatedMs) {
    }
}
