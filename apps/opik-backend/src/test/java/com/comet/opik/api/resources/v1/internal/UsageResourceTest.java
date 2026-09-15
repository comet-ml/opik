package com.comet.opik.api.resources.v1.internal;

import com.comet.opik.api.BiInformationResponse;
import com.comet.opik.api.Span;
import com.comet.opik.api.SpanUpdate;
import com.comet.opik.api.SpansCountResponse;
import com.comet.opik.api.Trace;
import com.comet.opik.api.TraceCountResponse;
import com.comet.opik.api.UsageByWorkspaceProjectUserResponse.WorkspaceProjectUserCount;
import com.comet.opik.api.error.ErrorMessage;
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
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.api.resources.utils.resources.UsageResourceClient;
import com.comet.opik.domain.DemoData;
import com.comet.opik.domain.IdGenerator;
import com.comet.opik.domain.SpanService;
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
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.stream.Collectors;
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
    private SpanResourceClient spanResourceClient;
    private ProjectResourceClient projectResourceClient;
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
        this.spanResourceClient = new SpanResourceClient(client, baseURI);
        this.projectResourceClient = new ProjectResourceClient(client, baseURI, factory);
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

        private Stream<Arguments> spansCountExcludesDemoProjects() {
            return Stream.of(
                    arguments(named("a demo project", List.of(DemoData.PROJECTS.get(1)))),
                    arguments(named("every demo project", DemoData.PROJECTS)));
        }

        /** Demo-project activity is excluded whether the workspace touched one demo project or every one of them. */
        @ParameterizedTest
        @MethodSource
        void spansCountExcludesDemoProjects(List<String> demoProjectNames) {
            var regularSpans = spansInProjects("project-" + ID_GENERATOR.generateId());
            var demoSpans = spansInProjects(demoProjectNames.toArray(String[]::new));

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(concat(regularSpans, demoSpans), apiKey, workspaceName);

            // Change created_at to the previous day to capture in usage query
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitSpanCount(workspaceId, regularSpans.size());
        }

        /**
         * The span counterpart of {@link #tracesCountExcludesDemoProjectsPredatingTheDemoCutoff()}, and separate
         * from {@link #spansCountExcludesDemoProjects(List)} for the same reason: only this one backdates the demo
         * projects, which is the state the removed cutoff needed in order to matter.
         */
        @Test
        void spansCountExcludesDemoProjectsPredatingTheDemoCutoff() {
            var regularSpans = spansInProjects("project-" + ID_GENERATOR.generateId());
            var demoSpans = spansInProjects(DemoData.PROJECTS.getFirst());

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(concat(regularSpans, demoSpans), apiKey, workspaceName);

            // Moves demo project creation before the window, where the removed OR branch would have counted it
            backdateDemoProjectsCreatedAtTwoDays();
            // Change created_at to the previous day to capture in usage query. Waiting on the regular count keeps
            // the assertion from passing on an unfinished mutation.
            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitSpanCount(workspaceId, regularSpans.size());
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
         * Demo-project activity is excluded even where the demo project predates the {@code demoDataCreatedAt}
         * cutoff the exclusion used to compute. That cutoff was {@code max(project.created_at) + 1 minute}, so
         * where demo projects are created continuously it is effectively now and can spare no row in the
         * previous-day window; it existed only alongside the inlined project-id literal the usage queries no longer
         * carry. Separate from {@link #tracesCountExcludesDemoProjects(List)} because only this one backdates the
         * projects, which is the state the cutoff needed in order to matter.
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
        void tracesCountIncludesEachTraceOnceBecauseItsIdCannotMoveBetweenProjects() {
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
         * The known limit of the fold, pinned rather than left undefined. {@code BULK_INSERT} binds the project the
         * request asked for without reading the stored row, so a client reusing an id across two project names
         * writes two rows, and the fold counts it once per project. That is the value the per-project breakdown has
         * always reported — its query has always grouped by project — so the three consumers agree here; before the
         * fold the workspace and BI totals said one while the breakdown said two. Counting it once instead would
         * mean deduplicating inside the usage queries, which exist to stay cheap and constant, so the behaviour is
         * documented on {@code DemoDataExclusionUtils} rather than changed. Assert the breakdown alongside the
         * count: they agreeing is the property worth keeping if this is ever revisited.
         */
        @Test
        void spansCountCountsABatchDuplicatedIdOncePerProject() {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var inFirstProject = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .getFirst()
                    .toBuilder()
                    .id(ID_GENERATOR.generateId())
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .feedbackScores(null)
                    .build();
            var sameIdInSecondProject = inFirstProject.toBuilder()
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .build();

            spanResourceClient.batchCreateSpans(List.of(inFirstProject), apiKey, workspaceName);
            spanResourceClient.batchCreateSpans(List.of(sameIdInSecondProject), apiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitSpanCount(workspaceId, 2);
            assertThat(usageResourceClient.getWorkspaceSpanCountsBreakdown().breakdown())
                    .filteredOn(row -> row.workspaceId().equals(workspaceId))
                    .hasSize(2);
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

        /**
         * The single-span write paths keep an id in one project, which is what makes summing per-project counts
         * equal the distinct-id total the workspace-grouped query used to return. A create is ignored, the span
         * already existing, and a patch is refused by the 40-character sentinel {@code SpanDAO.PARTIAL_INSERT}
         * writes into a {@code FixedString(36)} when the stored project differs. The batch path does not enforce
         * this — see {@link #spansCountCountsABatchDuplicatedIdOncePerProject()}.
         */
        @Test
        void spansCountIncludesEachSpanOnceOnTheSingleSpanWritePaths() {
            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            var span = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                    .getFirst()
                    .toBuilder()
                    .id(ID_GENERATOR.generateId())
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .build();
            createSpans(List.of(span), apiKey, workspaceName);

            var createInAnotherProject = span.toBuilder()
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .build();
            try (var response = spanResourceClient.callCreateSpan(createInAnotherProject, apiKey, workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CREATED);
            }

            var patchInAnotherProject = factory.manufacturePojo(SpanUpdate.class).toBuilder()
                    .projectId(null)
                    .projectName("project-" + ID_GENERATOR.generateId())
                    .traceId(span.traceId())
                    .parentSpanId(span.parentSpanId())
                    .build();
            // The message, not just the status: handleSpanDBError maps the trace-id and parent-span-id sentinels to
            // 409 as well, and this test is only about the project one
            try (var response = spanResourceClient.callUpdateSpan(span.id(), patchInAnotherProject, apiKey,
                    workspaceName)) {
                assertThat(response.getStatus()).isEqualTo(HttpStatus.SC_CONFLICT);
                assertThat(response.readEntity(ErrorMessage.class).errors())
                        .containsExactly(SpanService.PROJECT_AND_WORKSPACE_NAME_MISMATCH);
            }

            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitSpanCount(workspaceId, 1);
        }

        /**
         * The per-workspace count is folded from per-project rows, so a workspace spanning several projects has to
         * sum back to the total the workspace-grouped query returned.
         */
        @Test
        void spansCountSumsAWorkspacesProjectsIntoOneRow() {
            var spans = spansInProjects("project-" + ID_GENERATOR.generateId(),
                    "project-" + ID_GENERATOR.generateId());

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(spans, apiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitSpanCount(workspaceId, spans.size());
        }

        /**
         * The span BI fold, which re-aggregates the per-project rows by workspace and user. One user across two
         * regular projects plus a demo project must come back as a single row counting only the regular projects,
         * so losing the exclusion and losing the re-aggregation are both caught.
         */
        @Test
        void spanBiInfoSumsAUsersProjectsAndExcludesDemoProjects() {
            var regularSpans = spansInProjects("project-" + ID_GENERATOR.generateId(),
                    "project-" + ID_GENERATOR.generateId());
            var demoSpans = spansInProjects(DemoData.PROJECTS.get(2));

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(concat(regularSpans, demoSpans), apiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitBiInformation("spans", workspaceId, regularSpans.size());
        }

        /**
         * The BI fold keys on workspace and user, so two users in one workspace have to come back as two rows.
         * Every other span test here runs as a single user, and collapsing the two would misreport both.
         */
        @Test
        void spanBiInfoKeepsUsersInTheSameWorkspaceApart() {
            var workspaceId = UUID.randomUUID().toString();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var otherApiKey = "apiKey-" + UUID.randomUUID();
            var otherUser = "user-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);
            mockTargetWorkspace(otherApiKey, workspaceName, workspaceId, otherUser);

            var spans = spansInProjects("project-" + ID_GENERATOR.generateId());
            var otherUserSpans = spansInProjects("project-" + ID_GENERATOR.generateId());
            createSpans(spans, apiKey, workspaceName);
            createSpans(otherUserSpans, otherApiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            awaitBiInformation("spans", workspaceId,
                    BiInformationResponse.BiInformation.builder()
                            .workspaceId(workspaceId)
                            .user(USER)
                            .count(spans.size())
                            .build(),
                    BiInformationResponse.BiInformation.builder()
                            .workspaceId(workspaceId)
                            .user(otherUser)
                            .count(otherUserSpans.size())
                            .build());
        }

        /**
         * The breakdown is the one span consumer that keeps project granularity, so the same input the BI fold
         * collapses into one row has to come back as one row per regular project — with the demo project gone.
         */
        @Test
        void spanBreakdownKeepsAUsersProjectsApartAndExcludesDemoProjects() {
            var firstProject = "project-" + ID_GENERATOR.generateId();
            var secondProject = "project-" + ID_GENERATOR.generateId();
            var firstProjectSpans = spansInProjects(firstProject);
            var secondProjectSpans = spansInProjects(secondProject);
            var demoSpans = spansInProjects(DemoData.PROJECTS.get(1));

            var workspaceId = UUID.randomUUID().toString();
            var apiKey = "apiKey-" + UUID.randomUUID();
            var workspaceName = "test-workspace-" + UUID.randomUUID();
            mockTargetWorkspace(apiKey, workspaceName, workspaceId);

            createSpans(concat(concat(firstProjectSpans, secondProjectSpans), demoSpans), apiKey, workspaceName);

            subtractClickHouseTableRecordsCreatedAtOneDay("spans").accept(workspaceId);

            // The project ids are assigned by the backend, so each project's expected count is keyed by the id it
            // was given rather than by position
            var expectedCountsByProjectId = Map.of(
                    projectResourceClient.getByName(firstProject, apiKey, workspaceName).id(),
                    (long) firstProjectSpans.size(),
                    projectResourceClient.getByName(secondProject, apiKey, workspaceName).id(),
                    (long) secondProjectSpans.size());

            await().atMost(10, SECONDS).untilAsserted(() -> {
                var actualRows = usageResourceClient.getWorkspaceSpanCountsBreakdown()
                        .breakdown()
                        .stream()
                        .filter(row -> row.workspaceId().equals(workspaceId))
                        .toList();

                assertThat(actualRows).allSatisfy(row -> assertThat(row.user()).isEqualTo(USER));
                assertThat(actualRows.stream()
                        .collect(Collectors.toMap(WorkspaceProjectUserCount::projectId,
                                WorkspaceProjectUserCount::count)))
                        .isEqualTo(expectedCountsByProjectId);
            });
        }

        private List<Trace> tracesInProjects(String... projectNames) {
            return Arrays.stream(projectNames)
                    .flatMap(projectName -> PodamFactoryUtils.manufacturePojoList(factory, Trace.class)
                            .stream()
                            .map(trace -> trace.toBuilder().id(null).projectName(projectName).build()))
                    .toList();
        }

        private List<Span> spansInProjects(String... projectNames) {
            return Arrays.stream(projectNames)
                    .flatMap(projectName -> PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                            .stream()
                            .map(span -> span.toBuilder().id(null).projectName(projectName).build()))
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
        // Push demo-named project creation far enough into the past that the demoDataCreatedAt cutoff the exclusion
        // used to compute (= max(project.created_at) + 1 min) would precede spans/traces backdated to yesterday —
        // the only state in which that cutoff could have spared a row, and so what the two tests asserting it no
        // longer does need. Demo projects are resolved by global name, so the update must span workspaces too.
        mySqlTemplate.inTransaction(WRITE, handle -> {
            handle.createUpdate(
                    "UPDATE projects SET created_at = TIMESTAMPADD(DAY, -2, created_at) WHERE name IN (<names>)")
                    .bindList("names", DemoData.PROJECTS)
                    .execute();

            return null;
        });
    }
}
