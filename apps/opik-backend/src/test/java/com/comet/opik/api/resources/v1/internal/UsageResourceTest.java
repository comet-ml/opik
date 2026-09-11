package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceCountResponse;
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
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.UsageResourceClient;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.TestIdGeneratorFactory;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.db.TransactionTemplateAsync;
import com.comet.opik.podam.PodamFactoryUtils;
import com.redis.testcontainers.RedisContainer;
import jakarta.ws.rs.client.Entity;
import jakarta.ws.rs.core.HttpHeaders;
import lombok.extern.slf4j.Slf4j;
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
import reactor.core.publisher.Mono;
import ru.vyarus.dropwizard.guice.test.ClientSupport;
import ru.vyarus.dropwizard.guice.test.jupiter.ext.TestDropwizardAppExtension;
import ru.vyarus.guicey.jdbi3.tx.TransactionTemplate;
import uk.co.jemos.podam.api.PodamFactory;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static com.comet.opik.infrastructure.auth.RequestContext.WORKSPACE_HEADER;
import static com.comet.opik.infrastructure.db.TransactionTemplateAsync.WRITE;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Named.named;
import static org.junit.jupiter.params.provider.Arguments.arguments;

@DisplayName("Usage Resource Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Slf4j
@ExtendWith(DropwizardAppExtensionProvider.class)
class UsageResourceTest {

    public static final String TRACE_RESOURCE_URL_TEMPLATE = "%s/v1/private/traces";
    public static final String SPANS_RESOURCE_URL_TEMPLATE = "%s/v1/private/spans";
    private static final String EXPERIMENT_RESOURCE_URL_TEMPLATE = "%s/v1/private/experiments";
    private static final String DATASET_RESOURCE_URL_TEMPLATE = "%s/v1/private/datasets";

    private static final IdGenerator ID_GENERATOR = TestIdGeneratorFactory.create();

    private final String USER = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final MySQLContainer MYSQL_CONTAINER = MySQLContainerUtils.newMySQLContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICK_HOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);

    @RegisterApp
    private final TestDropwizardAppExtension APP;

    private final WireMockUtils.WireMockRuntime wireMock;

    {
        Startables.deepStart(REDIS, MYSQL_CONTAINER, CLICK_HOUSE_CONTAINER, ZOOKEEPER_CONTAINER).join();

        wireMock = WireMockUtils.startWireMock();

        var databaseAnalyticsFactory = ClickHouseContainerUtils.newDatabaseAnalyticsFactory(
                CLICK_HOUSE_CONTAINER, DATABASE_NAME);

        MigrationUtils.runMysqlDbMigration(MYSQL_CONTAINER);
        MigrationUtils.runClickhouseDbMigration(CLICK_HOUSE_CONTAINER);

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL_CONTAINER.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private String baseURI;
    private ClientSupport client;
    private TransactionTemplateAsync clickHouseTemplate;
    private TransactionTemplate mySqlTemplate;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private UsageResourceClient usageResourceClient;

    @BeforeAll
    void setUpAll(ClientSupport client, TransactionTemplateAsync clickHouseTemplate,
            TransactionTemplate mySqlTemplate) {
        this.baseURI = TestUtils.getBaseUrl(client);
        this.client = client;
        this.clickHouseTemplate = clickHouseTemplate;
        this.mySqlTemplate = mySqlTemplate;

        ClientSupportUtils.config(client);

        this.experimentResourceClient = new ExperimentResourceClient(client, baseURI, factory);
        this.traceResourceClient = new TraceResourceClient(client, baseURI);
        this.usageResourceClient = new UsageResourceClient(client, baseURI);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        mockTargetWorkspace(apiKey, workspaceName, workspaceId, USER);
    }

    private void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId, String user) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, user);
    }

    @Nested
    @DisplayName("Opik usage:")
    @TestInstance(TestInstance.Lifecycle.PER_CLASS)
    class Usage {

        @Test
        @DisplayName("Get traces count on previous day for all workspaces, no Auth")
        void tracesCountForWorkspace() {
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            // Setup mock workspace with traces
            var workspaceId = UUID.randomUUID().toString();
            var apikey = "apiKey-" + UUID.randomUUID();
            var tracesCount = setupEntitiesForWorkspace(workspaceId, apikey, traces,
                    TRACE_RESOURCE_URL_TEMPLATE);

            // Change created_at to the previous day in order to capture those traces in count query, since for usage billing we need to count it daily for yesterday
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            // Setup second workspace with traces, but leave created_at date set to today, so traces do not end up in the pool
            var workspaceIdForToday = UUID.randomUUID().toString();
            var apikey2 = "apiKey-" + UUID.randomUUID();

            setupEntitiesForWorkspace(workspaceIdForToday, apikey2, traces, TRACE_RESOURCE_URL_TEMPLATE);

            var expectedTraceCount = TraceCountResponse.WorkspaceTraceCount.builder()
                    .workspace(workspaceId)
                    .traceCount(tracesCount)
                    .build();

            var response = usageResourceClient.getWorkspaceTraceCounts();

            var actualTraceCount = getMatch(response.workspacesTracesCount(),
                    wtc -> wtc.workspace().equals(workspaceId));
            assertThat(actualTraceCount).contains(expectedTraceCount);

            // Check that today's workspace is not returned
            var actualTraceCountToday = getMatch(response.workspacesTracesCount(),
                    wtc -> wtc.workspace().equals(workspaceIdForToday));
            assertThat(actualTraceCountToday).isEmpty();
        }

        @Test
        @DisplayName("Get spans count on previous day for all workspaces, no Auth")
        void spansCountForWorkspace() {
            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();

            // Setup mock workspace with spans
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var spansCount = setupEntitiesForWorkspace(workspaceId, apiKey, spans, SPANS_RESOURCE_URL_TEMPLATE);

            // Change created_at to the previous day in order to capture those spans in count query, since for usage
            // billing we need to count it daily for yesterday
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            // Setup second workspace with spans, but leave created_at date set to today, so spans do not end up in the
            // pool
            var workspaceIdForToday = UUID.randomUUID().toString();
            var apiKey2 = "apiKey-" + UUID.randomUUID();

            setupEntitiesForWorkspace(workspaceIdForToday, apiKey2, spans, SPANS_RESOURCE_URL_TEMPLATE);

            var expectedSpanCount = SpansCountResponse.WorkspaceSpansCount.builder()
                    .workspace(workspaceId)
                    .spanCount(spansCount)
                    .build();

            var response = usageResourceClient.getWorkspaceSpanCounts();

            var actualSpanCount = getMatch(response.workspacesSpansCount(),
                    workspaceCount -> workspaceCount.workspace().equals(workspaceId));
            assertThat(actualSpanCount).contains(expectedSpanCount);

            // Check that today's workspace is not returned
            var actualSpanCountToday = getMatch(response.workspacesSpansCount(),
                    workspaceCount -> workspaceCount.workspace().equals(workspaceIdForToday));
            assertThat(actualSpanCountToday).isEmpty();
        }

        @Test
        @DisplayName("Get span usage breakdown by workspace, project and user for previous day")
        void spanBreakdownForWorkspace() {
            var projectName = "breakdown-%s".formatted(ID_GENERATOR.generateId());
            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder().id(null).projectName(projectName).build())
                    .toList();

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var spansCount = setupEntitiesForWorkspace(workspaceId, apiKey, spans, SPANS_RESOURCE_URL_TEMPLATE);
            // Backdate to the previous day so the rows land in the yesterday window of the count query
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            // Spans left at today in another workspace must be excluded
            var workspaceIdForToday = UUID.randomUUID().toString();
            setupEntitiesForWorkspace(workspaceIdForToday, "apiKey-" + UUID.randomUUID(), spans,
                    SPANS_RESOURCE_URL_TEMPLATE);

            var response = usageResourceClient.getWorkspaceSpanCountsBreakdown();

            var actualRows = response.breakdown().stream()
                    .filter(row -> row.workspaceId().equals(workspaceId))
                    .toList();

            // All spans share one project and one user, so a single breakdown row is expected. The project id is
            // assigned by the backend, so it is asserted as present rather than compared.
            assertThat(actualRows).hasSize(1);
            var actualRow = actualRows.getFirst();
            assertThat(actualRow.user()).isEqualTo(USER);
            assertThat(actualRow.count()).isEqualTo(spansCount);
            assertThat(actualRow.projectId()).isNotNull();

            assertThat(response.breakdown())
                    .noneMatch(r -> r.workspaceId().equals(workspaceIdForToday));
        }

        @Test
        @DisplayName("Get traces daily info for BI events, no Auth")
        void traceBiInfoTest() {
            var traces = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(traces, TRACE_RESOURCE_URL_TEMPLATE, "traces",
                    subtractClickHouseTableRecordsCreatedAtOneDay("traces"));
        }

        @Test
        @DisplayName("Get spans daily info for BI events, no Auth")
        void spanBiInfoTest() {
            var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(spans, SPANS_RESOURCE_URL_TEMPLATE, "spans",
                    subtractClickHouseTableRecordsCreatedAtOneDay("spans"));
        }

        @Test
        @DisplayName("Get experiments daily info for BI events, no Auth")
        void experimentBiInfoTest() {
            var experiments = experimentResourceClient.generateExperimentList();
            biInfoTest(experiments, EXPERIMENT_RESOURCE_URL_TEMPLATE, "experiments",
                    subtractClickHouseTableRecordsCreatedAtOneDay("experiments"));
        }

        @Test
        @DisplayName("Get datasets daily info for BI events, no Auth")
        void datasetBiInfoTest() {
            var datasets = DatasetResourceClient.buildDatasetList(factory).stream()
                    .map(e -> e.toBuilder()
                            .id(null)
                            .build())
                    .toList();
            biInfoTest(datasets, DATASET_RESOURCE_URL_TEMPLATE, "datasets",
                    subtractDatasetRecordsCreatedAtOneDay());
        }

        private <T> void biInfoTest(List<T> entities, String resourseUri, String biType,
                Consumer<String> decreaseTableRecordsCreatedAt) {
            // Setup mock workspace with corresponding entities
            var workspaceId = UUID.randomUUID().toString();
            var apikey = "apiKey-" + UUID.randomUUID();
            var entitiesCount = setupEntitiesForWorkspace(workspaceId, apikey, entities,
                    resourseUri);

            // Change created_at to the previous day in order to capture those entities in count query, since for BI events we need to count it daily for yesterday
            decreaseTableRecordsCreatedAt.accept(workspaceId);

            awaitBiInformation(biType, workspaceId, entitiesCount);
        }

        @Test
        @DisplayName("Get spans count excluding demo data projects")
        void spansCountExcludingDemoData() {
            var regularSpans = spansInProject("project-" + ID_GENERATOR.generateId());
            var demoSpans = spansInProject(DemoData.PROJECTS.get(1));

            // Setup workspace with both regular and demo spans
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(concat(regularSpans, demoSpans), apiKey, workspaceName);

            // Change created_at to the previous day to capture in usage query
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            // Should only count regular spans, not demo spans
            awaitSpanCount(workspaceId, regularSpans.size());
        }

        @Test
        @DisplayName("Span count includes activity in demo projects created after the demo cutoff")
        void spansCountIncludesPostCutoffActivityInDemoProjects() {
            var demoSpans = spansInProject(DemoData.PROJECTS.getFirst());

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(demoSpans, apiKey, workspaceName);

            // Project created today → cutoff = today + 1 min, in the future. Push the project two days back so
            // cutoff lands ~2 days ago, then move spans to yesterday — they end up post-cutoff and must be counted.
            backdateDemoProjectsCreatedAtTwoDays();
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitSpanCount(workspaceId, demoSpans.size());
        }

        /** Demo-project activity is excluded whether the workspace touched one demo project or every one of them. */
        @ParameterizedTest
        @MethodSource
        void tracesCountExcludesDemoProjects(List<String> demoProjectNames) {
            var regularTraces = tracesInProjects("project-" + ID_GENERATOR.generateId());
            var demoTraces = tracesInProjects(demoProjectNames.toArray(String[]::new));

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createTraces(concat(regularTraces, demoTraces), apiKey, workspaceName);

            // Change created_at to the previous day to capture in usage query
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            awaitTraceCount(workspaceId, regularTraces.size());
        }

        private Stream<Arguments> tracesCountExcludesDemoProjects() {
            return Stream.of(
                    arguments(named("a demo project", List.of(DemoData.PROJECTS.getFirst()))),
                    arguments(named("every demo project", DemoData.PROJECTS)));
        }

        /**
         * The case whose behaviour changed, kept separate because the cutoff move is what it is about. The trace
         * exclusion used to carry an {@code OR created_at > demoDataCreatedAt} branch, which counted activity in a
         * demo project once that project predated the cutoff — what
         * {@link #spansCountIncludesPostCutoffActivityInDemoProjects()} still pins for spans. That branch cannot
         * match where demo projects are created continuously, since the cutoff is then effectively now, and it only
         * existed alongside the inlined project-id literal the trace usage queries no longer carry.
         */
        @Test
        void tracesCountExcludesDemoProjectsPredatingTheDemoCutoff() {
            var regularTraces = tracesInProjects("project-" + ID_GENERATOR.generateId());
            var demoTraces = tracesInProjects(DemoData.PROJECTS.getFirst());

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createTraces(concat(regularTraces, demoTraces), apiKey, workspaceName);

            // Moves demo project creation before the window, where the removed OR branch would have counted it
            backdateDemoProjectsCreatedAtTwoDays();
            // Change created_at to the previous day to capture in usage query. Waiting on the regular count keeps
            // the assertion from passing on an unfinished mutation.
            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            awaitTraceCount(workspaceId, regularTraces.size());
        }

        /**
         * The premise the fold rests on: summing per-project counts equals the distinct-id total the
         * workspace-grouped query used to return, because a trace id belongs to exactly one project. The write path
         * is what guarantees it — presenting an existing id under another project is a conflict, not a move — so no
         * id can contribute a row under two projects for the sum to double-count. Spans already cover the
         * equivalent rejection; this pins it for traces together with the count that depends on it.
         */
        @Test
        void tracesCountCountsATraceOnceBecauseItsIdCannotMoveProjects() {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var trace = PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                    .getFirst()
                    .toBuilder()
                    .id(ID_GENERATOR.generateId())
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .build();
            createTraces(List.of(trace), apiKey, workspaceName);

            var sameIdInAnotherProject = trace.toBuilder()
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .build();
            try (var response = traceResourceClient.callCreateTrace(sameIdInAnotherProject, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
            }

            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            awaitTraceCount(workspaceId, 1);
        }

        /**
         * The per-workspace count is folded from per-project rows, so a workspace spanning several projects has to
         * sum back to the total the workspace-grouped query returned.
         */
        @Test
        void tracesCountSumsAWorkspacesProjectsIntoOneRow() {
            var traces = tracesInProjects("project-" + ID_GENERATOR.generateId(),
                    "project-" + ID_GENERATOR.generateId());

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createTraces(traces, apiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            awaitTraceCount(workspaceId, traces.size());
        }

        /**
         * The BI fold, which re-aggregates the per-project rows by workspace and user. One user across two regular
         * projects plus a demo project must come back as a single row counting only the regular projects, so losing
         * the exclusion and losing the re-aggregation are both caught.
         */
        @Test
        void traceBiInfoSumsAUsersProjectsAndExcludesDemoProjects() {
            var regularTraces = tracesInProjects("project-" + ID_GENERATOR.generateId(),
                    "project-" + ID_GENERATOR.generateId());
            var demoTraces = tracesInProjects(DemoData.PROJECTS.get(2));

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createTraces(concat(regularTraces, demoTraces), apiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            awaitBiInformation("traces", workspaceId, regularTraces.size());
        }

        /**
         * The BI fold keys on workspace and user, so two users in one workspace have to come back as two rows.
         * Every other test here runs as a single user, and collapsing the two would misreport both.
         */
        @Test
        void traceBiInfoKeepsUsersInTheSameWorkspaceApart() {
            var workspaceId = UUID.randomUUID().toString();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var otherApiKey = "apiKey-" + UUID.randomUUID();
            var otherUser = "user-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockTargetWorkspace(otherApiKey, workspaceName, workspaceId, otherUser);

            var traces = tracesInProjects("project-" + ID_GENERATOR.generateId());
            var otherUserTraces = tracesInProjects("project-" + ID_GENERATOR.generateId());
            createTraces(traces, apiKey, workspaceName);
            createTraces(otherUserTraces, otherApiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("traces").accept(workspaceId);

            awaitBiInformation("traces", workspaceId,
                    BiInformationResponse.BiInformation.builder()
                            .workspaceId(workspaceId)
                            .user(USER)
                            .count(traces.size())
                            .build(),
                    BiInformationResponse.BiInformation.builder()
                            .workspaceId(workspaceId)
                            .user(otherUser)
                            .count(otherUserTraces.size())
                            .build());
        }

        private List<Trace> tracesInProjects(String... projectNames) {
            return Arrays.stream(projectNames)
                    .flatMap(projectName -> PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                            .stream()
                            .map(trace -> trace.toBuilder().id(null).projectName(projectName).build()))
                    .toList();
        }

        private List<Span> spansInProject(String projectName) {
            return PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .stream()
                    .map(span -> span.toBuilder().id(null).projectName(projectName).build())
                    .toList();
        }

        private <T> List<T> concat(List<T> first, List<T> second) {
            return Stream.concat(first.stream(), second.stream()).toList();
        }

        private void createTraces(List<Trace> traces, String apiKey, String workspaceName) {
            traces.forEach(trace -> createEntity(trace, apiKey, workspaceName, TRACE_RESOURCE_URL_TEMPLATE));
        }

        private void createSpans(List<Span> spans, String apiKey, String workspaceName) {
            spans.forEach(span -> createEntity(span, apiKey, workspaceName, SPANS_RESOURCE_URL_TEMPLATE));
        }

        private void awaitTraceCount(String workspaceId, int expectedCount) {
            var expectedTraceCount = TraceCountResponse.WorkspaceTraceCount.builder()
                    .workspace(workspaceId)
                    .traceCount(expectedCount)
                    .build();
            await().atMost(10, SECONDS).untilAsserted(() -> {
                var actualTraceCount = getMatch(usageResourceClient.getWorkspaceTraceCounts().workspacesTracesCount(),
                        traceCount -> traceCount.workspace().equals(workspaceId));
                assertThat(actualTraceCount).contains(expectedTraceCount);
            });
        }

        private void awaitSpanCount(String workspaceId, int expectedCount) {
            var expectedSpanCount = SpansCountResponse.WorkspaceSpansCount.builder()
                    .workspace(workspaceId)
                    .spanCount(expectedCount)
                    .build();
            await().atMost(10, SECONDS).untilAsserted(() -> {
                var actualSpanCount = getMatch(usageResourceClient.getWorkspaceSpanCounts().workspacesSpansCount(),
                        spanCount -> spanCount.workspace().equals(workspaceId));
                assertThat(actualSpanCount).contains(expectedSpanCount);
            });
        }

        private void awaitBiInformation(String entityType, String workspaceId, int expectedCount) {
            awaitBiInformation(entityType, workspaceId, BiInformationResponse.BiInformation.builder()
                    .workspaceId(workspaceId)
                    .user(USER)
                    .count(expectedCount)
                    .build());
        }

        private void awaitBiInformation(String entityType, String workspaceId,
                BiInformationResponse.BiInformation... expectedBiInformation) {
            await().atMost(10, SECONDS).untilAsserted(() -> {
                var actualBiInformation = usageResourceClient.getBiInformation(entityType)
                        .biInformation()
                        .stream()
                        .filter(biInfo -> biInfo.workspaceId().equals(workspaceId))
                        .toList();
                assertThat(actualBiInformation).containsExactlyInAnyOrder(expectedBiInformation);
            });
        }
    }

    private <T> int setupEntitiesForWorkspace(String workspaceId, String okApikey, List<T> entities,
            String resourseUri) {
        var workspaceName = "test-workspace-" + UUID.randomUUID();
        mockTargetWorkspace(okApikey, workspaceName, workspaceId);

        entities.forEach(entity -> createEntity(entity, okApikey, workspaceName, resourseUri));

        return entities.size();
    }

    private <T> void createEntity(T entity, String apiKey, String workspaceName, String resourseUri) {
        try (var actualResponse = client.target(resourseUri.formatted(baseURI))
                .request()
                .header(HttpHeaders.AUTHORIZATION, apiKey)
                .header(WORKSPACE_HEADER, workspaceName)
                .post(Entity.json(entity))) {

            assertThat(actualResponse.getStatusInfo().getStatusCode()).isEqualTo(201);
            assertThat(actualResponse.hasEntity()).isFalse();
        }
    }

    private <T> Optional<T> getMatch(List<T> list, Predicate<T> predicate) {
        return list.stream()
                .filter(predicate)
                .findFirst();
    }

    private Consumer<String> subtractClickHouseTableRecordsCreatedAtOneDay(String table) {
        // Change created_at to the previous day in order to capture this data in query
        return workspaceId -> {
            String updateCreatedAt = "ALTER TABLE %s UPDATE created_at = subtractDays(created_at, 1) WHERE workspace_id=:workspace_id;"
                    .formatted(table);
            clickHouseTemplate.nonTransaction(connection -> {
                var statement = connection.createStatement(updateCreatedAt)
                        .bind("workspace_id", workspaceId);
                return Mono.from(statement.execute());
            }).block();
        };
    }

    private Consumer<String> subtractDatasetRecordsCreatedAtOneDay() {
        // Change created_at to the previous day in order to capture this data in query
        return workspaceId -> {
            mySqlTemplate.inTransaction(WRITE, handle -> {
                handle.createUpdate(
                        "UPDATE datasets SET created_at = TIMESTAMPADD(DAY, -1, created_at) WHERE workspace_id=:workspace_id")
                        .bind("workspace_id", workspaceId)
                        .execute();

                return null;
            });
        };
    }

    private void backdateDemoProjectsCreatedAtTwoDays() {
        // Push demo-named project creation timestamps far enough into the past that the
        // computed demoDataCreatedAt cutoff (= max(project.created_at) + 1 min) precedes
        // spans/traces backdated to yesterday — exercising the `OR created_at > cutoff`
        // branch of the span exclusion predicate. The service resolves demo projects
        // by global name across all workspaces, so the update must span workspaces too.
        mySqlTemplate.inTransaction(WRITE, handle -> {
            handle.createUpdate(
                    "UPDATE projects SET created_at = TIMESTAMPADD(DAY, -2, created_at) WHERE name IN (<names>)")
                    .bindList("names", DemoData.PROJECTS)
                    .execute();

            return null;
        });
    }
}
