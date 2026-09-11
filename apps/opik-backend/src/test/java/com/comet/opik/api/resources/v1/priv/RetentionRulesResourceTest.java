package com.comet.opik.api.resources.v1.priv;

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
import com.comet.opik.api.retention.RetentionLevel;
import com.comet.opik.api.retention.RetentionPeriod;
import com.comet.opik.api.retention.RetentionRule;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.redis.testcontainers.RedisContainer;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
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
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;

import java.util.UUID;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(DropwizardAppExtensionProvider.class)
class RetentionRulesResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

    private static final String OTHER_API_KEY = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_NAME = "workspace" + RandomStringUtils.secure().nextAlphanumeric(36);
    private static final String OTHER_USER = "user-" + RandomStringUtils.secure().nextAlphanumeric(36);

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
                .authCacheTtlInSeconds(null)
                .build();

        APP = newTestDropwizardAppExtension(contextConfig);
    }

    private String baseURI;
    private RetentionRuleResourceClient retentionClient;

    @BeforeAll
    void beforeAll(ClientSupport client) {
        this.baseURI = TestUtils.getBaseUrl(client);
        ClientSupportUtils.config(client);

        this.retentionClient = new RetentionRuleResourceClient(client, baseURI);

        mockTargetWorkspace(API_KEY, TEST_WORKSPACE_NAME, WORKSPACE_ID, USER);
        mockTargetWorkspace(OTHER_API_KEY, OTHER_WORKSPACE_NAME, OTHER_WORKSPACE_ID, OTHER_USER);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @Nested
    @DisplayName("Create retention rule")
    class CreateRetentionRule {

        @Test
        @DisplayName("Create workspace-level rule")
        void createWorkspaceRule() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();

            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.id()).isNotNull();
            assertThat(created.workspaceId()).isEqualTo(WORKSPACE_ID);
            assertThat(created.projectId()).isNull();
            assertThat(created.level()).isEqualTo(RetentionLevel.WORKSPACE);
            assertThat(created.retention()).isEqualTo(RetentionPeriod.BASE_60D);
            assertThat(created.applyToPast()).isTrue();
            assertThat(created.enabled()).isTrue();
            assertThat(created.createdBy()).isEqualTo(USER);
            assertThat(created.lastUpdatedBy()).isEqualTo(USER);
            assertThat(created.createdAt()).isNotNull();
            assertThat(created.lastUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("Create project-level rule")
        void createProjectRule() {
            UUID projectId = UUID.randomUUID();
            var rule = retentionClient.buildProjectRule(projectId, RetentionPeriod.SHORT_14D).build();

            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.projectId()).isEqualTo(projectId);
            assertThat(created.level()).isEqualTo(RetentionLevel.PROJECT);
            assertThat(created.retention()).isEqualTo(RetentionPeriod.SHORT_14D);
            assertThat(created.enabled()).isTrue();
        }

        @Test
        @DisplayName("Create organization-level rule")
        void createOrganizationRule() {
            var rule = retentionClient.buildOrganizationRule(RetentionPeriod.BASE_60D).build();

            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.projectId()).isNull();
            assertThat(created.level()).isEqualTo(RetentionLevel.ORGANIZATION);
            assertThat(created.retention()).isEqualTo(RetentionPeriod.BASE_60D);
            assertThat(created.enabled()).isTrue();
        }

        @Test
        @DisplayName("Create rule with apply_to_past=true")
        void createRuleWithApplyToPast() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.EXTENDED_400D)
                    .applyToPast(true)
                    .build();

            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.applyToPast()).isTrue();
            assertThat(created.retention()).isEqualTo(RetentionPeriod.EXTENDED_400D);
        }

        @Test
        @DisplayName("Create rule with unlimited retention")
        void createUnlimitedRule() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.UNLIMITED).build();

            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created.retention()).isEqualTo(RetentionPeriod.UNLIMITED);
        }

        @Test
        @DisplayName("Create rule without retention fails validation")
        void createRuleWithoutRetentionFails() {
            var rule = RetentionRule.builder().build();

            try (var response = retentionClient.callCreate(rule, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }

        @Test
        @DisplayName("Create organization_level=true with project_id fails validation")
        void createOrgLevelWithProjectIdFails() {
            var rule = RetentionRule.builder()
                    .organizationLevel(true)
                    .projectId(UUID.randomUUID())
                    .retention(RetentionPeriod.BASE_60D)
                    .build();

            try (var response = retentionClient.callCreate(rule, API_KEY, TEST_WORKSPACE_NAME)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("Creating new workspace rule auto-deactivates previous workspace rule")
        void createRuleAutoDeactivatesPrevious() {
            var rule1 = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            var created1 = retentionClient.createAndGet(rule1, API_KEY, TEST_WORKSPACE_NAME);
            assertThat(created1.enabled()).isTrue();

            // Create a second workspace rule — should auto-deactivate rule1
            var rule2 = retentionClient.buildWorkspaceRule(RetentionPeriod.EXTENDED_400D).build();
            var created2 = retentionClient.createAndGet(rule2, API_KEY, TEST_WORKSPACE_NAME);
            assertThat(created2.enabled()).isTrue();

            // Verify the first rule is now deactivated
            var fetched1 = retentionClient.get(created1.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetched1.enabled()).isFalse();
        }

        @Test
        @DisplayName("Creating project rule does not deactivate workspace rule")
        void createProjectRuleDoesNotDeactivateWorkspaceRule() {
            var wsRule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            var createdWs = retentionClient.createAndGet(wsRule, API_KEY, TEST_WORKSPACE_NAME);

            UUID projectId = UUID.randomUUID();
            var projRule = retentionClient.buildProjectRule(projectId, RetentionPeriod.SHORT_14D).build();
            retentionClient.createAndGet(projRule, API_KEY, TEST_WORKSPACE_NAME);

            // Workspace rule should still be active
            var fetchedWs = retentionClient.get(createdWs.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedWs.enabled()).isTrue();
        }

        @Test
        @DisplayName("Organization rule and workspace rule coexist")
        void orgAndWorkspaceRulesCoexist() {
            var orgRule = retentionClient.buildOrganizationRule(RetentionPeriod.BASE_60D).build();
            var createdOrg = retentionClient.createAndGet(orgRule, API_KEY, TEST_WORKSPACE_NAME);

            var wsRule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            var createdWs = retentionClient.createAndGet(wsRule, API_KEY, TEST_WORKSPACE_NAME);

            // Both should be active
            var fetchedOrg = retentionClient.get(createdOrg.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedOrg.enabled()).isTrue();
            assertThat(fetchedOrg.level()).isEqualTo(RetentionLevel.ORGANIZATION);

            var fetchedWs = retentionClient.get(createdWs.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedWs.enabled()).isTrue();
            assertThat(fetchedWs.level()).isEqualTo(RetentionLevel.WORKSPACE);
        }

        @Test
        @DisplayName("New organization rule auto-deactivates previous organization rule")
        void newOrgRuleDeactivatesPreviousOrgRule() {
            var orgRule1 = retentionClient.buildOrganizationRule(RetentionPeriod.BASE_60D).build();
            var createdOrg1 = retentionClient.createAndGet(orgRule1, API_KEY, TEST_WORKSPACE_NAME);

            var orgRule2 = retentionClient.buildOrganizationRule(RetentionPeriod.EXTENDED_400D).build();
            var createdOrg2 = retentionClient.createAndGet(orgRule2, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(createdOrg2.enabled()).isTrue();

            var fetchedOrg1 = retentionClient.get(createdOrg1.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedOrg1.enabled()).isFalse();
        }

        @Test
        @DisplayName("Creating workspace rule does not deactivate organization rule")
        void workspaceRuleDoesNotDeactivateOrgRule() {
            var orgRule = retentionClient.buildOrganizationRule(RetentionPeriod.BASE_60D).build();
            var createdOrg = retentionClient.createAndGet(orgRule, API_KEY, TEST_WORKSPACE_NAME);

            var wsRule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            retentionClient.createAndGet(wsRule, API_KEY, TEST_WORKSPACE_NAME);

            // Org rule should still be active
            var fetchedOrg = retentionClient.get(createdOrg.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedOrg.enabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Get retention rule")
    class GetRetentionRule {

        @Test
        @DisplayName("Get rule by id")
        void getRuleById() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            var fetched = retentionClient.get(created.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);

            assertThat(fetched.id()).isEqualTo(created.id());
            assertThat(fetched.retention()).isEqualTo(created.retention());
            assertThat(fetched.level()).isEqualTo(RetentionLevel.WORKSPACE);
        }

        @Test
        @DisplayName("Get non-existent rule returns 404")
        void getNonExistentRule() {
            retentionClient.get(UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Get rule from another workspace returns 404")
        void getRuleFromAnotherWorkspace() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            // Try to fetch from a different workspace
            retentionClient.get(created.id(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Find retention rules")
    class FindRetentionRules {

        @Test
        @DisplayName("Find returns only active rules by default")
        void findActiveOnly() {
            // Create and then deactivate a rule
            var rule1 = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            retentionClient.createAndGet(rule1, API_KEY, TEST_WORKSPACE_NAME);

            // Create a second one (deactivates rule1)
            var rule2 = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            retentionClient.createAndGet(rule2, API_KEY, TEST_WORKSPACE_NAME);

            var page = retentionClient.find(API_KEY, TEST_WORKSPACE_NAME, 1, 100, false, HttpStatus.SC_OK);

            // All returned rules should be active
            assertThat(page.content()).isNotEmpty();
            assertThat(page.content()).allMatch(RetentionRule::enabled);
        }

        @Test
        @DisplayName("Find with include_inactive returns all rules")
        void findIncludingInactive() {
            // Create two workspace rules (second auto-deactivates first)
            var rule1 = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            var created1 = retentionClient.createAndGet(rule1, API_KEY, TEST_WORKSPACE_NAME);

            var rule2 = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            retentionClient.createAndGet(rule2, API_KEY, TEST_WORKSPACE_NAME);

            var page = retentionClient.find(API_KEY, TEST_WORKSPACE_NAME, 1, 100, true, HttpStatus.SC_OK);

            // Should include both active and inactive rules
            assertThat(page.total()).isGreaterThanOrEqualTo(2);
            var ids = page.content().stream().map(RetentionRule::id).toList();
            assertThat(ids).contains(created1.id());
        }

        @Test
        @DisplayName("Find with pagination")
        void findWithPagination() {
            // Create several project rules (each with a unique project, so they don't deactivate each other)
            for (int i = 0; i < 3; i++) {
                var rule = retentionClient.buildProjectRule(UUID.randomUUID(), RetentionPeriod.BASE_60D).build();
                retentionClient.create(rule, API_KEY, TEST_WORKSPACE_NAME);
            }

            var page1 = retentionClient.find(API_KEY, TEST_WORKSPACE_NAME, 1, 2, false, HttpStatus.SC_OK);
            assertThat(page1.content().size()).isLessThanOrEqualTo(2);
            assertThat(page1.page()).isEqualTo(1);
        }

        @Test
        @DisplayName("Find returns empty for workspace with no rules")
        void findEmptyWorkspace() {
            var page = retentionClient.find(OTHER_API_KEY, OTHER_WORKSPACE_NAME, 1, 10, false, HttpStatus.SC_OK);

            assertThat(page.content()).isEmpty();
            assertThat(page.total()).isZero();
        }
    }

    @Nested
    @DisplayName("Deactivate retention rule")
    class DeactivateRetentionRule {

        @Test
        @DisplayName("Deactivate rule")
        void deactivateRule() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);
            assertThat(created.enabled()).isTrue();

            retentionClient.deactivate(created.id(), API_KEY, TEST_WORKSPACE_NAME);

            var fetched = retentionClient.get(created.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetched.enabled()).isFalse();
        }

        @Test
        @DisplayName("Deactivate already-deactivated rule is idempotent")
        void deactivateAlreadyDeactivated() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            retentionClient.deactivate(created.id(), API_KEY, TEST_WORKSPACE_NAME);
            // Second deactivation should also succeed (idempotent)
            retentionClient.deactivate(created.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NO_CONTENT);
        }

        @Test
        @DisplayName("Deactivate non-existent rule returns 404")
        void deactivateNonExistent() {
            retentionClient.deactivate(UUID.randomUUID(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Deactivate rule from another workspace returns 404")
        void deactivateFromAnotherWorkspace() {
            var rule = retentionClient.buildWorkspaceRule(RetentionPeriod.BASE_60D).build();
            var created = retentionClient.createAndGet(rule, API_KEY, TEST_WORKSPACE_NAME);

            retentionClient.deactivate(created.id(), OTHER_API_KEY, OTHER_WORKSPACE_NAME, HttpStatus.SC_NOT_FOUND);

            // Original rule should still be active
            var fetched = retentionClient.get(created.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetched.enabled()).isTrue();
        }
    }

    @Nested
    @DisplayName("Revert and fallback scenarios")
    class RevertScenario {

        @Test
        @DisplayName("Deactivate current rule then create new one")
        void deactivateThenCreateNew() {
            var rule1 = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            var created1 = retentionClient.createAndGet(rule1, API_KEY, TEST_WORKSPACE_NAME);

            retentionClient.deactivate(created1.id(), API_KEY, TEST_WORKSPACE_NAME);

            var rule2 = retentionClient.buildWorkspaceRule(RetentionPeriod.UNLIMITED).build();
            var created2 = retentionClient.createAndGet(rule2, API_KEY, TEST_WORKSPACE_NAME);

            assertThat(created2.enabled()).isTrue();
            assertThat(created2.retention()).isEqualTo(RetentionPeriod.UNLIMITED);

            var fetched1 = retentionClient.get(created1.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetched1.enabled()).isFalse();
        }

        @Test
        @DisplayName("Deactivating workspace rule leaves organization rule active as fallback")
        void deactivateWorkspaceRuleFallsBackToOrgRule() {
            // Set org-level rule
            var orgRule = retentionClient.buildOrganizationRule(RetentionPeriod.BASE_60D).build();
            var createdOrg = retentionClient.createAndGet(orgRule, API_KEY, TEST_WORKSPACE_NAME);

            // Set workspace-level override
            var wsRule = retentionClient.buildWorkspaceRule(RetentionPeriod.SHORT_14D).build();
            var createdWs = retentionClient.createAndGet(wsRule, API_KEY, TEST_WORKSPACE_NAME);

            // Both active
            assertThat(retentionClient.get(createdOrg.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK).enabled())
                    .isTrue();
            assertThat(retentionClient.get(createdWs.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK).enabled())
                    .isTrue();

            // Deactivate workspace rule — org rule is still there as fallback
            retentionClient.deactivate(createdWs.id(), API_KEY, TEST_WORKSPACE_NAME);

            var fetchedOrg = retentionClient.get(createdOrg.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedOrg.enabled()).isTrue();
            assertThat(fetchedOrg.level()).isEqualTo(RetentionLevel.ORGANIZATION);

            var fetchedWs = retentionClient.get(createdWs.id(), API_KEY, TEST_WORKSPACE_NAME, HttpStatus.SC_OK);
            assertThat(fetchedWs.enabled()).isFalse();
        }
    }
}
