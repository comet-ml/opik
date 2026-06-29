package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueSeverity;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.ReactServiceErrorResponse;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.ClientSupportUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.AgentInsightsResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.sorting.Direction;
import com.comet.opik.api.sorting.SortableFields;
import com.comet.opik.api.sorting.SortingField;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.commons.lang3.RandomStringUtils;
import org.apache.commons.lang3.RandomUtils;
import org.apache.hc.core5.http.HttpStatus;
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
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Agent Insights Resource Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class AgentInsightsResourceTest {

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String FAKE_API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private static final String OTHER_API_KEY = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String OTHER_WORKSPACE = UUID.randomUUID().toString();

    private static final LocalDate DAY_1 = LocalDate.of(2026, 6, 1);
    private static final LocalDate DAY_2 = LocalDate.of(2026, 6, 2);
    private static final LocalDate DAY_3 = LocalDate.of(2026, 6, 3);

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, CLICKHOUSE_CONTAINER, MYSQL, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        DatabaseAnalyticsFactory databaseAnalyticsFactory = ClickHouseContainerUtils
                .newDatabaseAnalyticsFactory(CLICKHOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL);
        MigrationUtils.runClickhouseDbMigration(CLICKHOUSE_CONTAINER);

        APP = newTestDropwizardAppExtension(
                TestDropwizardAppExtensionUtils.AppContextConfig.builder()
                        .jdbcUrl(MYSQL.getJdbcUrl())
                        .databaseAnalyticsFactory(databaseAnalyticsFactory)
                        .redisUrl(REDIS.getRedisURI())
                        .runtimeInfo(wireMock.runtimeInfo())
                        .build());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private AgentInsightsResourceClient agentInsightsResourceClient;
    private ProjectResourceClient projectResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client) {
        String baseUrl = TestUtils.getBaseUrl(client);
        this.agentInsightsResourceClient = new AgentInsightsResourceClient(client);
        this.projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);

        ClientSupportUtils.config(client);

        AuthTestUtils.mockTargetWorkspace(wireMock.server(), API_KEY, TEST_WORKSPACE, WORKSPACE_ID, USER);
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE, OTHER_WORKSPACE_ID, USER);

        wireMock.server().stubFor(
                post(urlPathEqualTo("/opik/auth"))
                        .withHeader(HttpHeaders.AUTHORIZATION, equalTo(FAKE_API_KEY))
                        .withRequestBody(matchingJsonPath("$.workspaceName", matching(".+")))
                        .willReturn(WireMock.unauthorized().withHeader("Content-Type", "application/json")
                                .withJsonBody(JsonUtils.readTree(
                                        new ReactServiceErrorResponse("User not allowed to access workspace",
                                                401)))));
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private UUID createProject() {
        return projectResourceClient.createProject(UUID.randomUUID().toString(), API_KEY, TEST_WORKSPACE);
    }

    private AgentInsightsReport.ReportedIssue reportedIssue(String name, long count, long totalCount,
            long usersImpacted, long totalUsers) {
        return reportedIssue(null, name, count, totalCount, usersImpacted, totalUsers);
    }

    private AgentInsightsReport.ReportedIssue reportedIssue(UUID id, String name, long count, long totalCount,
            long usersImpacted, long totalUsers) {
        return AgentInsightsReport.ReportedIssue.builder()
                .id(id)
                .name(name)
                .description("Description of " + name)
                .cause("Cause of " + name)
                .suggestedFix("Fix for " + name)
                .tracesQuery("SELECT 1")
                .severity(AgentInsightsIssueSeverity.MEDIUM)
                .count(count)
                .totalCount(totalCount)
                .usersImpacted(usersImpacted)
                .totalUsers(totalUsers)
                .build();
    }

    private void report(UUID projectId, LocalDate reportDay, List<AgentInsightsReport.ReportedIssue> issues) {
        var reportRequest = AgentInsightsReport.builder()
                .projectId(projectId)
                .reportDay(reportDay)
                .issues(issues)
                .build();
        agentInsightsResourceClient.reportIssues(reportRequest, API_KEY, TEST_WORKSPACE,
                HttpStatus.SC_NO_CONTENT);
    }

    private AgentInsightsIssue.AgentInsightsIssuePage findIssues(UUID projectId, LocalDate fromDate,
            LocalDate toDate) {
        return agentInsightsResourceClient.findIssues(projectId, fromDate, toDate, null, null, null, null, null,
                API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
    }

    private static List<SortingField> sortBy(String field, Direction direction) {
        return List.of(SortingField.builder().field(field).direction(direction).build());
    }

    private static long rndOccurrences() {
        return RandomUtils.secure().randomLong(1L, 100L);
    }

    private static long rndTotalCount() {
        return RandomUtils.secure().randomLong(1L, 1000L);
    }

    private static long rndUserCount() {
        return RandomUtils.secure().randomLong(1L, 50L);
    }

    private static String rndName() {
        return RandomStringUtils.secure().nextAlphanumeric(10);
    }

    @Nested
    @DisplayName("Report Issues:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ReportIssues {

        @Test
        @DisplayName("Success: stored issues are returned by the list endpoint")
        void reportIssues() {
            var projectId = createProject();
            var nameA = rndName();
            var nameB = rndName();
            long occurrences = rndOccurrences(), totalCount = rndTotalCount(), impacted = rndUserCount(),
                    totalUsers = rndUserCount();

            report(projectId, DAY_1, List.of(
                    reportedIssue(nameA, occurrences, totalCount, impacted, totalUsers),
                    reportedIssue(nameB, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));

            var page = findIssues(projectId, DAY_1, DAY_1);

            var expectedIssueA = AgentInsightsIssue.builder()
                    .name(nameA)
                    .description("Description of " + nameA)
                    .cause("Cause of " + nameA)
                    .suggestedFix("Fix for " + nameA)
                    .status(AgentInsightsIssueStatus.OPEN)
                    .severity(AgentInsightsIssueSeverity.MEDIUM)
                    .tracesQuery("SELECT 1")
                    .totalOccurrences(occurrences)
                    .latestCount(occurrences)
                    .total(totalCount)
                    .usersImpacted(impacted)
                    .totalUsers(totalUsers)
                    .firstSeen(DAY_1)
                    .lastSeen(DAY_1)
                    .daysReported(1)
                    .createdBy(USER)
                    .lastUpdatedBy(USER)
                    .build();

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactlyInAnyOrder(nameA, nameB);
            assertThat(page.content())
                    .usingRecursiveFieldByFieldElementComparatorIgnoringFields("id", "createdAt", "lastUpdatedAt")
                    .contains(expectedIssueA);

            var issueA = page.content().stream()
                    .filter(issue -> issue.name().equals(nameA))
                    .findFirst()
                    .orElseThrow();
            assertThat(issueA.createdAt()).isNotNull();
            assertThat(issueA.lastUpdatedAt()).isNotNull();
        }

        @Test
        @DisplayName("All clear: an empty report is accepted and persists nothing")
        void reportIssuesWhenEmptyThenAcceptedAndNothingPersisted() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of());

            assertThat(findIssues(projectId, DAY_1, DAY_1).content()).isEmpty();
        }

        @Test
        @DisplayName("Idempotency: re-reporting with the same id replaces metrics instead of duplicating")
        void reportIssuesWhenSameDayReportedTwiceThenMetricsAreReplaced() {
            var projectId = createProject();
            var name = rndName();
            long occurrences = rndOccurrences(), totalCount = rndTotalCount(), impacted = rndUserCount(),
                    totalUsers = rndUserCount();

            report(projectId, DAY_1,
                    List.of(reportedIssue(name, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            report(projectId, DAY_1,
                    List.of(reportedIssue(issueId, name, occurrences, totalCount, impacted, totalUsers)));
            var secondPage = findIssues(projectId, DAY_1, DAY_1);

            assertThat(secondPage.total()).isEqualTo(1);
            var issue = secondPage.content().getFirst();
            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.totalOccurrences()).isEqualTo(occurrences);
            assertThat(issue.total()).isEqualTo(totalCount);
            assertThat(issue.usersImpacted()).isEqualTo(impacted);
            assertThat(issue.totalUsers()).isEqualTo(totalUsers);
            assertThat(issue.daysReported()).isEqualTo(1);
        }

        @Test
        @DisplayName("Re-reporting preserves user-owned status and issue id, but updates description")
        void reportIssuesWhenIssueAlreadyResolvedThenStatusIsPreserved() {
            var projectId = createProject();
            var name = rndName();
            var updatedDescription = rndName();

            report(projectId, DAY_1,
                    List.of(reportedIssue(name, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            agentInsightsResourceClient.updateStatus(issueId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.RESOLVED)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            report(projectId, DAY_2, List.of(
                    reportedIssue(issueId, name, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())
                            .toBuilder().description(updatedDescription).build()));

            var issue = findIssues(projectId, DAY_1, DAY_2).content().getFirst();
            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.status()).isEqualTo(AgentInsightsIssueStatus.RESOLVED);
            assertThat(issue.description()).isEqualTo(updatedDescription);
        }

        @Test
        @DisplayName("Multi-day issue exposes cross-day total and the latest day's count")
        void findIssuesWhenMultiDayThenTotalAndLatestCount() {
            var projectId = createProject();
            var name = rndName();
            long day1Count = 56L;
            long day2Count = 36L;

            report(projectId, DAY_1, List.of(reportedIssue(name, day1Count, 200L, 5L, 50L)));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();
            report(projectId, DAY_2, List.of(reportedIssue(issueId, name, day2Count, 192L, 4L, 40L)));

            var issue = findIssues(projectId, DAY_1, DAY_2).content().getFirst();
            // total_occurrences stays the cross-day sum (overall scale); latest_count is the most recent
            // day's number, which the issue prose describes — so the UI can render both without contradiction.
            assertThat(issue.totalOccurrences()).isEqualTo(day1Count + day2Count);
            assertThat(issue.latestCount()).isEqualTo(day2Count);
            assertThat(issue.daysReported()).isEqualTo(2);
            assertThat(issue.lastSeen()).isEqualTo(DAY_2);
            assertThat(issue.firstSeen()).isEqualTo(DAY_1);
        }

        @Test
        @DisplayName("Duplicate explicit issue ids in one request return 400")
        void reportIssuesWhenDuplicateIdsThenBadRequest() {
            var projectId = createProject();
            var sharedId = UUID.randomUUID();

            var reportRequest = AgentInsightsReport.builder()
                    .projectId(projectId)
                    .reportDay(DAY_1)
                    .issues(List.of(
                            reportedIssue(sharedId, rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                                    rndUserCount()),
                            reportedIssue(sharedId, rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                                    rndUserCount())))
                    .build();

            agentInsightsResourceClient.reportIssues(reportRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_BAD_REQUEST);
        }

        @Test
        @DisplayName("Cross-workspace: an issue is not accessible from another workspace")
        void getIssueWhenInAnotherWorkspaceThenNotFound() {
            var issueId = UUID.randomUUID();
            var name = rndName();

            var projectInWorkspace = createProject();
            report(projectInWorkspace, DAY_1, List.of(
                    reportedIssue(issueId, name, rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));

            var original = agentInsightsResourceClient.getIssue(issueId, projectInWorkspace, null, null,
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(original.name()).isEqualTo(name);

            var projectInOtherWorkspace = projectResourceClient.createProject(UUID.randomUUID().toString(),
                    OTHER_API_KEY, OTHER_WORKSPACE);
            agentInsightsResourceClient.getIssue(issueId, projectInOtherWorkspace, null, null,
                    OTHER_API_KEY, OTHER_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Cross-project: an issue is not accessible from another project of the same workspace")
        void getIssueWhenInAnotherProjectThenNotFound() {
            var issueIdX = UUID.randomUUID();
            var issueIdY = UUID.randomUUID();
            var nameX = rndName();
            var nameY = rndName();

            var projectX = createProject();
            var projectY = createProject();

            report(projectX, DAY_1, List.of(
                    reportedIssue(issueIdX, nameX, rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            report(projectY, DAY_1, List.of(
                    reportedIssue(issueIdY, nameY, rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));

            assertThat(agentInsightsResourceClient.getIssue(issueIdX, projectX, null, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK).name()).isEqualTo(nameX);
            assertThat(agentInsightsResourceClient.getIssue(issueIdY, projectY, null, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK).name()).isEqualTo(nameY);

            agentInsightsResourceClient.getIssue(issueIdX, projectY, null, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
            agentInsightsResourceClient.getIssue(issueIdY, projectX, null, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }

        @Test
        @DisplayName("Unknown project returns 404")
        void reportIssuesWhenProjectDoesNotExistThenNotFound() {
            var reportRequest = AgentInsightsReport.builder()
                    .projectId(UUID.randomUUID())
                    .reportDay(DAY_1)
                    .issues(List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())))
                    .build();

            agentInsightsResourceClient.reportIssues(reportRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        Stream<AgentInsightsReport> invalidReports() {
            var validIssue = reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                    rndUserCount());
            return Stream.of(
                    // missing report_day
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).issues(List.of(validIssue)).build(),
                    // missing project_id
                    AgentInsightsReport.builder().reportDay(DAY_1).issues(List.of(validIssue)).build(),
                    // blank issue name
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder().name(" ").build())).build(),
                    // name too long
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder().name("a".repeat(256)).build())).build(),
                    // negative count
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder().count(-1L).build())).build(),
                    // missing users_impacted
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder().usersImpacted(null).build())).build(),
                    // missing total_users
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder().totalUsers(null).build())).build(),
                    // null issue element
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(Collections.singletonList(null)).build(),
                    // missing severity (required field)
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder().severity(null).build())).build(),
                    // metadata exceeding the column byte limit
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1)
                            .issues(List.of(validIssue.toBuilder()
                                    .metadata(JsonUtils.getJsonNodeFromString(
                                            "{\"blob\":\"%s\"}".formatted("a".repeat(70_000))))
                                    .build()))
                            .build());
        }

        @ParameterizedTest
        @MethodSource("invalidReports")
        @DisplayName("Invalid payloads return 422")
        void reportIssuesWhenPayloadIsInvalidThenUnprocessableEntity(AgentInsightsReport reportRequest) {
            agentInsightsResourceClient.reportIssues(reportRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_UNPROCESSABLE_ENTITY);
        }

        @Test
        @DisplayName("Internal persistence endpoint requires authentication")
        void reportIssuesWhenApiKeyIsInvalidThenUnauthorized() {
            var reportRequest = AgentInsightsReport.builder()
                    .projectId(UUID.randomUUID())
                    .reportDay(DAY_1)
                    .issues(List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())))
                    .build();

            agentInsightsResourceClient.reportIssues(reportRequest, FAKE_API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_UNAUTHORIZED);
        }
    }

    @Nested
    @DisplayName("Find Issues:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class FindIssues {

        @Test
        @DisplayName("Only issues with details inside the window are returned, aggregates are window-bounded")
        void findIssuesWhenWindowIsRestrictedThenOnlyMatchingIssuesAndAggregates() {
            var projectId = createProject();
            var issueAId = UUID.randomUUID();
            var issueBId = UUID.randomUUID();
            var nameA = rndName();
            var nameB = rndName();

            long occurrences1 = rndOccurrences(), totalCount1 = rndTotalCount(), impacted1 = rndUserCount(),
                    users1 = rndUserCount();
            long occurrences2 = rndOccurrences(), totalCount2 = rndTotalCount(), impacted2 = rndUserCount(),
                    users2 = rndUserCount();
            long occurrences3 = rndOccurrences(), totalCount3 = rndTotalCount(), impacted3 = rndUserCount(),
                    users3 = rndUserCount();

            report(projectId, DAY_1, List.of(
                    reportedIssue(issueAId, nameA, occurrences1, totalCount1, impacted1, users1),
                    reportedIssue(issueBId, nameB, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));
            report(projectId, DAY_2,
                    List.of(reportedIssue(issueAId, nameA, occurrences2, totalCount2, impacted2, users2)));
            report(projectId, DAY_3,
                    List.of(reportedIssue(issueAId, nameA, occurrences3, totalCount3, impacted3, users3)));

            // full window: both issues, nameA aggregated over 3 days
            var fullPage = findIssues(projectId, DAY_1, DAY_3);
            assertThat(fullPage.total()).isEqualTo(2);
            var issueA = fullPage.content().stream()
                    .filter(issue -> issue.name().equals(nameA))
                    .findFirst()
                    .orElseThrow();
            assertThat(issueA.totalOccurrences()).isEqualTo(occurrences1 + occurrences2 + occurrences3);
            assertThat(issueA.total()).isEqualTo(totalCount1 + totalCount2 + totalCount3);
            assertThat(issueA.usersImpacted()).isEqualTo(impacted1 + impacted2 + impacted3);
            assertThat(issueA.totalUsers()).isEqualTo(users1 + users2 + users3);
            assertThat(issueA.firstSeen()).isEqualTo(DAY_1);
            assertThat(issueA.lastSeen()).isEqualTo(DAY_3);
            assertThat(issueA.daysReported()).isEqualTo(3);

            // restricted window: nameB has no details, aggregates only cover DAY_2..DAY_3
            var restrictedPage = findIssues(projectId, DAY_2, DAY_3);
            assertThat(restrictedPage.total()).isEqualTo(1);
            var restricted = restrictedPage.content().getFirst();
            assertThat(restricted.name()).isEqualTo(nameA);
            assertThat(restricted.totalOccurrences()).isEqualTo(occurrences2 + occurrences3);
            assertThat(restricted.total()).isEqualTo(totalCount2 + totalCount3);
            assertThat(restricted.usersImpacted()).isEqualTo(impacted2 + impacted3);
            assertThat(restricted.totalUsers()).isEqualTo(users2 + users3);
            assertThat(restricted.firstSeen()).isEqualTo(DAY_2);
            assertThat(restricted.lastSeen()).isEqualTo(DAY_3);
            assertThat(restricted.daysReported()).isEqualTo(2);
        }

        @Test
        @DisplayName("Omitting dates returns all issues across all history")
        void findIssuesWhenNoDatesProvidedThenAllIssuesReturned() {
            var projectId = createProject();
            var nameA = rndName();
            var nameB = rndName();

            report(projectId, DAY_1,
                    List.of(reportedIssue(nameA, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));
            report(projectId, DAY_3,
                    List.of(reportedIssue(nameB, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));

            var page = agentInsightsResourceClient.findIssues(projectId, null, null, null, null, null, null, null,
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(page.total()).isEqualTo(2);
            assertThat(page.content()).extracting(AgentInsightsIssue::name)
                    .containsExactlyInAnyOrder(nameA, nameB);
        }

        @Test
        @DisplayName("Default sorting is last seen DESC, sorting by total_occurrences sorts by occurrences DESC")
        void findIssuesSorting() {
            var projectId = createProject();
            var nameA = rndName();
            var nameB = rndName();
            var nameC = rndName();
            // nameA: most occurrences, seen earliest; nameC: fewest, seen latest
            long countC = RandomUtils.secure().randomLong(1L, 50L);
            long countB = RandomUtils.secure().randomLong(51L, 100L);
            long countA = RandomUtils.secure().randomLong(101L, 200L);
            long totalCount = rndTotalCount(), impacted = rndUserCount(), totalUsers = rndUserCount();

            report(projectId, DAY_1, List.of(reportedIssue(nameA, countA, totalCount, impacted, totalUsers)));
            report(projectId, DAY_2, List.of(reportedIssue(nameB, countB, totalCount, impacted, totalUsers)));
            report(projectId, DAY_3, List.of(reportedIssue(nameC, countC, totalCount, impacted, totalUsers)));

            var defaultPage = findIssues(projectId, DAY_1, DAY_3);
            assertThat(defaultPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly(nameC, nameB, nameA);

            var byOccurrences = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_3, null, null,
                    sortBy(SortableFields.TOTAL_OCCURRENCES, Direction.DESC), null, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(byOccurrences.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly(nameA, nameB, nameC);

            var byLastSeen = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_3, null, null,
                    sortBy(SortableFields.LAST_SEEN, Direction.DESC), null, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(byLastSeen.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly(nameC, nameB, nameA);
        }

        @Test
        @DisplayName("Status filter returns only issues with the requested status")
        void findIssuesWhenStatusFilterIsSetThenOnlyMatchingIssues() {
            var projectId = createProject();
            var nameA = rndName();
            var nameB = rndName();

            report(projectId, DAY_1, List.of(
                    reportedIssue(nameA, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount()),
                    reportedIssue(nameB, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())));

            var issueBId = findIssues(projectId, DAY_1, DAY_1).content().stream()
                    .filter(issue -> issue.name().equals(nameB))
                    .findFirst()
                    .orElseThrow()
                    .id();
            agentInsightsResourceClient.updateStatus(issueBId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.RESOLVED)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            var resolvedPage = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1,
                    AgentInsightsIssueStatus.RESOLVED, null, null, null, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(resolvedPage.total()).isEqualTo(1);
            assertThat(resolvedPage.content().getFirst().name()).isEqualTo(nameB);

            assertThat(findIssues(projectId, DAY_1, DAY_1).total()).isEqualTo(2);
        }

        @Test
        @DisplayName("Pagination honors page and size and reports the full total")
        void findIssuesPagination() {
            var projectId = createProject();
            var nameA = rndName();
            var nameB = rndName();
            var nameC = rndName();
            long countC = RandomUtils.secure().randomLong(1L, 50L);
            long countB = RandomUtils.secure().randomLong(51L, 100L);
            long countA = RandomUtils.secure().randomLong(101L, 200L);
            long totalCount = rndTotalCount(), impacted = rndUserCount(), totalUsers = rndUserCount();

            report(projectId, DAY_1, List.of(
                    reportedIssue(nameA, countA, totalCount, impacted, totalUsers),
                    reportedIssue(nameB, countB, totalCount, impacted, totalUsers),
                    reportedIssue(nameC, countC, totalCount, impacted, totalUsers)));

            var firstPage = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null, null,
                    sortBy(SortableFields.TOTAL_OCCURRENCES, Direction.DESC), 1, 2, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(firstPage.total()).isEqualTo(3);
            assertThat(firstPage.size()).isEqualTo(2);
            assertThat(firstPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly(nameA, nameB);

            var secondPage = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null, null,
                    sortBy(SortableFields.TOTAL_OCCURRENCES, Direction.DESC), 2, 2, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(secondPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly(nameC);
        }

        @Test
        @DisplayName("Workspace isolation: issues are invisible from another workspace")
        void findIssuesWhenOtherWorkspaceThenIssuesAreNotVisible() {
            var projectId = createProject();

            report(projectId, DAY_1,
                    List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));

            var page = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null, null, null, null, null,
                    OTHER_API_KEY, OTHER_WORKSPACE, HttpStatus.SC_OK);
            assertThat(page.total()).isZero();
            assertThat(page.content()).isEmpty();
        }

        Stream<Arguments> invalidFindParams() {
            return Stream.of(
                    Arguments.of("2026-06-01", "2026-06-03", "unknown-status", null),
                    Arguments.of("2026-06-01", "2026-06-03", null, "unknown-sort"),
                    Arguments.of("not-a-date", "2026-06-03", null, null),
                    Arguments.of("2026-06-03", "2026-06-01", null, null)); // from_date after to_date
        }

        @ParameterizedTest
        @MethodSource("invalidFindParams")
        @DisplayName("Invalid query params return 400")
        void findIssuesWhenQueryParamsAreInvalidThenBadRequest(String fromDate, String toDate, String status,
                String sorting) {
            try (var response = agentInsightsResourceClient.findIssuesWithResponse(UUID.randomUUID(), fromDate,
                    toDate, status, null, sorting, null, null, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        Stream<Arguments> outOfBoundsPagination() {
            return Stream.of(
                    Arguments.of(0, 10), // page below min
                    Arguments.of(1, 0), // size below min
                    Arguments.of(1, 101)); // size above max
        }

        @ParameterizedTest
        @MethodSource("outOfBoundsPagination")
        @DisplayName("Out-of-bounds page or size returns 400")
        void findIssuesWhenPaginationOutOfBoundsThenBadRequest(int page, int size) {
            try (var response = agentInsightsResourceClient.findIssuesWithResponse(UUID.randomUUID(), null, null,
                    null, null, null, page, size, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }

        @Test
        @DisplayName("Missing required project_id returns 400")
        void findIssuesWhenProjectIdMissingThenBadRequest() {
            try (var response = agentInsightsResourceClient.findIssuesWithResponse(null, null, null, null, null,
                    null, null, null, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    @Nested
    @DisplayName("Get Issue By Id:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class GetIssueById {

        @Test
        @DisplayName("Success: returns the issue header with the per-day breakdown ordered ascending")
        void getIssueById() {
            var projectId = createProject();
            var issueAId = UUID.randomUUID();
            var name = rndName();
            var metadata = JsonUtils.getJsonNodeFromString("{\"sample_trace_ids\":[\"abc\",\"def\"]}");
            long occurrences1 = rndOccurrences(), totalCount1 = rndTotalCount(), impacted1 = rndUserCount(),
                    users1 = rndUserCount();
            long occurrences2 = rndOccurrences(), totalCount2 = rndTotalCount(), impacted2 = rndUserCount(),
                    users2 = rndUserCount();
            long occurrences3 = rndOccurrences(), totalCount3 = rndTotalCount(), impacted3 = rndUserCount(),
                    users3 = rndUserCount();

            report(projectId, DAY_2,
                    List.of(reportedIssue(issueAId, name, occurrences2, totalCount2, impacted2, users2)));
            report(projectId, DAY_1, List.of(
                    reportedIssue(issueAId, name, occurrences1, totalCount1, impacted1, users1).toBuilder()
                            .metadata(metadata).build()));
            report(projectId, DAY_3,
                    List.of(reportedIssue(issueAId, name, occurrences3, totalCount3, impacted3, users3)));

            var issueId = findIssues(projectId, DAY_1, DAY_3).content().getFirst().id();
            var issue = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_3, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.name()).isEqualTo(name);
            assertThat(issue.status()).isEqualTo(AgentInsightsIssueStatus.OPEN);
            assertThat(issue.details()).hasSize(3);
            assertThat(issue.details())
                    .extracting(detail -> detail.reportDay())
                    .containsExactly(DAY_1, DAY_2, DAY_3);

            var firstDetail = issue.details().getFirst();
            assertThat(firstDetail.count()).isEqualTo(occurrences1);
            assertThat(firstDetail.totalCount()).isEqualTo(totalCount1);
            assertThat(firstDetail.usersImpacted()).isEqualTo(impacted1);
            assertThat(firstDetail.totalUsers()).isEqualTo(users1);
            assertThat(firstDetail.metadata()).isEqualTo(metadata);

            // window restricted to a single day
            var restricted = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_2, DAY_2, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(restricted.details()).hasSize(1);
            assertThat(restricted.details().getFirst().reportDay()).isEqualTo(DAY_2);
        }

        @Test
        @DisplayName("Omitting dates returns all available details")
        void getIssueByIdWhenNoDatesProvidedThenAllDetailsReturned() {
            var projectId = createProject();
            var issueAId = UUID.randomUUID();
            var name = rndName();

            report(projectId, DAY_1,
                    List.of(reportedIssue(issueAId, name, rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            report(projectId, DAY_2,
                    List.of(reportedIssue(issueAId, name, rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            report(projectId, DAY_3,
                    List.of(reportedIssue(issueAId, name, rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));

            var issueId = findIssues(projectId, DAY_1, DAY_3).content().getFirst().id();
            var issue = agentInsightsResourceClient.getIssue(issueId, projectId, null, null, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.details()).hasSize(3);
        }

        @Test
        @DisplayName("Issue exists but has no details in the window: returns 200 with empty details")
        void getIssueByIdWhenNoDetailsInWindowThenEmptyDetails() {
            var projectId = createProject();

            report(projectId, DAY_1,
                    List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            var issue = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_2, DAY_3, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.details()).isEmpty();
        }

        @Test
        @DisplayName("Unknown issue, mismatched project or foreign workspace return 404")
        void getIssueByIdWhenNotAccessibleThenNotFound() {
            var projectId = createProject();

            report(projectId, DAY_1,
                    List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            agentInsightsResourceClient.getIssue(UUID.randomUUID(), projectId, DAY_1, DAY_1, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
            agentInsightsResourceClient.getIssue(issueId, UUID.randomUUID(), DAY_1, DAY_1, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_NOT_FOUND);
            agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_1, OTHER_API_KEY,
                    OTHER_WORKSPACE, HttpStatus.SC_NOT_FOUND);
        }
    }

    @Nested
    @DisplayName("Update Issue Status:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class UpdateIssueStatus {

        @Test
        @DisplayName("Success: status change is visible on subsequent reads")
        void updateIssueStatus() {
            var projectId = createProject();

            report(projectId, DAY_1,
                    List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            agentInsightsResourceClient.updateStatus(issueId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.CLOSED)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            assertThat(agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_1, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK).status()).isEqualTo(AgentInsightsIssueStatus.CLOSED);

            agentInsightsResourceClient.updateStatus(issueId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.OPEN)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            assertThat(agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_1, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK).status()).isEqualTo(AgentInsightsIssueStatus.OPEN);
        }

        @Test
        @DisplayName("Unknown issue, mismatched project or foreign workspace return 404")
        void updateIssueStatusWhenNotAccessibleThenNotFound() {
            var projectId = createProject();

            report(projectId, DAY_1,
                    List.of(reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(),
                            rndUserCount())));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            var update = AgentInsightsIssueUpdate.builder()
                    .projectId(projectId)
                    .status(AgentInsightsIssueStatus.RESOLVED)
                    .build();

            agentInsightsResourceClient.updateStatus(UUID.randomUUID(), update, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
            agentInsightsResourceClient.updateStatus(issueId,
                    update.toBuilder().projectId(UUID.randomUUID()).build(), API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
            agentInsightsResourceClient.updateStatus(issueId, update, OTHER_API_KEY, OTHER_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        Stream<Arguments> invalidStatusUpdatePayloads() {
            return Stream.of(
                    Arguments.of("{\"project_id\":\"%s\",\"status\":\"unknown\"}".formatted(UUID.randomUUID()),
                            HttpStatus.SC_BAD_REQUEST),
                    Arguments.of("{\"status\":\"resolved\"}", HttpStatus.SC_UNPROCESSABLE_ENTITY));
        }

        @ParameterizedTest
        @MethodSource("invalidStatusUpdatePayloads")
        @DisplayName("Invalid status value returns 400, missing project_id returns 422")
        void updateIssueStatusWhenPayloadIsInvalidThenClientError(String body, int expectedStatus) {
            try (var response = agentInsightsResourceClient.updateStatusWithResponse(UUID.randomUUID(), body,
                    API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(expectedStatus);
            }
        }
    }

    @Nested
    @DisplayName("Severity Behavior:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class SeverityBehavior {

        @Test
        @DisplayName("Reported severity is returned in both the list and detail responses")
        void severityIsStoredAndReturnedInListAndDetailResponse() {
            var projectId = createProject();
            var name = rndName();

            report(projectId, DAY_1, List.of(
                    reportedIssue(name, rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())
                            .toBuilder().severity(AgentInsightsIssueSeverity.CRITICAL).build()));

            var page = findIssues(projectId, DAY_1, DAY_1);
            assertThat(page.total()).isEqualTo(1);
            assertThat(page.content().getFirst().severity()).isEqualTo(AgentInsightsIssueSeverity.CRITICAL);

            var issueId = page.content().getFirst().id();
            var detail = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_1,
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(detail.severity()).isEqualTo(AgentInsightsIssueSeverity.CRITICAL);
        }

        Stream<Arguments> severityFilterCases() {
            return Stream.of(
                    Arguments.of(AgentInsightsIssueSeverity.CRITICAL, 1),
                    Arguments.of(AgentInsightsIssueSeverity.HIGH, 1),
                    // edge: LOW was never reported — filter must return zero results, not an error
                    Arguments.of(AgentInsightsIssueSeverity.LOW, 0));
        }

        @ParameterizedTest
        @MethodSource("severityFilterCases")
        @DisplayName("Severity filter returns only issues with the matching severity")
        void findIssuesWhenSeverityFilterIsSetThenOnlyMatchingIssues(
                AgentInsightsIssueSeverity filterSeverity, int expectedCount) {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(
                    reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())
                            .toBuilder().severity(AgentInsightsIssueSeverity.CRITICAL).build(),
                    reportedIssue(rndName(), rndOccurrences(), rndTotalCount(), rndUserCount(), rndUserCount())
                            .toBuilder().severity(AgentInsightsIssueSeverity.HIGH).build()));

            var page = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null,
                    filterSeverity, null, null, null, API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(page.total()).isEqualTo(expectedCount);
        }

        @Test
        @DisplayName("Sorting by severity ASC places critical (highest priority) first")
        void findIssuesSortBySeverityAscGivesCriticalFirst() {
            var projectId = createProject();
            var nameLow = rndName();
            var nameCritical = rndName();
            var nameMedium = rndName();
            long totalCount = rndTotalCount(), impacted = rndUserCount(), totalUsers = rndUserCount();

            report(projectId, DAY_1, List.of(
                    reportedIssue(nameLow, rndOccurrences(), totalCount, impacted, totalUsers)
                            .toBuilder().severity(AgentInsightsIssueSeverity.LOW).build(),
                    reportedIssue(nameCritical, rndOccurrences(), totalCount, impacted, totalUsers)
                            .toBuilder().severity(AgentInsightsIssueSeverity.CRITICAL).build(),
                    reportedIssue(nameMedium, rndOccurrences(), totalCount, impacted, totalUsers)
                            .toBuilder().severity(AgentInsightsIssueSeverity.MEDIUM).build()));

            var ascPage = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null, null,
                    sortBy(SortableFields.SEVERITY, Direction.ASC), null, null, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_OK);
            assertThat(ascPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly(nameCritical, nameMedium, nameLow);
        }

        @Test
        @DisplayName("Unknown severity query param value returns 400")
        void findIssuesWhenUnknownSeverityParamThenBadRequest() {
            try (var response = agentInsightsResourceClient.findIssuesWithResponse(UUID.randomUUID(), null, null,
                    null, "unknown-severity", null, null, null, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }
        }
    }

    @Nested
    @DisplayName("Date Range Validation:")
    class DateRangeValidation {

        @Test
        @DisplayName("List endpoint: from_date after to_date returns 400")
        void findIssuesWhenFromDateAfterToDateThenBadRequest() {
            var projectId = createProject();
            // from_date strictly after to_date is the only ordering the boundary validation rejects
            var fromDate = DAY_2;
            var toDate = DAY_1;

            agentInsightsResourceClient.findIssues(projectId, fromDate, toDate, null, null, null, null, null,
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_BAD_REQUEST);
        }

        @Test
        @DisplayName("Detail endpoint: from_date after to_date returns 400")
        void getIssueWhenFromDateAfterToDateThenBadRequest() {
            var projectId = createProject();
            // from_date strictly after to_date is the only ordering the boundary validation rejects
            var fromDate = DAY_2;
            var toDate = DAY_1;

            // validation runs before the issue lookup, so a non-existent id still yields 400, not 404
            agentInsightsResourceClient.getIssue(UUID.randomUUID(), projectId, fromDate, toDate,
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_BAD_REQUEST);
        }
    }
}
