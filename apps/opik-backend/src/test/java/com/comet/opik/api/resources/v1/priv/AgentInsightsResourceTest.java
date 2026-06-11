package com.comet.opik.api.resources.v1.priv;

import com.comet.opik.api.AgentInsightsIssue;
import com.comet.opik.api.AgentInsightsIssueStatus;
import com.comet.opik.api.AgentInsightsIssueUpdate;
import com.comet.opik.api.AgentInsightsReport;
import com.comet.opik.api.AgentInsightsSortBy;
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
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.core.HttpHeaders;
import org.apache.hc.core5.http.HttpStatus;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.clickhouse.ClickHouseContainer;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.lifecycle.Startables;
import org.testcontainers.mysql.MySQLContainer;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import uk.co.jemos.podam.api.PodamFactory;

import java.time.LocalDate;
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
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), OTHER_API_KEY, OTHER_WORKSPACE, OTHER_WORKSPACE_ID,
                USER);

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
        return AgentInsightsReport.ReportedIssue.builder()
                .name(name)
                .description("Description of " + name)
                .query("SELECT 1")
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
        return agentInsightsResourceClient.findIssues(projectId, fromDate, toDate, null, null, null, null,
                API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
    }

    @Nested
    @DisplayName("Report Issues:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class ReportIssues {

        @Test
        @DisplayName("Success: stored issues are returned by the list endpoint")
        void reportIssues() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(
                    reportedIssue("issue-a", 10, 100, 3, 40),
                    reportedIssue("issue-b", 5, 100, 1, 10)));

            var page = findIssues(projectId, DAY_1, DAY_1);

            assertThat(page.total()).isEqualTo(2);
            assertThat(page.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactlyInAnyOrder("issue-a", "issue-b");

            var issueA = page.content().stream()
                    .filter(issue -> issue.name().equals("issue-a"))
                    .findFirst()
                    .orElseThrow();
            assertThat(issueA.description()).isEqualTo("Description of issue-a");
            assertThat(issueA.query()).isEqualTo("SELECT 1");
            assertThat(issueA.status()).isEqualTo(AgentInsightsIssueStatus.OPEN);
            assertThat(issueA.totalOccurrences()).isEqualTo(10);
            assertThat(issueA.total()).isEqualTo(100);
            assertThat(issueA.usersImpacted()).isEqualTo(3);
            assertThat(issueA.totalUsers()).isEqualTo(40);
            assertThat(issueA.firstSeen()).isEqualTo(DAY_1);
            assertThat(issueA.lastSeen()).isEqualTo(DAY_1);
            assertThat(issueA.daysReported()).isEqualTo(1);
            assertThat(issueA.createdBy()).isEqualTo(USER);
        }

        @Test
        @DisplayName("Idempotency: re-reporting the same day replaces metrics instead of duplicating")
        void reportIssuesWhenSameDayReportedTwiceThenMetricsAreReplaced() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
            var firstPage = findIssues(projectId, DAY_1, DAY_1);

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 20, 200, 6, 60)));
            var secondPage = findIssues(projectId, DAY_1, DAY_1);

            assertThat(secondPage.total()).isEqualTo(1);
            var issue = secondPage.content().getFirst();
            assertThat(issue.id()).isEqualTo(firstPage.content().getFirst().id());
            assertThat(issue.totalOccurrences()).isEqualTo(20);
            assertThat(issue.total()).isEqualTo(200);
            assertThat(issue.usersImpacted()).isEqualTo(6);
            assertThat(issue.totalUsers()).isEqualTo(60);
            assertThat(issue.daysReported()).isEqualTo(1);
        }

        @Test
        @DisplayName("Re-reporting preserves user-owned status and issue id, but updates description")
        void reportIssuesWhenIssueAlreadyResolvedThenStatusIsPreserved() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            agentInsightsResourceClient.updateStatus(issueId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.RESOLVED)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            report(projectId, DAY_2, List.of(
                    reportedIssue("issue-a", 7, 50, 2, 20).toBuilder().description("updated description").build()));

            var issue = findIssues(projectId, DAY_1, DAY_2).content().getFirst();
            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.status()).isEqualTo(AgentInsightsIssueStatus.RESOLVED);
            assertThat(issue.description()).isEqualTo("updated description");
        }

        @Test
        @DisplayName("Duplicate issue names in one request return 400")
        void reportIssuesWhenDuplicateNamesThenBadRequest() {
            var projectId = createProject();

            var reportRequest = AgentInsightsReport.builder()
                    .projectId(projectId)
                    .reportDay(DAY_1)
                    .issues(List.of(
                            reportedIssue("issue-a", 1, 10, 1, 10),
                            reportedIssue("issue-a", 2, 10, 1, 10)))
                    .build();

            agentInsightsResourceClient.reportIssues(reportRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_BAD_REQUEST);
        }

        @Test
        @DisplayName("Unknown project returns 404")
        void reportIssuesWhenProjectDoesNotExistThenNotFound() {
            var reportRequest = AgentInsightsReport.builder()
                    .projectId(UUID.randomUUID())
                    .reportDay(DAY_1)
                    .issues(List.of(reportedIssue("issue-a", 1, 10, 1, 10)))
                    .build();

            agentInsightsResourceClient.reportIssues(reportRequest, API_KEY, TEST_WORKSPACE,
                    HttpStatus.SC_NOT_FOUND);
        }

        Stream<AgentInsightsReport> invalidReports() {
            var validIssue = reportedIssue("issue-a", 1, 10, 1, 10);
            return Stream.of(
                    // missing report_day
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).issues(List.of(validIssue)).build(),
                    // missing project_id
                    AgentInsightsReport.builder().reportDay(DAY_1).issues(List.of(validIssue)).build(),
                    // empty issues
                    AgentInsightsReport.builder().projectId(UUID.randomUUID()).reportDay(DAY_1).issues(List.of())
                            .build(),
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
                            .issues(List.of(validIssue.toBuilder().totalUsers(null).build())).build());
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
                    .issues(List.of(reportedIssue("issue-a", 1, 10, 1, 10)))
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

            report(projectId, DAY_1, List.of(
                    reportedIssue("issue-a", 10, 100, 3, 40),
                    reportedIssue("issue-b", 1, 100, 1, 10)));
            report(projectId, DAY_2, List.of(reportedIssue("issue-a", 20, 200, 4, 50)));
            report(projectId, DAY_3, List.of(reportedIssue("issue-a", 30, 300, 5, 60)));

            // full window: both issues, issue-a aggregated over 3 days
            var fullPage = findIssues(projectId, DAY_1, DAY_3);
            assertThat(fullPage.total()).isEqualTo(2);
            var issueA = fullPage.content().stream()
                    .filter(issue -> issue.name().equals("issue-a"))
                    .findFirst()
                    .orElseThrow();
            assertThat(issueA.totalOccurrences()).isEqualTo(60);
            assertThat(issueA.total()).isEqualTo(600);
            assertThat(issueA.usersImpacted()).isEqualTo(12);
            assertThat(issueA.totalUsers()).isEqualTo(150);
            assertThat(issueA.firstSeen()).isEqualTo(DAY_1);
            assertThat(issueA.lastSeen()).isEqualTo(DAY_3);
            assertThat(issueA.daysReported()).isEqualTo(3);

            // restricted window: issue-b has no details, aggregates only cover DAY_2..DAY_3
            var restrictedPage = findIssues(projectId, DAY_2, DAY_3);
            assertThat(restrictedPage.total()).isEqualTo(1);
            var restrictedIssueA = restrictedPage.content().getFirst();
            assertThat(restrictedIssueA.name()).isEqualTo("issue-a");
            assertThat(restrictedIssueA.totalOccurrences()).isEqualTo(50);
            assertThat(restrictedIssueA.total()).isEqualTo(500);
            assertThat(restrictedIssueA.usersImpacted()).isEqualTo(9);
            assertThat(restrictedIssueA.totalUsers()).isEqualTo(110);
            assertThat(restrictedIssueA.firstSeen()).isEqualTo(DAY_2);
            assertThat(restrictedIssueA.lastSeen()).isEqualTo(DAY_3);
            assertThat(restrictedIssueA.daysReported()).isEqualTo(2);
        }

        @Test
        @DisplayName("Default sorting is last seen DESC, sort_by=total_occurrences sorts by occurrences DESC")
        void findIssuesSorting() {
            var projectId = createProject();

            // issue-a: most occurrences, seen earliest; issue-c: fewest occurrences, seen latest
            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 100, 1000, 1, 10)));
            report(projectId, DAY_2, List.of(reportedIssue("issue-b", 50, 1000, 1, 10)));
            report(projectId, DAY_3, List.of(reportedIssue("issue-c", 10, 1000, 1, 10)));

            var defaultPage = findIssues(projectId, DAY_1, DAY_3);
            assertThat(defaultPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly("issue-c", "issue-b", "issue-a");

            var byOccurrences = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_3, null,
                    AgentInsightsSortBy.TOTAL_OCCURRENCES, null, null, API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(byOccurrences.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly("issue-a", "issue-b", "issue-c");

            var byLastSeen = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_3, null,
                    AgentInsightsSortBy.LAST_SEEN, null, null, API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(byLastSeen.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly("issue-c", "issue-b", "issue-a");
        }

        @Test
        @DisplayName("Status filter returns only issues with the requested status")
        void findIssuesWhenStatusFilterIsSetThenOnlyMatchingIssues() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(
                    reportedIssue("issue-a", 1, 10, 1, 10),
                    reportedIssue("issue-b", 2, 10, 1, 10)));

            var issueBId = findIssues(projectId, DAY_1, DAY_1).content().stream()
                    .filter(issue -> issue.name().equals("issue-b"))
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
                    AgentInsightsIssueStatus.RESOLVED, null, null, null, API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(resolvedPage.total()).isEqualTo(1);
            assertThat(resolvedPage.content().getFirst().name()).isEqualTo("issue-b");

            var allPage = findIssues(projectId, DAY_1, DAY_1);
            assertThat(allPage.total()).isEqualTo(2);
        }

        @Test
        @DisplayName("Pagination honors page and size and reports the full total")
        void findIssuesPagination() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(
                    reportedIssue("issue-a", 30, 100, 1, 10),
                    reportedIssue("issue-b", 20, 100, 1, 10),
                    reportedIssue("issue-c", 10, 100, 1, 10)));

            var firstPage = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null,
                    AgentInsightsSortBy.TOTAL_OCCURRENCES, 1, 2, API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(firstPage.total()).isEqualTo(3);
            assertThat(firstPage.size()).isEqualTo(2);
            assertThat(firstPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly("issue-a", "issue-b");

            var secondPage = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null,
                    AgentInsightsSortBy.TOTAL_OCCURRENCES, 2, 2, API_KEY, TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(secondPage.content())
                    .extracting(AgentInsightsIssue::name)
                    .containsExactly("issue-c");
        }

        @Test
        @DisplayName("Workspace isolation: issues are invisible from another workspace")
        void findIssuesWhenOtherWorkspaceThenIssuesAreNotVisible() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 1, 10, 1, 10)));

            var page = agentInsightsResourceClient.findIssues(projectId, DAY_1, DAY_1, null, null, null, null,
                    OTHER_API_KEY, OTHER_WORKSPACE, HttpStatus.SC_OK);
            assertThat(page.total()).isZero();
            assertThat(page.content()).isEmpty();
        }

        @Test
        @DisplayName("Invalid query params return 400")
        void findIssuesWhenQueryParamsAreInvalidThenBadRequest() {
            var projectId = UUID.randomUUID();

            try (var response = agentInsightsResourceClient.findIssuesWithResponse(projectId, "2026-06-01",
                    "2026-06-03", "unknown-status", null, null, null, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }

            try (var response = agentInsightsResourceClient.findIssuesWithResponse(projectId, "2026-06-01",
                    "2026-06-03", null, "unknown-sort", null, null, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }

            try (var response = agentInsightsResourceClient.findIssuesWithResponse(projectId, "not-a-date",
                    "2026-06-03", null, null, null, null, API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }

            // from_date after to_date
            try (var response = agentInsightsResourceClient.findIssuesWithResponse(projectId, "2026-06-03",
                    "2026-06-01", null, null, null, null, API_KEY, TEST_WORKSPACE)) {
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
            var metadata = JsonUtils.getJsonNodeFromString("{\"sample_trace_ids\":[\"abc\",\"def\"]}");

            report(projectId, DAY_2, List.of(reportedIssue("issue-a", 20, 200, 4, 50)));
            report(projectId, DAY_1, List.of(
                    reportedIssue("issue-a", 10, 100, 3, 40).toBuilder().metadata(metadata).build()));
            report(projectId, DAY_3, List.of(reportedIssue("issue-a", 30, 300, 5, 60)));

            var issueId = findIssues(projectId, DAY_1, DAY_3).content().getFirst().id();

            var issue = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_3, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);

            assertThat(issue.id()).isEqualTo(issueId);
            assertThat(issue.name()).isEqualTo("issue-a");
            assertThat(issue.status()).isEqualTo(AgentInsightsIssueStatus.OPEN);
            assertThat(issue.details()).hasSize(3);
            assertThat(issue.details())
                    .extracting(detail -> detail.reportDay())
                    .containsExactly(DAY_1, DAY_2, DAY_3);

            var firstDetail = issue.details().getFirst();
            assertThat(firstDetail.count()).isEqualTo(10);
            assertThat(firstDetail.totalCount()).isEqualTo(100);
            assertThat(firstDetail.usersImpacted()).isEqualTo(3);
            assertThat(firstDetail.totalUsers()).isEqualTo(40);
            assertThat(firstDetail.metadata()).isEqualTo(metadata);

            // window restricted to a single day
            var restricted = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_2, DAY_2, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(restricted.details()).hasSize(1);
            assertThat(restricted.details().getFirst().reportDay()).isEqualTo(DAY_2);
        }

        @Test
        @DisplayName("Issue exists but has no details in the window: returns 200 with empty details")
        void getIssueByIdWhenNoDetailsInWindowThenEmptyDetails() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
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

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
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

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            agentInsightsResourceClient.updateStatus(issueId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.CLOSED)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            var issue = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_1, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(issue.status()).isEqualTo(AgentInsightsIssueStatus.CLOSED);

            // reopen
            agentInsightsResourceClient.updateStatus(issueId,
                    AgentInsightsIssueUpdate.builder()
                            .projectId(projectId)
                            .status(AgentInsightsIssueStatus.OPEN)
                            .build(),
                    API_KEY, TEST_WORKSPACE, HttpStatus.SC_NO_CONTENT);

            issue = agentInsightsResourceClient.getIssue(issueId, projectId, DAY_1, DAY_1, API_KEY,
                    TEST_WORKSPACE, HttpStatus.SC_OK);
            assertThat(issue.status()).isEqualTo(AgentInsightsIssueStatus.OPEN);
        }

        @Test
        @DisplayName("Unknown issue, mismatched project or foreign workspace return 404")
        void updateIssueStatusWhenNotAccessibleThenNotFound() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
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

        @Test
        @DisplayName("Invalid status value returns 400, missing project_id returns 422")
        void updateIssueStatusWhenPayloadIsInvalidThenClientError() {
            var projectId = createProject();

            report(projectId, DAY_1, List.of(reportedIssue("issue-a", 10, 100, 3, 40)));
            var issueId = findIssues(projectId, DAY_1, DAY_1).content().getFirst().id();

            try (var response = agentInsightsResourceClient.updateStatusWithResponse(issueId,
                    "{\"project_id\":\"%s\",\"status\":\"unknown\"}".formatted(projectId), API_KEY,
                    TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_BAD_REQUEST);
            }

            try (var response = agentInsightsResourceClient.updateStatusWithResponse(issueId,
                    "{\"status\":\"resolved\"}", API_KEY, TEST_WORKSPACE)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_UNPROCESSABLE_ENTITY);
            }
        }
    }
}
