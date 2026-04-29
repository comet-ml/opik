package com.comet.opik.domain.retention;

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
import com.comet.opik.api.resources.utils.resources.RetentionRuleResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanType;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.awaitility.Awaitility;
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
import ru.vyarus.dropwizard.guice.test.jupiter.param.Jit;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RetentionPolicyServiceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String PROJECT_NAME = "retention-test-project";

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    private final WireMockUtils.WireMockRuntime wireMock;

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        var contextConfig = TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                .jdbcUrl(MYSQL_CONTAINER.getJdbcUrl())
                .databaseAnalyticsFactory(databaseAnalyticsFactory)
                .runtimeInfo(wireMock.runtimeInfo())
                .redisUrl(REDIS.getRedisURI())
                .customConfigs(List.of(
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.enabled", "true"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.executionsPerDay", "48"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.enabled", "true"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.smallThreshold", "10000"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.largeThreshold", "100000"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.smallBatchSize", "200"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.mediumBatchSize", "10"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.mediumChunkDays", "7"),
                        new TestDropwizardAppExtensionUtils.CustomConfig("retention.catchUp.largeChunkDays", "1")))
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
    }

    private String baseURI;
    private String workspaceId;
    private RetentionRuleResourceClient retentionClient;
    private TraceResourceClient traceClient;
    private SpanResourceClient spanClient;
    private RetentionPolicyService retentionPolicyService;
    private RetentionCatchUpService catchUpService;
    private RetentionEstimationService estimationService;
    private TransactionTemplateAsync templateAsync;
    private IdGenerator idGenerator;

    @BeforeAll
    void beforeAll(ClientSupport client, @Jit RetentionPolicyService retentionPolicyService,
            @Jit RetentionCatchUpService catchUpService, @Jit RetentionEstimationService estimationService,
            TransactionTemplateAsync templateAsync, IdGenerator idGenerator) {
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.retentionPolicyService = retentionPolicyService;
        this.estimationService = estimationService;
        this.catchUpService = catchUpService;
        this.templateAsync = templateAsync;
        this.idGenerator = idGenerator;
        this.retentionClient = new RetentionRuleResourceClient(client, baseURI);
        this.traceClient = new TraceResourceClient(client, baseURI);
        this.spanClient = new SpanResourceClient(client, baseURI);

        // Workspace ID that falls in fraction 0's range (starts with 00-05)
        this.workspaceId = "00000001-0000-0000-0000-000000000000";

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE_NAME, workspaceId, USER);
    }

    @Nested
    @DisplayName("Retention cycle execution")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RetentionCycleExecution {

        @Test
        @DisplayName("Deletes expired traces and spans, keeps recent data")
        void deletesExpiredDataAndKeepsRecentData() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            Instant now = Instant.now();
            // Old data within the 3-day sliding window: 15 days ago (14d retention + 1d inside window)
            Instant oldTime = now.minus(15, ChronoUnit.DAYS);
            Instant recentTime = now.minus(5, ChronoUnit.DAYS);

            UUID oldTraceId = idGenerator.generateId(oldTime);
            UUID oldSpanId = idGenerator.generateId(oldTime);
            UUID recentTraceId = idGenerator.generateId(recentTime);
            UUID recentSpanId = idGenerator.generateId(recentTime);

            createTestTrace(oldTraceId, API_KEY, TEST_WORKSPACE_NAME);
            createTestSpan(oldSpanId, oldTraceId, API_KEY, TEST_WORKSPACE_NAME);

            createTestTrace(recentTraceId, API_KEY, TEST_WORKSPACE_NAME);
            createTestSpan(recentSpanId, recentTraceId, API_KEY, TEST_WORKSPACE_NAME);

            // Wait for async writes to reach ClickHouse before verifying
            waitForRows("traces", workspaceId, 2);
            waitForRows("spans", workspaceId, 2);

            // Execute retention cycle for fraction 0 (our workspace falls in this range)
            retentionPolicyService.executeRetentionCycle(0, now).block();

            // Verify: old traces/spans deleted, recent data kept
            assertThat(countRows("traces", workspaceId)).isEqualTo(1);
            assertThat(countRows("spans", workspaceId)).isEqualTo(1);

            // Verify the remaining rows are the recent ones
            assertThat(countRowsById("traces", recentTraceId)).isEqualTo(1);
            assertThat(countRowsById("spans", recentSpanId)).isEqualTo(1);
        }

        @Test
        @DisplayName("No rules in range - no deletions")
        void noRulesInRange_noDeletes() {
            String farWsId = "ff000001-0000-0000-0000-000000000000";
            String farApiKey = UUID.randomUUID().toString();
            String farWsName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String farUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), farApiKey, farWsName, farWsId, farUser);

            UUID traceId = idGenerator.generateId(Instant.now().minus(30, ChronoUnit.DAYS));
            createTestTrace(traceId, farApiKey, farWsName);
            waitForRows("traces", farWsId, 1);

            // Execute fraction 47 - no retention rules exist for this workspace
            retentionPolicyService.executeRetentionCycle(47, Instant.now()).block();

            assertThat(countRows("traces", farWsId)).isEqualTo(1);
        }

        @Test
        @DisplayName("Unlimited retention rules are ignored")
        void unlimitedRetentionRulesAreIgnored() {
            String unlimitedWsId = "00000002-0000-0000-0000-000000000000";
            String unlimitedApiKey = UUID.randomUUID().toString();
            String unlimitedWsName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String unlimitedUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), unlimitedApiKey, unlimitedWsName, unlimitedWsId,
                    unlimitedUser);

            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.UNLIMITED).build();
            retentionClient.createAndGet(rule, unlimitedApiKey, unlimitedWsName);

            UUID traceId = idGenerator.generateId(Instant.now().minus(500, ChronoUnit.DAYS));
            createTestTrace(traceId, unlimitedApiKey, unlimitedWsName);
            waitForRows("traces", unlimitedWsId, 1);

            retentionPolicyService.executeRetentionCycle(0, Instant.now()).block();

            // Data should still be there - unlimited means no deletion
            assertThat(countRows("traces", unlimitedWsId)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Actual ClickHouse deletion verification")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class DeletionVerification {

        private static final String WS_DELETION = "00000003-0000-0000-0000-000000000000";
        private static final String WS_DELETION_API_KEY = UUID.randomUUID().toString();
        private static final String WS_DELETION_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
        private static final String WS_DELETION_USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

        @BeforeAll
        void setUp() {
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), WS_DELETION_API_KEY, WS_DELETION_NAME,
                    WS_DELETION, WS_DELETION_USER);
        }

        @Test
        @DisplayName("Deletion removes only traces/spans older than cutoff")
        void deletesOnlyOldRowsAcrossAllTables() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            retentionClient.createAndGet(rule, WS_DELETION_API_KEY, WS_DELETION_NAME);

            Instant now = Instant.now();
            // 61 days old: within the 3-day window past 60d cutoff
            Instant oldTime = now.minus(61, ChronoUnit.DAYS);
            Instant recentTime = now.minus(30, ChronoUnit.DAYS);

            UUID oldTraceId = idGenerator.generateId(oldTime);
            UUID oldSpanId = idGenerator.generateId(oldTime);
            UUID recentTraceId = idGenerator.generateId(recentTime);
            UUID recentSpanId = idGenerator.generateId(recentTime);

            // Old data (61 days old > 60 day retention -> deleted)
            createTestTrace(oldTraceId, WS_DELETION_API_KEY, WS_DELETION_NAME);
            createTestSpan(oldSpanId, oldTraceId, WS_DELETION_API_KEY, WS_DELETION_NAME);

            // Recent data (30 days old < 60 day retention -> kept)
            createTestTrace(recentTraceId, WS_DELETION_API_KEY, WS_DELETION_NAME);
            createTestSpan(recentSpanId, recentTraceId, WS_DELETION_API_KEY, WS_DELETION_NAME);

            waitForRows("traces", WS_DELETION, 2);
            waitForRows("spans", WS_DELETION, 2);

            retentionPolicyService.executeRetentionCycle(0, now).block();

            // Only recent traces/spans remain
            assertThat(countRows("traces", WS_DELETION)).isEqualTo(1);
            assertThat(countRows("spans", WS_DELETION)).isEqualTo(1);

            assertThat(countRowsById("traces", recentTraceId)).isEqualTo(1);
            assertThat(countRowsById("spans", recentSpanId)).isEqualTo(1);
            assertThat(countRowsById("traces", oldTraceId)).isZero();
            assertThat(countRowsById("spans", oldSpanId)).isZero();
        }

        @Test
        @DisplayName("Deletion does not touch rows in other workspaces")
        void deletionIsScopedToTargetWorkspaces() {
            String otherWsId = "00000004-0000-0000-0000-000000000000";
            String otherApiKey = UUID.randomUUID().toString();
            String otherWsName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String otherUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), otherApiKey, otherWsName, otherWsId, otherUser);

            Instant oldTime = Instant.now().minus(61, ChronoUnit.DAYS);

            // Insert old data in the OTHER workspace (no retention rule)
            UUID otherTraceId = idGenerator.generateId(oldTime);
            UUID otherSpanId = idGenerator.generateId(oldTime);
            createTestTrace(otherTraceId, otherApiKey, otherWsName);
            createTestSpan(otherSpanId, otherTraceId, otherApiKey, otherWsName);
            waitForRows("traces", otherWsId, 1);
            waitForRows("spans", otherWsId, 1);

            retentionPolicyService.executeRetentionCycle(0, Instant.now()).block();

            // Other workspace data should be untouched
            assertThat(countRows("traces", otherWsId)).isEqualTo(1);
            assertThat(countRows("spans", otherWsId)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Rule priority resolution")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class RulePriorityResolution {

        private static final String WS_PRIORITY = "00000005-0000-0000-0000-000000000000";
        private static final String WS_PRIORITY_API_KEY = UUID.randomUUID().toString();
        private static final String WS_PRIORITY_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
        private static final String WS_PRIORITY_USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

        @BeforeAll
        void setUp() {
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), WS_PRIORITY_API_KEY, WS_PRIORITY_NAME,
                    WS_PRIORITY, WS_PRIORITY_USER);
        }

        @Test
        @DisplayName("Workspace rule takes priority over organization rule")
        void workspaceRuleTakesPriorityOverOrgRule() {
            // Org rule: 400 days (very permissive)
            var orgRule = retentionClient.buildOrganizationRule(RetentionPeriod.EXTENDED_400D).build();
            retentionClient.createAndGet(orgRule, WS_PRIORITY_API_KEY, WS_PRIORITY_NAME);

            // Workspace rule: 14 days (restrictive) - should win
            var wsRule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            retentionClient.createAndGet(wsRule, WS_PRIORITY_API_KEY, WS_PRIORITY_NAME);

            Instant now = Instant.now();

            // 15 days old: within 3-day window past 14d cutoff, outside workspace rule
            UUID traceId15d = idGenerator.generateId(now.minus(15, ChronoUnit.DAYS));
            createTestTrace(traceId15d, WS_PRIORITY_API_KEY, WS_PRIORITY_NAME);

            // 5 days old: within both rules
            UUID traceId5d = idGenerator.generateId(now.minus(5, ChronoUnit.DAYS));
            createTestTrace(traceId5d, WS_PRIORITY_API_KEY, WS_PRIORITY_NAME);
            waitForRows("traces", WS_PRIORITY, 2);

            retentionPolicyService.executeRetentionCycle(0, now).block();

            // Workspace rule (14d) wins: 15-day trace deleted, 5-day trace kept
            assertThat(countRows("traces", WS_PRIORITY)).isEqualTo(1);
            assertThat(countRowsById("traces", traceId5d)).isEqualTo(1);
            assertThat(countRowsById("traces", traceId15d)).isZero();
        }

        @Test
        @DisplayName("Organization rule applies when no workspace rule exists")
        void orgRuleAppliesWhenNoWorkspaceRule() {
            String wsOnlyOrg = "00000006-0000-0000-0000-000000000000";
            String wsOnlyOrgApiKey = UUID.randomUUID().toString();
            String wsOnlyOrgName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String wsOnlyOrgUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), wsOnlyOrgApiKey, wsOnlyOrgName,
                    wsOnlyOrg, wsOnlyOrgUser);

            // Only org rule: 60 days
            var orgRule = retentionClient.buildOrganizationRule(RetentionPeriod.BASE_60D).build();
            retentionClient.createAndGet(orgRule, wsOnlyOrgApiKey, wsOnlyOrgName);

            Instant now = Instant.now();

            // 61 days old: within sliding window, outside org rule (60d)
            UUID oldTraceId = idGenerator.generateId(now.minus(61, ChronoUnit.DAYS));
            createTestTrace(oldTraceId, wsOnlyOrgApiKey, wsOnlyOrgName);

            // 30 days old: within org rule (60d)
            UUID recentTraceId = idGenerator.generateId(now.minus(30, ChronoUnit.DAYS));
            createTestTrace(recentTraceId, wsOnlyOrgApiKey, wsOnlyOrgName);
            waitForRows("traces", wsOnlyOrg, 2);

            retentionPolicyService.executeRetentionCycle(0, now).block();

            // Org rule (60d) applies: old trace deleted, recent trace kept
            assertThat(countRows("traces", wsOnlyOrg)).isEqualTo(1);
            assertThat(countRowsById("traces", recentTraceId)).isEqualTo(1);
        }

        @Test
        @DisplayName("Multiple workspaces with different retention periods are handled correctly")
        void multipleWorkspacesDifferentRetention() {
            String ws14d = "00000007-0000-0000-0000-000000000000";
            String ws14dApiKey = UUID.randomUUID().toString();
            String ws14dName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String ws14dUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), ws14dApiKey, ws14dName, ws14d, ws14dUser);

            String ws400d = "00000008-0000-0000-0000-000000000000";
            String ws400dApiKey = UUID.randomUUID().toString();
            String ws400dName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String ws400dUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), ws400dApiKey, ws400dName, ws400d, ws400dUser);

            // ws14d: strict 14-day retention
            retentionClient.createAndGet(
                    retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build(),
                    ws14dApiKey, ws14dName);

            // ws400d: lenient 400-day retention
            retentionClient.createAndGet(
                    retentionClient.buildWorkspaceRule(RetentionPeriod.EXTENDED_400D).build(),
                    ws400dApiKey, ws400dName);

            Instant now = Instant.now();

            // 15-day old trace in both workspaces (within 3-day window for 14d retention)
            UUID trace14d = idGenerator.generateId(now.minus(15, ChronoUnit.DAYS));
            UUID trace400d = idGenerator.generateId(now.minus(15, ChronoUnit.DAYS));
            createTestTrace(trace14d, ws14dApiKey, ws14dName);
            createTestTrace(trace400d, ws400dApiKey, ws400dName);
            waitForRows("traces", ws14d, 1);
            waitForRows("traces", ws400d, 1);

            retentionPolicyService.executeRetentionCycle(0, now).block();

            // ws14d: 15 days > 14 day retention -> deleted
            assertThat(countRows("traces", ws14d)).isZero();

            // ws400d: 15 days < 400 day retention -> kept
            assertThat(countRows("traces", ws400d)).isEqualTo(1);
        }

        @Test
        @DisplayName("Disabled rules are not executed")
        void disabledRulesNotExecuted() {
            String wsDisabled = "00000009-0000-0000-0000-000000000000";
            String wsDisabledApiKey = UUID.randomUUID().toString();
            String wsDisabledName = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
            String wsDisabledUser = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), wsDisabledApiKey, wsDisabledName,
                    wsDisabled, wsDisabledUser);

            // Create and then deactivate
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            var created = retentionClient.createAndGet(rule, wsDisabledApiKey, wsDisabledName);
            retentionClient.deactivate(created.id(), wsDisabledApiKey, wsDisabledName);

            UUID oldTraceId = idGenerator.generateId(Instant.now().minus(15, ChronoUnit.DAYS));
            createTestTrace(oldTraceId, wsDisabledApiKey, wsDisabledName);
            waitForRows("traces", wsDisabled, 1);

            retentionPolicyService.executeRetentionCycle(0, Instant.now()).block();

            // Data should still be there - rule was deactivated
            assertThat(countRows("traces", wsDisabled)).isEqualTo(1);
        }
    }

    @Nested
    @DisplayName("Catch-up job")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class CatchUpJob {

        // Small workspace for catch-up integration test
        private static final String WS_SMALL = "0000000a-0000-0000-0000-000000000000";
        private static final String WS_SMALL_API_KEY = UUID.randomUUID().toString();
        private static final String WS_SMALL_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
        private static final String WS_SMALL_USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

        // No-apply workspace: applyToPast=false, catch-up should be marked done immediately
        private static final String WS_NOAPPLY = "0000000c-0000-0000-0000-000000000000";
        private static final String WS_NOAPPLY_API_KEY = UUID.randomUUID().toString();
        private static final String WS_NOAPPLY_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
        private static final String WS_NOAPPLY_USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

        // NOTE: The TOO_MANY_ROWS / large workspace / scouting paths are tested in
        // RetentionRuleServiceVelocityTest using mocked DAOs, because ClickHouse's
        // max_rows_to_read profile setting also blocks normal INSERT/SELECT operations.

        @BeforeAll
        void setUp() {
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), WS_SMALL_API_KEY, WS_SMALL_NAME,
                    WS_SMALL, WS_SMALL_USER);
            AuthTestUtils.mockTargetWorkspace(wireMock.server(), WS_NOAPPLY_API_KEY, WS_NOAPPLY_NAME,
                    WS_NOAPPLY, WS_NOAPPLY_USER);
        }

        @Test
        @DisplayName("applyToPast=false marks catch-up as done immediately")
        void noApplyToPast_catchUpDoneImmediately() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D)
                    .applyToPast(false).build();
            var created = retentionClient.createAndGet(rule, WS_NOAPPLY_API_KEY, WS_NOAPPLY_NAME);

            assertThat(created.catchUpDone()).isTrue();
            assertThat(created.catchUpCursor()).isNull();
        }

        @Test
        @DisplayName("Small workspace: velocity estimated, catch-up deletes old data in one shot")
        void smallWorkspace_oneShotCatchUp() {
            Instant now = Instant.now();

            // Insert old data (18 days ago — beyond 14d retention + 3d sliding window)
            // Needs enough spans for non-zero velocity: uniq(id)/weeks >= 1
            Instant oldTime = now.minus(18, ChronoUnit.DAYS);
            UUID oldTraceId1 = idGenerator.generateId(oldTime);
            UUID oldTraceId2 = idGenerator.generateId(oldTime.plusSeconds(1));

            createTestTrace(oldTraceId1, WS_SMALL_API_KEY, WS_SMALL_NAME);
            createTestTrace(oldTraceId2, WS_SMALL_API_KEY, WS_SMALL_NAME);
            for (int i = 0; i < 5; i++) {
                createTestSpan(idGenerator.generateId(oldTime.plusMillis(i)), oldTraceId1,
                        WS_SMALL_API_KEY, WS_SMALL_NAME);
            }
            for (int i = 0; i < 5; i++) {
                createTestSpan(idGenerator.generateId(oldTime.plusSeconds(1).plusMillis(i)), oldTraceId2,
                        WS_SMALL_API_KEY, WS_SMALL_NAME);
            }

            // Insert recent data (5 days ago — within retention period, should survive)
            Instant recentTime = now.minus(5, ChronoUnit.DAYS);
            UUID recentTraceId = idGenerator.generateId(recentTime);
            UUID recentSpanId = idGenerator.generateId(recentTime);

            createTestTrace(recentTraceId, WS_SMALL_API_KEY, WS_SMALL_NAME);
            createTestSpan(recentSpanId, recentTraceId, WS_SMALL_API_KEY, WS_SMALL_NAME);

            waitForRows("traces", WS_SMALL, 3);
            waitForRows("spans", WS_SMALL, 11);

            // Create rule with applyToPast=true — saved as pending estimation (no velocity/cursor yet)
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D)
                    .applyToPast(true).build();
            var created = retentionClient.createAndGet(rule, WS_SMALL_API_KEY, WS_SMALL_NAME);

            // Rule pending estimation: no velocity, no cursor, not done
            assertThat(created.catchUpDone()).isFalse();
            assertThat(created.catchUpVelocity()).isNull();
            assertThat(created.catchUpCursor()).isNull();

            // Run estimation job — populates velocity + cursor
            estimationService.estimatePendingRules();

            // Run catch-up — small workspace should be processed in one shot
            catchUpService.executeCatchUpCycle(now).block();

            // Old data should be deleted, recent data kept
            assertThat(countRows("traces", WS_SMALL)).isEqualTo(1);
            assertThat(countRows("spans", WS_SMALL)).isEqualTo(1);
            assertThat(countRowsById("traces", recentTraceId)).isEqualTo(1);
            assertThat(countRowsById("traces", oldTraceId1)).isZero();
            assertThat(countRowsById("traces", oldTraceId2)).isZero();

            // Catch-up should be marked done
            var updated = retentionClient.get(created.id(), WS_SMALL_API_KEY, WS_SMALL_NAME, 200);
            assertThat(updated.catchUpDone()).isTrue();
            assertThat(updated.catchUpCursor()).isNull();
        }

    }

    // -- Resource client insert helpers --

    private void createTestTrace(UUID id, String apiKey, String wsName) {
        traceClient.createTrace(Trace.builder()
                .id(id)
                .name("test-trace")
                .projectName(PROJECT_NAME)
                .startTime(Instant.now())
                .build(), apiKey, wsName);
    }

    private void createTestSpan(UUID id, UUID traceId, String apiKey, String wsName) {
        spanClient.createSpan(Span.builder()
                .id(id)
                .traceId(traceId)
                .name("test-span")
                .type(SpanType.general)
                .projectName(PROJECT_NAME)
                .startTime(Instant.now())
                .build(), apiKey, wsName);
    }

    // -- ClickHouse read helpers for verification --

    private long countRows(String table, String wsId) {
        return templateAsync.nonTransaction(connection -> {
            var sql = "SELECT count() as cnt FROM %s WHERE workspace_id = '%s'".formatted(table, wsId);
            return Mono.from(connection.createStatement(sql).execute())
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class))));
        }).block();
    }

    private long countRowsById(String table, UUID id) {
        return templateAsync.nonTransaction(connection -> {
            var sql = "SELECT count() as cnt FROM %s WHERE id = '%s'".formatted(table, id);
            return Mono.from(connection.createStatement(sql).execute())
                    .flatMap(result -> Mono.from(result.map((row, metadata) -> row.get("cnt", Long.class))));
        }).block();
    }

    /**
     * Wait for ClickHouse to have at least {@code expected} rows for the given table/workspace.
     * Trace and span writes go through an async pipeline (Redis streams -> ClickHouse),
     * so data may not be visible immediately after REST API returns 201.
     */
    private void waitForRows(String table, String wsId, long expected) {
        Awaitility.await()
                .atMost(10, TimeUnit.SECONDS)
                .pollInterval(Duration.ofMillis(300))
                .untilAsserted(() -> assertThat(countRows(table, wsId))
                        .as("Expected %d rows in %s for workspace %s", expected, table, wsId)
                        .isEqualTo(expected));
    }

}
