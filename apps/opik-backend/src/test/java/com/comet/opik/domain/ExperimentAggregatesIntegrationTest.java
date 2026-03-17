package com.comet.opik.domain;

import com.comet.opik.api.Dataset;
import com.comet.opik.api.DatasetItem;
import com.comet.opik.api.DatasetItemBatch;
import com.comet.opik.api.DatasetItemSource;
import com.comet.opik.api.Experiment;
import com.comet.opik.api.ExperimentGroupAggregationsResponse;
import com.comet.opik.api.ExperimentGroupCriteria;
import com.comet.opik.api.ExperimentGroupResponse;
import com.comet.opik.api.ExperimentItem;
import com.comet.opik.api.ExperimentSearchCriteria;
import com.comet.opik.api.ExperimentType;
import com.comet.opik.api.FeedbackScoreItem.FeedbackScoreBatchItem;
import com.comet.opik.api.Project;
import com.comet.opik.api.ScoreSource;
import com.comet.opik.api.Span;
import com.comet.opik.api.Trace;
import com.comet.opik.api.filter.ExperimentField;
import com.comet.opik.api.filter.ExperimentFilter;
import com.comet.opik.api.filter.ExperimentsComparisonFilter;
import com.comet.opik.api.filter.ExperimentsComparisonValidKnownField;
import com.comet.opik.api.filter.FieldType;
import com.comet.opik.api.filter.Operator;
import com.comet.opik.api.grouping.GroupBy;
import com.comet.opik.api.grouping.GroupingFactory;
import com.comet.opik.api.resources.utils.AuthTestUtils;
import com.comet.opik.api.resources.utils.ClickHouseContainerUtils;
import com.comet.opik.api.resources.utils.MigrationUtils;
import com.comet.opik.api.resources.utils.MySQLContainerUtils;
import com.comet.opik.api.resources.utils.RedisContainerUtils;
import com.comet.opik.api.resources.utils.StatsUtils;
import com.comet.opik.api.resources.utils.TestDropwizardAppExtensionUtils;
import com.comet.opik.api.resources.utils.TestUtils;
import com.comet.opik.api.resources.utils.WireMockUtils;
import com.comet.opik.api.resources.utils.resources.DatasetResourceClient;
import com.comet.opik.api.resources.utils.resources.ExperimentResourceClient;
import com.comet.opik.api.resources.utils.resources.ProjectResourceClient;
import com.comet.opik.api.resources.utils.resources.SpanResourceClient;
import com.comet.opik.api.resources.utils.resources.TraceResourceClient;
import com.comet.opik.domain.experiments.aggregations.ExperimentAggregatesService;
import com.comet.opik.extensions.DropwizardAppExtensionProvider;
import com.comet.opik.extensions.RegisterApp;
import com.comet.opik.infrastructure.DatabaseAnalyticsFactory;
import com.comet.opik.infrastructure.auth.RequestContext;
import com.comet.opik.podam.PodamFactoryUtils;
import com.comet.opik.utils.JsonUtils;
import com.google.inject.Injector;
import com.redis.testcontainers.RedisContainer;
import org.assertj.core.api.recursive.comparison.RecursiveComparisonConfiguration;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.random.RandomGenerator;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static com.comet.opik.api.resources.utils.ClickHouseContainerUtils.DATABASE_NAME;
import static org.assertj.core.api.Assertions.assertThat;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@DisplayName("Experiment Aggregates Integration Test")
@ExtendWith(DropwizardAppExtensionProvider.class)
class ExperimentAggregatesIntegrationTest {

    // Fields to ignore in recursive comparison: id is a lookup key,
    // timestamps differ due to timing, and the remaining fields are not stored
    // in experiment_aggregates (they are computed/joined in the raw FIND path).
    // The not-stored fields are explicitly asserted as null below.
    private static final String[] EXPERIMENT_AGGREGATED_FIELDS_TO_IGNORE = new String[]{
            "id", "createdAt", "lastUpdatedAt",
            "datasetName", "projectName", "promptVersion",
            "datasetVersionSummary", "datasetItemCount",
    };

    private static final String API_KEY = UUID.randomUUID().toString();
    private static final String USER = UUID.randomUUID().toString();
    private static final String WORKSPACE_ID = UUID.randomUUID().toString();
    private static final String TEST_WORKSPACE = UUID.randomUUID().toString();

    private final RedisContainer REDIS = RedisContainerUtils.newRedisContainer();
    private final GenericContainer<?> ZOOKEEPER_CONTAINER = ClickHouseContainerUtils.newZookeeperContainer();
    private final ClickHouseContainer CLICKHOUSE_CONTAINER = ClickHouseContainerUtils
            .newClickHouseContainer(ZOOKEEPER_CONTAINER);
    private final MySQLContainer MYSQL = MySQLContainerUtils.newMySQLContainer();
    private final RandomGenerator random = new Random();

    public static final String[] IGNORED_FIELDS_DATA_ITEM = {"createdAt", "lastUpdatedAt", "experimentItems",
            "createdBy", "lastUpdatedBy", "datasetId", "tags", "datasetItemId"};

    public static final String[] IGNORED_FIELDS_EXPERIMENT_ITEM = {"createdAt", "lastUpdatedAt", "createdBy",
            "lastUpdatedBy", "comments", "projectName", "executionPolicy", "assertionResults", "status"};

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

        APP = TestDropwizardAppExtensionUtils.newTestDropwizardAppExtension(
                MYSQL.getJdbcUrl(), databaseAnalyticsFactory, wireMock.runtimeInfo(), REDIS.getRedisURI());
    }

    private final PodamFactory factory = PodamFactoryUtils.newPodamFactory();

    private ProjectResourceClient projectResourceClient;
    private DatasetResourceClient datasetResourceClient;
    private ExperimentResourceClient experimentResourceClient;
    private TraceResourceClient traceResourceClient;
    private SpanResourceClient spanResourceClient;

    private ExperimentService experimentService;
    private ExperimentAggregatesService experimentAggregatesService;

    @BeforeAll
    void setUpAll(ClientSupport client, Injector injector) {
        String baseUrl = TestUtils.getBaseUrl(client);

        // Mock authentication
        mockTargetWorkspace(API_KEY, TEST_WORKSPACE, WORKSPACE_ID);

        projectResourceClient = new ProjectResourceClient(client, baseUrl, factory);
        datasetResourceClient = new DatasetResourceClient(client, baseUrl);
        experimentResourceClient = new ExperimentResourceClient(client, baseUrl, factory);
        traceResourceClient = new TraceResourceClient(client, baseUrl);
        spanResourceClient = new SpanResourceClient(client, baseUrl);

        // Get injected dependencies
        experimentService = injector.getInstance(ExperimentService.class);
        experimentAggregatesService = injector.getInstance(ExperimentAggregatesService.class);
    }

    void mockTargetWorkspace(String apiKey, String workspaceName, String workspaceId) {
        AuthTestUtils.mockTargetWorkspace(wireMock.server(), apiKey, workspaceName, workspaceId, USER);
    }

    @AfterAll
    void tearDownAll() {
        wireMock.server().stop();
    }

    @BeforeEach
    void setUp() {
        // Clean up before each test if needed
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countFilterScenarios")
    @DisplayName("ExperimentDAO.FIND_COUNT matches FIND_COUNT_FROM_AGGREGATES")
    void experimentCountMatchesAggregates(
            String scenarioName,
            Function<CountTestData, ExperimentSearchCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        // Given: Create test data
        var namePrefix = "count-filter-test-" + UUID.randomUUID() + "-";

        List<Project> projects = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        // Use named experiments with alternating REGULAR/TRIAL types to cover name and types filters
        List<Experiment> experiments = IntStream.range(0, 5)
                .mapToObj(i -> createNamedExperiment(
                        datasets.get(i),
                        namePrefix + i,
                        i % 2 == 0 ? ExperimentType.REGULAR : ExperimentType.TRIAL,
                        apiKey, workspaceName))
                .toList();

        // Create experiment items with feedback scores
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, 5)
                .parallel()
                .forEach(i -> {

                    Project project = projects.get(i);
                    Dataset dataset = datasets.get(i);
                    Experiment experiment = experiments.get(i);

                    createExperimentItemWithData(
                            experiment.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores, apiKey, workspaceName);
                });

        // Populate aggregates
        experiments.parallelStream().forEach(experiment -> {
            experimentAggregatesService.populateAggregations(experiment.id())
                    .contextWrite(ctx -> ctx
                            .put(RequestContext.USER_NAME, USER)
                            .put(RequestContext.WORKSPACE_ID, workspaceId))
                    .block();
        });

        // Build criteria using the provided function
        var testData = new CountTestData(
                datasets.stream().map(Dataset::id).toList(),
                experiments.stream().map(Experiment::id).toList(),
                projects.stream().map(Project::id).toList(),
                feedbackScores,
                namePrefix);
        var criteria = criteriaBuilder.apply(testData);

        // When: Count using both methods
        var countFromRaw = experimentService.find(1, 10, criteria)
                .map(Experiment.ExperimentPage::total)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var countFromAggregates = experimentAggregatesService.countTotal(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Both should return the same count
        assertThat(countFromAggregates)
                .as("Count from aggregates should match count from raw data for scenario: %s", scenarioName)
                .isEqualTo(countFromRaw);
    }

    Stream<Arguments> countFilterScenarios() {
        return Stream.of(
                Arguments.of("No filters",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by dataset",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomDataset = data.datasetIds().get(random.nextInt(data.datasetIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .datasetId(randomDataset)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Filter by experiment IDs",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomExperiment = data.experimentIds()
                                    .get(random.nextInt(data.experimentIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .experimentIds(Set.of(randomExperiment))
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Filter by dataset IDs",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .datasetIds(Set.copyOf(data.datasetIds()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by project_id",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomProject = data.projectIds()
                                    .get(random.nextInt(data.projectIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .projectId(randomProject)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Combined filters: dataset + project_id",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomDataset = data.datasetIds()
                                    .get(random.nextInt(data.datasetIds().size()));
                            var randomProject = data.projectIds()
                                    .get(random.nextInt(data.projectIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .datasetId(randomDataset)
                                    .projectId(randomProject)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Combined filters: experiment IDs + project_id",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> {
                            var randomExperiment = data.experimentIds()
                                    .get(random.nextInt(data.experimentIds().size()));
                            var randomProject = data.projectIds()
                                    .get(random.nextInt(data.projectIds().size()));
                            return ExperimentSearchCriteria.builder()
                                    .experimentIds(Set.of(randomExperiment))
                                    .projectId(randomProject)
                                    .entityType(EntityType.TRACE)
                                    .sortingFields(List.of())
                                    .build();
                        }),
                Arguments.of("Filter by feedback scores > 0",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.FEEDBACK_SCORES)
                                        .key(data.scoreNames().getFirst())
                                        .value("0")
                                        .operator(Operator.GREATER_THAN)
                                        .build()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by feedback scores not empty",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.FEEDBACK_SCORES)
                                        .key(data.scoreNames().getLast())
                                        .value("")
                                        .operator(Operator.IS_NOT_EMPTY)
                                        .build()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by feedback scores empty",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.FEEDBACK_SCORES)
                                        .key(UUID.randomUUID().toString())
                                        .value("")
                                        .operator(Operator.IS_EMPTY)
                                        .build()))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by name",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .name(data.namePrefix())
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by types REGULAR",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .types(Set.of(ExperimentType.REGULAR))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()),
                Arguments.of("Filter by types TRIAL",
                        (Function<CountTestData, ExperimentSearchCriteria>) data -> ExperimentSearchCriteria
                                .builder()
                                .types(Set.of(ExperimentType.TRIAL))
                                .entityType(EntityType.TRACE)
                                .sortingFields(List.of())
                                .build()));
    }

    private record CountTestData(List<UUID> datasetIds, List<UUID> experimentIds, List<UUID> projectIds,
            List<String> scoreNames, String namePrefix) {
    }

    @Test
    @DisplayName("ExperimentDAO.FIND calculated values match experiment_aggregates stored values")
    void experimentDaoFindMatchesAggregates() {
        // Given: Create experiment with known test data
        // Given: Create test data
        List<Project> projects = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createProject(API_KEY, TEST_WORKSPACE))
                .toList();

        List<Dataset> datasets = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createDataset(API_KEY, TEST_WORKSPACE))
                .toList();

        List<Experiment> experiments = datasets
                .parallelStream()
                .map(dataset -> createExperiment(dataset, API_KEY, TEST_WORKSPACE))
                .toList();

        // Create experiment items with specific feedback scores for verification
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);

        IntStream.range(0, 5)
                .parallel()
                .forEach(i -> {
                    Project project = projects.get(i);
                    Dataset dataset = datasets.get(i);
                    Experiment experiment = experiments.get(i);

                    createExperimentItemWithData(
                            experiment.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores, API_KEY, TEST_WORKSPACE);
                });

        var searchCriteria = ExperimentSearchCriteria.builder()
                .experimentIds(Set.of(experiments.getFirst().id()))
                .entityType(EntityType.TRACE)
                .sortingFields(List.of())
                .build();

        // When: Query BEFORE aggregation (calculates from raw data)
        var beforeAggregation = experimentService.find(1, 10, searchCriteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        assertThat(beforeAggregation).isNotNull();
        assertThat(beforeAggregation.content()).hasSize(1);
        var rawExperiment = beforeAggregation.content().getFirst();

        // Populate experiment aggregates
        experimentAggregatesService.populateAggregations(experiments.getFirst().id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        // Query experiment_aggregates table directly using service method
        var experimentFromAggregates = experimentAggregatesService
                .getExperimentFromAggregates(experiments.getFirst().id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, WORKSPACE_ID))
                .block();

        // Then: Verify raw calculation matches stored aggregates using recursive comparison
        assertThat(experimentFromAggregates).as("Experiment from aggregates should not be null").isNotNull();

        assertThat(experimentFromAggregates)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringFields(EXPERIMENT_AGGREGATED_FIELDS_TO_IGNORE)
                .ignoringCollectionOrderInFields("experimentScores", "feedbackScores")
                .isEqualTo(rawExperiment);

        // Fields not stored in experiment_aggregates table are expected to be null
        assertThat(experimentFromAggregates.datasetName())
                .as("datasetName is not stored in aggregates")
                .isNull();
        assertThat(experimentFromAggregates.projectName())
                .as("projectName is not stored in aggregates")
                .isNull();
        assertThat(experimentFromAggregates.promptVersion())
                .as("promptVersion is not stored in aggregates")
                .isNull();
        assertThat(experimentFromAggregates.datasetVersionSummary())
                .as("datasetVersionSummary is not stored in aggregates")
                .isNull();
        assertThat(experimentFromAggregates.datasetItemCount())
                .as("datasetItemCount is not stored in aggregates")
                .isNull();
    }

    @ParameterizedTest(name = "Group by {0}")
    @MethodSource("groupingTestCases")
    @DisplayName("ExperimentAggregatesService.findGroups matches ExperimentService.findGroups (raw)")
    void testFindGroupsFromAggregates(String testName, List<GroupBy> groups) {
        // Given: Isolate test with unique workspace
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        int numberOfItems = 5;

        // Create experiments with different grouping values
        List<Project> projects = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        List<Experiment> experiments = datasets.stream()
                .map(dataset -> createExperiment(dataset, apiKey, workspaceName))
                .toList();

        // Create experiment items for each experiment
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, numberOfItems).parallel().forEach(i -> {
            createExperimentItemWithData(
                    experiments.get(i).id(),
                    datasets.get(i).id(),
                    projects.get(i).name(),
                    feedbackScores, apiKey, workspaceName);
        });

        // Populate aggregates for all experiments
        experiments.parallelStream()
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // When: Query groups using both methods (both return Mono<ExperimentGroupResponse>)
        var criteria = com.comet.opik.api.ExperimentGroupCriteria.builder()
                .groups(groups)
                .build();

        // Query using raw calculation (ExperimentService -> ExperimentDAO)
        var groupsFromRaw = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query using aggregates (ExperimentAggregatesService -> ExperimentAggregatesDAO)
        var groupsFromAggregates = experimentAggregatesService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Verify both return non-null results
        assertThat(groupsFromRaw).isNotNull();
        assertThat(groupsFromAggregates).isNotNull();
        assertThat(groupsFromRaw.content()).isNotEmpty();
        assertThat(groupsFromAggregates.content()).isNotEmpty();

        // Compare the ExperimentGroupResponse objects from both methods
        assertThat(groupsFromAggregates)
                .as("Groups from aggregates should match groups from raw data for scenario: %s", testName)
                .usingRecursiveComparison()
                .isEqualTo(groupsFromRaw);
    }

    @ParameterizedTest(name = "Group aggregations by {0}")
    @MethodSource("groupingTestCases")
    @DisplayName("ExperimentAggregatesService.findGroupsAggregations matches ExperimentService.findGroupsAggregations (raw)")
    void testFindGroupsAggregationsFromAggregates(String testName, List<GroupBy> groups) {
        // Given: Isolate test with unique workspace
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        int numberOfItems = 5;
        // Create experiments with different grouping values
        List<Project> projects = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, numberOfItems)
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        List<Experiment> experiments = datasets.stream()
                .map(dataset -> createExperiment(dataset, apiKey, workspaceName))
                .toList();

        // Create experiment items for each experiment
        List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, numberOfItems).parallel().forEach(i -> {
            createExperimentItemWithData(
                    experiments.get(i).id(),
                    datasets.get(i).id(),
                    projects.get(i).name(),
                    feedbackScores, apiKey, workspaceName);
        });

        // Populate aggregates for all experiments
        experiments.forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block());

        // When: Query group aggregations using both methods (both return Mono<ExperimentGroupAggregationsResponse>)
        var criteria = com.comet.opik.api.ExperimentGroupCriteria.builder()
                .groups(groups)
                .build();

        // Query using raw calculation (ExperimentService -> ExperimentDAO)
        var aggregationsFromRaw = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query using aggregates (ExperimentAggregatesService -> ExperimentAggregatesDAO)
        var aggregationsFromAggregates = experimentAggregatesService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Verify both return non-null results
        assertThat(aggregationsFromRaw).isNotNull();
        assertThat(aggregationsFromAggregates).isNotNull();
        assertThat(aggregationsFromRaw.content()).isNotEmpty();
        assertThat(aggregationsFromAggregates.content()).isNotEmpty();

        // Compare the ExperimentGroupAggregationsResponse objects from both methods
        assertThat(aggregationsFromAggregates)
                .as("Group aggregations from aggregates should match aggregations from raw data for scenario: %s",
                        testName)
                .usingRecursiveComparison(
                        RecursiveComparisonConfiguration.builder()
                                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                                .build())
                .ignoringCollectionOrderInFields("feedbackScores", "experimentScores")
                .isEqualTo(aggregationsFromRaw);
    }

    @ParameterizedTest(name = "Criteria filter: {0}")
    @MethodSource("groupingCriteriaFilterTestCases")
    @DisplayName("ExperimentAggregatesService.findGroups: aggregate result matches raw when criteria filters are applied")
    void testFindGroupsWithCriteriaFilters(
            String testName,
            Function<GroupCriteriaTestData, ExperimentGroupCriteria> criteriaBuilder) {

        // Given: Isolated workspace with experiments that have known names, types, and project associations
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var namePrefix = "criteria-filter-test-" + UUID.randomUUID() + "-";
        var project1 = createProject(apiKey, workspaceName);
        var project2 = createProject(apiKey, workspaceName);
        var dataset1 = createDataset(apiKey, workspaceName);
        var dataset2 = createDataset(apiKey, workspaceName);

        // Two REGULAR experiments linked to project1 via dataset1
        var exp1 = createNamedExperiment(dataset1, namePrefix + "alpha", ExperimentType.REGULAR, apiKey, workspaceName);
        var exp2 = createNamedExperiment(dataset1, namePrefix + "beta", ExperimentType.REGULAR, apiKey, workspaceName);
        // One TRIAL experiment linked to project2 via dataset2
        var exp3 = createNamedExperiment(dataset2, namePrefix + "gamma", ExperimentType.TRIAL, apiKey, workspaceName);

        var feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(exp1.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp2.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp3.id(), dataset2.id(), project2.name(), feedbackScores, apiKey, workspaceName);

        List.of(exp1, exp2, exp3)
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // When: Build criteria and query both raw and aggregate paths
        var testData = new GroupCriteriaTestData(
                project1.id(),
                namePrefix,
                List.of(
                        GroupBy.builder().field(GroupingFactory.DATASET_ID).type(FieldType.STRING).build(),
                        GroupBy.builder().field(GroupingFactory.PROJECT_ID).type(FieldType.STRING).build()));

        var criteria = criteriaBuilder.apply(testData);

        var groupsFromRaw = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var groupsFromAggregates = experimentAggregatesService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Both paths must return identical results
        assertGroupsMatch(groupsFromRaw, groupsFromAggregates, testName);
    }

    @ParameterizedTest(name = "Criteria filter: {0}")
    @MethodSource("groupingCriteriaFilterTestCases")
    @DisplayName("ExperimentAggregatesService.findGroupsAggregations: aggregate result matches raw when criteria filters are applied")
    void testFindGroupsAggregationsWithCriteriaFilters(
            String testName,
            Function<GroupCriteriaTestData, ExperimentGroupCriteria> criteriaBuilder) {

        // Given: Isolated workspace with experiments that have known names, types, and project associations
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var namePrefix = "criteria-filter-test-" + UUID.randomUUID() + "-";
        var project1 = createProject(apiKey, workspaceName);
        var project2 = createProject(apiKey, workspaceName);
        var dataset1 = createDataset(apiKey, workspaceName);
        var dataset2 = createDataset(apiKey, workspaceName);

        // Two REGULAR experiments linked to project1 via dataset1
        var exp1 = createNamedExperiment(dataset1, namePrefix + "alpha", ExperimentType.REGULAR, apiKey, workspaceName);
        var exp2 = createNamedExperiment(dataset1, namePrefix + "beta", ExperimentType.REGULAR, apiKey, workspaceName);
        // One TRIAL experiment linked to project2 via dataset2
        var exp3 = createNamedExperiment(dataset2, namePrefix + "gamma", ExperimentType.TRIAL, apiKey, workspaceName);

        var feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(exp1.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp2.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp3.id(), dataset2.id(), project2.name(), feedbackScores, apiKey, workspaceName);

        List.of(exp1, exp2, exp3)
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // When: Build criteria and query both raw and aggregate paths
        var testData = new GroupCriteriaTestData(
                project1.id(),
                namePrefix,
                List.of(
                        GroupBy.builder().field(GroupingFactory.DATASET_ID).type(FieldType.STRING).build(),
                        GroupBy.builder().field(GroupingFactory.PROJECT_ID).type(FieldType.STRING).build()));

        var criteria = criteriaBuilder.apply(testData);

        var aggregationsFromRaw = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        var aggregationsFromAggregates = experimentAggregatesService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Then: Both paths must return identical results
        assertGroupsAggregationsMatch(aggregationsFromRaw, aggregationsFromAggregates, testName);
    }

    static Stream<Arguments> groupingTestCases() {
        return Stream.of(
                Arguments.of("dataset_id", List.of(
                        GroupBy.builder()
                                .field(GroupingFactory.DATASET_ID)
                                .type(FieldType.STRING)
                                .build())),
                Arguments.of("project_id", List.of(
                        GroupBy.builder()
                                .field(GroupingFactory.PROJECT_ID)
                                .type(FieldType.STRING)
                                .build())),
                Arguments.of("dataset_id and project_id", List.of(
                        GroupBy.builder()
                                .field(GroupingFactory.DATASET_ID)
                                .type(FieldType.STRING)
                                .build(),
                        GroupBy.builder()
                                .field(GroupingFactory.PROJECT_ID)
                                .type(FieldType.STRING)
                                .build())));
    }

    private record GroupCriteriaTestData(UUID projectId, String namePrefix, List<GroupBy> groups) {
    }

    static Stream<Arguments> groupingCriteriaFilterTestCases() {
        return Stream.of(
                Arguments.of("Filter by name prefix (partial match)",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .name(data.namePrefix())
                                .build()),
                Arguments.of("Filter by type REGULAR",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .types(Set.of(ExperimentType.REGULAR))
                                .build()),
                Arguments.of("Filter by type TRIAL",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .types(Set.of(ExperimentType.TRIAL))
                                .build()),
                Arguments.of("Filter by projectId",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .projectId(data.projectId())
                                .build()),
                Arguments.of("Filter by name prefix AND type REGULAR",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .name(data.namePrefix())
                                .types(Set.of(ExperimentType.REGULAR))
                                .build()),
                Arguments.of("Filter by name with no matches (empty result)",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .name("nonexistent-experiment-name-" + UUID.randomUUID())
                                .build()),
                Arguments.of("Filter by experiment-level filter (tags IS_EMPTY exercises filters template var)",
                        (Function<GroupCriteriaTestData, ExperimentGroupCriteria>) data -> ExperimentGroupCriteria
                                .builder()
                                .groups(data.groups())
                                .filters(List.of(ExperimentFilter.builder()
                                        .field(ExperimentField.TAGS)
                                        .operator(Operator.IS_EMPTY)
                                        .value("")
                                        .build()))
                                .build()));
    }

    private void assertGroupsMatch(ExperimentGroupResponse fromRaw, ExperimentGroupResponse fromAggregates,
            String testName) {
        assertThat(fromRaw).as("Groups from raw should not be null for: %s", testName).isNotNull();
        assertThat(fromAggregates).as("Groups from aggregates should not be null for: %s", testName).isNotNull();
        assertThat(fromAggregates)
                .as("Groups from aggregates should match raw for: %s", testName)
                .usingRecursiveComparison()
                .isEqualTo(fromRaw);
    }

    private void assertGroupsAggregationsMatch(ExperimentGroupAggregationsResponse fromRaw,
            ExperimentGroupAggregationsResponse fromAggregates, String testName) {
        assertThat(fromRaw).as("Aggregations from raw should not be null for: %s", testName).isNotNull();
        assertThat(fromAggregates).as("Aggregations from aggregates should not be null for: %s", testName)
                .isNotNull();
        assertThat(fromAggregates)
                .as("Group aggregations from aggregates should match raw for: %s", testName)
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .build())
                .ignoringCollectionOrderInFields("feedbackScores", "experimentScores")
                .isEqualTo(fromRaw);
    }

    // Helper methods

    private Project createProject(String apiKey, String workspaceName) {
        var projectName = "test-project-" + UUID.randomUUID();
        var projectId = projectResourceClient.createProject(projectName, apiKey, workspaceName);
        return projectResourceClient.getProject(projectId, apiKey, workspaceName);
    }

    private Dataset createDataset(String apiKey, String workspaceName) {
        var dataset = DatasetResourceClient.buildDataset(factory);
        datasetResourceClient.createDataset(dataset, apiKey, workspaceName);
        return dataset;
    }

    private Experiment createExperiment(Dataset dataset, String apiKey, String workspaceName) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetId(dataset.id())
                .datasetName(dataset.name()) // Use the actual dataset's name
                .build();
        experimentResourceClient.create(experiment, apiKey, workspaceName);
        return experiment;
    }

    private Experiment createNamedExperiment(Dataset dataset, String name, ExperimentType type,
            String apiKey, String workspaceName) {
        var experiment = experimentResourceClient.createPartialExperiment()
                .datasetId(dataset.id())
                .datasetName(dataset.name())
                .name(name)
                .type(type)
                .build();
        experimentResourceClient.create(experiment, apiKey, workspaceName);
        return experiment;
    }

    private List<ExperimentItem> createExperimentItemWithData(UUID experimentId, UUID datasetId, String projectName,
            List<String> feedbackScores, String apiKey, String workspaceName) {

        // Create dataset item
        var datasetItemIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        var datasetItems = PodamFactoryUtils.manufacturePojoList(factory, DatasetItem.class)
                .stream()
                .map(item -> {
                    int dIdx = datasetItemIndex.getAndIncrement();
                    return item.toBuilder()
                            .datasetId(datasetId)
                            .traceId(null)
                            .experimentItems(null)
                            .spanId(null)
                            .source(DatasetItemSource.SDK)
                            .description("desc-" + (char) ('a' + dIdx) + "-" + UUID.randomUUID())
                            .tags(Set.of("tag-" + (char) ('a' + dIdx) + "-" + UUID.randomUUID()))
                            .build();
                })
                .toList();

        var batch = DatasetItemBatch.builder()
                .datasetId(datasetId)
                .items(datasetItems)
                .build();

        datasetResourceClient.createDatasetItems(batch, workspaceName, apiKey);

        // Create experiment item
        var itemIndex = new java.util.concurrent.atomic.AtomicInteger(0);
        List<ExperimentItem> experimentItems = datasetItems.stream()
                .map(datasetItem -> {
                    int idx = itemIndex.getAndIncrement();

                    // Create trace with output containing unique values per item for stable sorting
                    var outputNode = JsonUtils.getJsonNodeFromString(
                            "{\"result\": \"output-" + (char) ('a' + idx) + "-" + UUID.randomUUID() + "\"}");
                    var baseTrace = factory.manufacturePojo(Trace.class)
                            .toBuilder()
                            .projectName(projectName)
                            .usage(null)
                            .visibilityMode(null)
                            .output(outputNode)
                            .build();
                    // Override endTime to produce distinct durations per trace for stable sorting
                    var trace = baseTrace.toBuilder()
                            .endTime(baseTrace.startTime().plusSeconds((idx + 1) * 10L))
                            .build();

                    traceResourceClient.createTrace(trace, apiKey, workspaceName);

                    // Create spans for the trace

                    var spans = PodamFactoryUtils.manufacturePojoList(factory, Span.class)
                            .stream()
                            .map(span -> span
                                    .toBuilder()
                                    .projectName(projectName)
                                    .traceId(trace.id())
                                    .parentSpanId(null)
                                    .usage(spanResourceClient.getTokenUsage())
                                    .build())
                            .toList();

                    spanResourceClient.batchCreateSpans(spans, apiKey, workspaceName);

                    // Create feedback scores
                    List<FeedbackScoreBatchItem> feedbackScoreItems = (List<FeedbackScoreBatchItem>) feedbackScores
                            .stream()
                            .map(name -> factory.manufacturePojo(FeedbackScoreBatchItem.class).builder()
                                    .id(trace.id())
                                    .projectName(projectName)
                                    .name(name)
                                    .value(BigDecimal.valueOf(Math.random() * 10))
                                    .source(ScoreSource.SDK)
                                    .build())
                            .toList();

                    if (!feedbackScoreItems.isEmpty()) {
                        traceResourceClient.feedbackScores(feedbackScoreItems, apiKey, workspaceName);
                    }

                    return ExperimentItem.builder()
                            .experimentId(experimentId)
                            .datasetItemId(datasetItem.id())
                            .traceId(trace.id())
                            .build();
                })
                .toList();

        experimentResourceClient.createExperimentItem(Set.copyOf(experimentItems), apiKey, workspaceName);
        return experimentItems;
    }

    private record AggregatesTestContext(
            String workspaceName,
            String apiKey,
            String workspaceId,
            DatasetItemCountTestData testData) {
    }

    private AggregatesTestContext setupAggregatesTestData(
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        List<List<UUID>> experimentIdsList = Collections.synchronizedList(new ArrayList<>());
        List<List<UUID>> datasetIdsList = Collections.synchronizedList(new ArrayList<>());
        List<List<String>> feedbackScoresList = Collections.synchronizedList(new ArrayList<>());

        IntStream.range(0, 2)
                .parallel()
                .forEach(i -> {
                    var project = createProject(apiKey, workspaceName);
                    var dataset = createDataset(apiKey, workspaceName);
                    var experiment1 = createExperiment(dataset, apiKey, workspaceName);
                    var experiment2 = createExperiment(dataset, apiKey, workspaceName);

                    List<String> feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);

                    createExperimentItemWithData(
                            experiment1.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);
                    createExperimentItemWithData(
                            experiment2.id(),
                            dataset.id(),
                            project.name(),
                            feedbackScores,
                            apiKey,
                            workspaceName);

                    experimentIdsList.add(List.of(experiment1.id(), experiment2.id()));
                    datasetIdsList.add(List.of(dataset.id()));
                    feedbackScoresList.add(feedbackScores);
                });

        experimentIdsList
                .parallelStream()
                .flatMap(List::stream)
                .forEach(experimentId -> experimentAggregatesService.populateAggregations(experimentId)
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        var testData = new DatasetItemCountTestData(
                datasetIdsList.getFirst().getFirst(),
                Set.copyOf(experimentIdsList.getFirst()),
                feedbackScoresList.getFirst());

        return new AggregatesTestContext(workspaceName, apiKey, workspaceId, testData);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("countDatasetItemsWithExperimentItems matches countDatasetItemsWithExperimentItemsFromAggregates")
    void testDatasetItemCountWithAggregates(String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        var ctx = setupAggregatesTestData(criteriaBuilder);
        var criteria = criteriaBuilder.apply(ctx.testData());

        // When: Get count from original service method
        var datasetItemPage = datasetResourceClient.getDatasetItemsWithExperimentItems(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                criteria.search(),
                criteria.filters(),
                ctx.apiKey(),
                ctx.workspaceName());

        var countFromOriginal = datasetItemPage.total();

        // When: Get count from aggregates (new version)
        var countFromAggregates = experimentAggregatesService
                .countDatasetItemsWithExperimentItemsFromAggregates(criteria)
                .contextWrite(reactorCtx -> reactorCtx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, ctx.workspaceId()))
                .block();

        // Then: Both should return the same count
        assertThat(countFromAggregates)
                .as("Count from aggregates should match count from original DAO for scenario: %s",
                        scenarioName)
                .isEqualTo(countFromOriginal);
    }

    Stream<Arguments> datasetItemCountFilterScenarios() {
        return Stream.of(
                Arguments.of("No filters",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by duration > 0",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.DURATION.getQueryParamField())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by feedback score with key",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES
                                                .getQueryParamField())
                                        .key(data.feedbackScores().getFirst())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by feedback score is not empty with key",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES
                                                .getQueryParamField())
                                        .key(data.feedbackScores().getFirst())
                                        .operator(Operator.IS_NOT_EMPTY)
                                        .value("")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by feedback score is empty with key",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.FEEDBACK_SCORES
                                                .getQueryParamField())
                                        .key(UUID.randomUUID().toString())
                                        .operator(Operator.IS_EMPTY)
                                        .value("")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Filter by output field (dynamic)",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field("output.result")
                                        .operator(Operator.CONTAINS)
                                        .value(data.experimentIds().iterator().next().toString())
                                        .type(FieldType.STRING)
                                        .build()))
                                .truncate(false)
                                .build()),

                Arguments.of("Search in input/output",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .search(data.experimentIds().iterator().next().toString())
                                .truncate(false)
                                .build()),

                Arguments.of("Combined filter and search",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.DURATION.getQueryParamField())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .search(data.experimentIds().iterator().next().toString())
                                .truncate(false)
                                .build()),

                Arguments.of("Combined filter and search with truncation",
                        (Function<DatasetItemCountTestData, DatasetItemSearchCriteria>) data -> DatasetItemSearchCriteria
                                .builder()
                                .datasetId(data.datasetId())
                                .experimentIds(data.experimentIds())
                                .entityType(EntityType.TRACE)
                                .filters(List.of(ExperimentsComparisonFilter.builder()
                                        .field(ExperimentsComparisonValidKnownField.DURATION.getQueryParamField())
                                        .operator(Operator.GREATER_THAN)
                                        .value("0")
                                        .type(FieldType.NUMBER)
                                        .build()))
                                .truncate(true)
                                .build()));
    }

    private record DatasetItemCountTestData(
            UUID datasetId,
            Set<UUID> experimentIds,
            List<String> feedbackScores) {
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("getDatasetItemsWithExperimentItemsFromAggregates: Compare with original DAO")
    void getDatasetItemsWithExperimentItemsFromAggregates(
            String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        var ctx = setupAggregatesTestData(criteriaBuilder);
        var criteria = criteriaBuilder.apply(ctx.testData());

        // When: Get items from original service method
        var pageFromOriginal = datasetResourceClient.getDatasetItemsWithExperimentItems(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                criteria.search(),
                criteria.filters(),
                ctx.apiKey(),
                ctx.workspaceName());

        // When: Get items from aggregates
        var pageFromAggregates = experimentAggregatesService
                .getDatasetItemsWithExperimentItemsFromAggregates(criteria, 1, 10)
                .contextWrite(reactorCtx -> reactorCtx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, ctx.workspaceId()))
                .block();

        // Then: Verify page properties match
        assertThat(pageFromAggregates.page())
                .as("Page number should match for scenario: %s", scenarioName)
                .isEqualTo(pageFromOriginal.page());

        assertThat(pageFromAggregates.total())
                .as("Total count should match for scenario: %s", scenarioName)
                .isEqualTo(pageFromOriginal.total());

        assertThat(pageFromAggregates.size())
                .as("Page size should match for scenario: %s", scenarioName)
                .isEqualTo(pageFromOriginal.size());

        // Then: Verify content matches exactly
        assertDatasetItemsWithExperimentItems(pageFromOriginal.content(), pageFromAggregates.content());
    }

    void assertDatasetItemsWithExperimentItems(List<DatasetItem> expectedDatasetItem,
            List<DatasetItem> actualDatasetItems) {

        assertThat(actualDatasetItems)
                .usingRecursiveFieldByFieldElementComparatorIgnoringFields(IGNORED_FIELDS_DATA_ITEM)
                .isEqualTo(expectedDatasetItem);

        for (var i = 0; i < actualDatasetItems.size(); i++) {
            var actualExperiments = actualDatasetItems.get(i).experimentItems();
            var expectedExperiments = expectedDatasetItem.get(i).experimentItems();

            // Filtering by those related to experiments 1 and 3
            assertThat(actualExperiments)
                    .usingRecursiveComparison()
                    .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                    .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "duration")
                    .ignoringCollectionOrderInFields("feedbackScores")
                    .ignoringFields(IGNORED_FIELDS_EXPERIMENT_ITEM)
                    .isEqualTo(expectedExperiments);
        }
    }

    private <T> void assertPageNotEmpty(com.comet.opik.api.Page<T> page) {
        assertThat(page).isNotNull();
        assertThat(page.content()).isNotEmpty();
    }

    private void assertPageNotEmpty(ExperimentGroupResponse response) {
        assertThat(response).isNotNull();
        assertThat(response.content()).isNotEmpty();
    }

    private void assertPageNotEmpty(ExperimentGroupAggregationsResponse response) {
        assertThat(response).isNotNull();
        assertThat(response.content()).isNotEmpty();
    }

    private <T> void assertPagesMatchForFind(com.comet.opik.api.Page<T> expected, com.comet.opik.api.Page<T> actual,
            String description) {
        assertThat(actual).isNotNull();
        assertThat(actual)
                .as(description)
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .build())
                .ignoringCollectionOrderInFields("content.feedbackScores", "content.experimentScores")
                .isEqualTo(expected);
    }

    private void assertPagesMatchForFindGroups(ExperimentGroupResponse expected,
            ExperimentGroupResponse actual, String description) {
        assertThat(actual).isNotNull();
        assertThat(actual)
                .as(description)
                .usingRecursiveComparison()
                .isEqualTo(expected);
    }

    private void assertPagesMatchForFindGroupsAggregations(ExperimentGroupAggregationsResponse expected,
            ExperimentGroupAggregationsResponse actual, String description) {
        assertThat(actual).isNotNull();
        assertThat(actual)
                .as(description)
                .usingRecursiveComparison(RecursiveComparisonConfiguration.builder()
                        .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                        .build())
                .ignoringCollectionOrderInFields("feedbackScores", "experimentScores")
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("getExperimentItemsStats: aggregates should match original calculation with filters")
    void getExperimentItemsStatsFromAggregates(String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        var ctx = setupAggregatesTestData(criteriaBuilder);
        var criteria = criteriaBuilder.apply(ctx.testData());

        @SuppressWarnings("unchecked")
        List<com.comet.opik.api.filter.ExperimentsComparisonFilter> filters = (List<com.comet.opik.api.filter.ExperimentsComparisonFilter>) criteria
                .filters();

        // When: Get stats from original DAO
        var statsFromOriginal = datasetResourceClient.getDatasetExperimentItemsStats(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                ctx.apiKey(),
                ctx.workspaceName(),
                filters);

        // When: Get stats from aggregates DAO
        var statsFromAggregates = experimentAggregatesService
                .getExperimentItemsStatsFromAggregates(
                        criteria.datasetId(),
                        criteria.experimentIds(),
                        filters)
                .contextWrite(reactorCtx -> reactorCtx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, ctx.workspaceId()))
                .block();

        // Then: Verify stats match
        assertThat(statsFromAggregates)
                .as("Stats from aggregates should not be null for scenario: %s", scenarioName)
                .isNotNull();

        assertThat(statsFromOriginal)
                .as("Stats from original should not be null for scenario: %s", scenarioName)
                .isNotNull();

        assertThat(statsFromAggregates)
                .as("Stats should match for scenario: %s", scenarioName)
                .usingRecursiveComparison()
                .withComparatorForType(StatsUtils::bigDecimalComparator, BigDecimal.class)
                .withComparatorForType(StatsUtils::compareDoubles, Double.class)
                .withComparatorForFields(StatsUtils::closeToEpsilonComparator, "value")
                .ignoringCollectionOrder()
                .isEqualTo(statsFromOriginal);
    }

    @Test
    @DisplayName("ExperimentDAO.FIND returns consistent results before and after populating experiment_aggregates (UNION ALL hybrid)")
    void experimentFindIsConsistentBeforeAndAfterAggregates() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment = createExperiment(dataset, apiKey, workspaceName);
        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(experiment.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var criteria = ExperimentSearchCriteria.builder()
                .experimentIds(Set.of(experiment.id()))
                .entityType(EntityType.TRACE)
                .sortingFields(List.of())
                .build();

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = experimentService.find(1, 10, criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPageNotEmpty(beforeAggregation);

        // Populate experiment_aggregates
        experimentAggregatesService.populateAggregations(experiment.id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = experimentService.find(1, 10, criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPagesMatchForFind(beforeAggregation, afterAggregation,
                "FIND must return identical results before and after populating experiment_aggregates");
    }

    @Test
    @DisplayName("ExperimentDAO.FIND_GROUPS returns consistent results before and after populating experiment_aggregates (UNION ALL hybrid)")
    void experimentFindGroupsIsConsistentBeforeAndAfterAggregates() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment = createExperiment(dataset, apiKey, workspaceName);
        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(experiment.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var criteria = ExperimentGroupCriteria.builder()
                .groups(List.of(
                        GroupBy.builder().field(GroupingFactory.DATASET_ID).type(FieldType.STRING).build()))
                .build();

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPageNotEmpty(beforeAggregation);

        // Populate experiment_aggregates
        experimentAggregatesService.populateAggregations(experiment.id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPagesMatchForFindGroups(beforeAggregation, afterAggregation,
                "FIND_GROUPS must return identical results before and after populating experiment_aggregates");
    }

    @Test
    @DisplayName("ExperimentDAO.FIND_GROUPS_AGGREGATIONS returns consistent results before and after populating experiment_aggregates (UNION ALL hybrid)")
    void experimentFindGroupsAggregationsIsConsistentBeforeAndAfterAggregates() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment = createExperiment(dataset, apiKey, workspaceName);
        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(experiment.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var criteria = ExperimentGroupCriteria.builder()
                .groups(List.of(
                        GroupBy.builder().field(GroupingFactory.DATASET_ID).type(FieldType.STRING).build()))
                .build();

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPageNotEmpty(beforeAggregation);

        // Populate experiment_aggregates
        experimentAggregatesService.populateAggregations(experiment.id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPagesMatchForFindGroupsAggregations(beforeAggregation, afterAggregation,
                "FIND_GROUPS_AGGREGATIONS must return identical results before and after populating experiment_aggregates");
    }

    @Test
    @DisplayName("ExperimentItemDAO.STREAM returns consistent results before and after populating experiment_item_aggregates (UNION ALL hybrid)")
    void streamExperimentItemsIsConsistentBeforeAndAfterAggregates() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment1 = createExperiment(dataset, apiKey, workspaceName);
        var experiment2 = createExperiment(dataset, apiKey, workspaceName);

        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(experiment1.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);
        createExperimentItemWithData(experiment2.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var experimentIds = List.of(experiment1.id(), experiment2.id());

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), experimentIds, null, null, apiKey, workspaceName);

        assertPageNotEmpty(beforeAggregation);

        // Populate experiment_item_aggregates
        experimentIds.forEach(id -> experimentAggregatesService.populateAggregations(id)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block());

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), experimentIds, null, null, apiKey, workspaceName);

        assertPageNotEmpty(afterAggregation);
        assertDatasetItemsWithExperimentItems(beforeAggregation.content(), afterAggregation.content());
    }

    @Test
    @DisplayName("ExperimentItemDAO.STREAM returns consistent results in mixed state (some experiments aggregated, some not)")
    void streamExperimentItemsIsConsistentInMixedAggregationState() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment1 = createExperiment(dataset, apiKey, workspaceName);
        var experiment2 = createExperiment(dataset, apiKey, workspaceName);
        var experiment3 = createExperiment(dataset, apiKey, workspaceName);

        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(experiment1.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);
        createExperimentItemWithData(experiment2.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);
        createExperimentItemWithData(experiment3.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var experimentIds = List.of(experiment1.id(), experiment2.id(), experiment3.id());

        // Query BEFORE any aggregation — all raw
        var beforeAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), experimentIds, null, null, apiKey, workspaceName);

        assertPageNotEmpty(beforeAggregation);

        // Aggregate only experiment1 — mixed state: has_aggregated=true AND has_raw=true
        experimentAggregatesService.populateAggregations(experiment1.id())
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        // Query in mixed state — UNION ALL hybrid with both branches active
        var mixedState = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), experimentIds, null, null, apiKey, workspaceName);

        assertPageNotEmpty(mixedState);
        assertDatasetItemsWithExperimentItems(beforeAggregation.content(), mixedState.content());
    }

    @Test
    @DisplayName("ExperimentItemDAO.STREAM returns non-empty results before and after populating experiment_item_aggregates when experiment items have no feedback scores or comments")
    void streamExperimentItemsWithNoScoresIsConsistentBeforeAndAfterAggregates() {
        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment1 = createExperiment(dataset, apiKey, workspaceName);
        var experiment2 = createExperiment(dataset, apiKey, workspaceName);

        // No feedback scores — this exercises the path where LEFT JOIN misses produce null rows
        createExperimentItemWithData(experiment1.id(), dataset.id(), project.name(), List.of(), apiKey, workspaceName);
        createExperimentItemWithData(experiment2.id(), dataset.id(), project.name(), List.of(), apiKey, workspaceName);

        var experimentIds = List.of(experiment1.id(), experiment2.id());

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs, no scores
        var beforeAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), experimentIds, null, null, apiKey, workspaceName);

        assertPageNotEmpty(beforeAggregation);

        // Populate experiment_item_aggregates
        experimentIds.forEach(id -> experimentAggregatesService.populateAggregations(id)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block());

        // Query AFTER populating aggregates — Branch 1: pre-computed data, no scores
        var afterAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), experimentIds, null, null, apiKey, workspaceName);

        assertPageNotEmpty(afterAggregation);
        assertDatasetItemsWithExperimentItems(beforeAggregation.content(), afterAggregation.content());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("countFilterScenarios")
    @DisplayName("ExperimentDAO.FIND returns consistent results before and after populating experiment_aggregates for all filter types (UNION ALL hybrid)")
    void experimentFindIsConsistentForAllFiltersBeforeAndAfterAggregates(
            String scenarioName,
            Function<CountTestData, ExperimentSearchCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var namePrefix = "find-filter-test-" + UUID.randomUUID() + "-";

        List<Project> projects = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createProject(apiKey, workspaceName))
                .toList();

        List<Dataset> datasets = IntStream.range(0, 5)
                .parallel()
                .mapToObj(i -> createDataset(apiKey, workspaceName))
                .toList();

        // Use named experiments with alternating REGULAR/TRIAL types to cover name and types filters
        List<Experiment> experiments = IntStream.range(0, 5)
                .mapToObj(i -> createNamedExperiment(
                        datasets.get(i),
                        namePrefix + i,
                        i % 2 == 0 ? ExperimentType.REGULAR : ExperimentType.TRIAL,
                        apiKey, workspaceName))
                .toList();

        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        IntStream.range(0, 5)
                .parallel()
                .forEach(i -> createExperimentItemWithData(
                        experiments.get(i).id(),
                        datasets.get(i).id(),
                        projects.get(i).name(),
                        feedbackScoreNames, apiKey, workspaceName));

        var testData = new CountTestData(
                datasets.stream().map(Dataset::id).toList(),
                experiments.stream().map(Experiment::id).toList(),
                projects.stream().map(Project::id).toList(),
                feedbackScoreNames,
                namePrefix);
        var criteria = criteriaBuilder.apply(testData);

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = experimentService.find(1, 10, criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertThat(beforeAggregation).isNotNull();

        // Populate experiment_aggregates for all experiments
        experiments.parallelStream()
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = experimentService.find(1, 10, criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPagesMatchForFind(beforeAggregation, afterAggregation,
                "FIND must return identical results before and after populating experiment_aggregates for scenario: %s"
                        .formatted(scenarioName));
    }

    @ParameterizedTest(name = "Criteria filter: {0}")
    @MethodSource("groupingCriteriaFilterTestCases")
    @DisplayName("ExperimentDAO.FIND_GROUPS returns consistent results before and after populating experiment_aggregates for all criteria filter types (UNION ALL hybrid)")
    void experimentFindGroupsIsConsistentForAllFiltersBeforeAndAfterAggregates(
            String scenarioName,
            Function<GroupCriteriaTestData, ExperimentGroupCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var namePrefix = "find-groups-filter-test-" + UUID.randomUUID() + "-";
        var project1 = createProject(apiKey, workspaceName);
        var project2 = createProject(apiKey, workspaceName);
        var dataset1 = createDataset(apiKey, workspaceName);
        var dataset2 = createDataset(apiKey, workspaceName);

        var exp1 = createNamedExperiment(dataset1, namePrefix + "alpha", ExperimentType.REGULAR, apiKey, workspaceName);
        var exp2 = createNamedExperiment(dataset1, namePrefix + "beta", ExperimentType.REGULAR, apiKey, workspaceName);
        var exp3 = createNamedExperiment(dataset2, namePrefix + "gamma", ExperimentType.TRIAL, apiKey, workspaceName);

        var feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(exp1.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp2.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp3.id(), dataset2.id(), project2.name(), feedbackScores, apiKey, workspaceName);

        var testData = new GroupCriteriaTestData(
                project1.id(),
                namePrefix,
                List.of(
                        GroupBy.builder().field(GroupingFactory.DATASET_ID).type(FieldType.STRING).build(),
                        GroupBy.builder().field(GroupingFactory.PROJECT_ID).type(FieldType.STRING).build()));

        var criteria = criteriaBuilder.apply(testData);

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertThat(beforeAggregation).isNotNull();

        // Populate experiment_aggregates for all experiments
        List.of(exp1, exp2, exp3)
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = experimentService.findGroups(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPagesMatchForFindGroups(beforeAggregation, afterAggregation,
                "FIND_GROUPS must return identical results before and after populating experiment_aggregates for scenario: %s"
                        .formatted(scenarioName));
    }

    @ParameterizedTest(name = "Criteria filter: {0}")
    @MethodSource("groupingCriteriaFilterTestCases")
    @DisplayName("ExperimentDAO.FIND_GROUPS_AGGREGATIONS returns consistent results before and after populating experiment_aggregates for all criteria filter types (UNION ALL hybrid)")
    void experimentFindGroupsAggregationsIsConsistentForAllFiltersBeforeAndAfterAggregates(
            String scenarioName,
            Function<GroupCriteriaTestData, ExperimentGroupCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var namePrefix = "find-groups-agg-filter-test-" + UUID.randomUUID() + "-";
        var project1 = createProject(apiKey, workspaceName);
        var project2 = createProject(apiKey, workspaceName);
        var dataset1 = createDataset(apiKey, workspaceName);
        var dataset2 = createDataset(apiKey, workspaceName);

        var exp1 = createNamedExperiment(dataset1, namePrefix + "alpha", ExperimentType.REGULAR, apiKey, workspaceName);
        var exp2 = createNamedExperiment(dataset1, namePrefix + "beta", ExperimentType.REGULAR, apiKey, workspaceName);
        var exp3 = createNamedExperiment(dataset2, namePrefix + "gamma", ExperimentType.TRIAL, apiKey, workspaceName);

        var feedbackScores = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(exp1.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp2.id(), dataset1.id(), project1.name(), feedbackScores, apiKey, workspaceName);
        createExperimentItemWithData(exp3.id(), dataset2.id(), project2.name(), feedbackScores, apiKey, workspaceName);

        var testData = new GroupCriteriaTestData(
                project1.id(),
                namePrefix,
                List.of(
                        GroupBy.builder().field(GroupingFactory.DATASET_ID).type(FieldType.STRING).build(),
                        GroupBy.builder().field(GroupingFactory.PROJECT_ID).type(FieldType.STRING).build()));

        var criteria = criteriaBuilder.apply(testData);

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertThat(beforeAggregation).isNotNull();

        // Populate experiment_aggregates for all experiments
        List.of(exp1, exp2, exp3)
                .forEach(experiment -> experimentAggregatesService.populateAggregations(experiment.id())
                        .contextWrite(ctx -> ctx
                                .put(RequestContext.USER_NAME, USER)
                                .put(RequestContext.WORKSPACE_ID, workspaceId))
                        .block());

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = experimentService.findGroupsAggregations(criteria)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block();

        assertPagesMatchForFindGroupsAggregations(beforeAggregation, afterAggregation,
                "FIND_GROUPS_AGGREGATIONS must return identical results before and after populating experiment_aggregates for scenario: %s"
                        .formatted(scenarioName));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("datasetItemCountFilterScenarios")
    @DisplayName("ExperimentItemDAO.STREAM returns consistent results before and after populating experiment_item_aggregates for all filter types (UNION ALL hybrid)")
    void streamExperimentItemsIsConsistentForAllFiltersBeforeAndAfterAggregates(
            String scenarioName,
            Function<DatasetItemCountTestData, DatasetItemSearchCriteria> criteriaBuilder) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment1 = createExperiment(dataset, apiKey, workspaceName);
        var experiment2 = createExperiment(dataset, apiKey, workspaceName);

        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);
        createExperimentItemWithData(experiment1.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);
        createExperimentItemWithData(experiment2.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var experimentIds = Set.of(experiment1.id(), experiment2.id());
        var testData = new DatasetItemCountTestData(dataset.id(), experimentIds, feedbackScoreNames);
        var criteria = criteriaBuilder.apply(testData);

        // Query BEFORE populating aggregates — Branch 2: on-the-fly JOINs
        var beforeAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                criteria.search(),
                criteria.filters(),
                apiKey,
                workspaceName);

        assertThat(beforeAggregation).isNotNull();

        // Populate experiment_item_aggregates for all experiments
        List.of(experiment1.id(), experiment2.id()).forEach(id -> experimentAggregatesService.populateAggregations(id)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block());

        // Query AFTER populating aggregates — Branch 1: pre-computed data
        var afterAggregation = datasetResourceClient.getDatasetItemsWithExperimentItems(
                criteria.datasetId(),
                List.copyOf(criteria.experimentIds()),
                criteria.search(),
                criteria.filters(),
                apiKey,
                workspaceName);

        assertThat(afterAggregation).isNotNull();

        assertDatasetItemsWithExperimentItems(beforeAggregation.content(), afterAggregation.content());
    }

    @ParameterizedTest(name = "Sort by {0} {1}")
    @MethodSource("sortingAndPaginationTestCases")
    @DisplayName("Sorting and pagination with push-top-limit: results before and after aggregates match")
    void sortingAndPaginationIsConsistentBeforeAndAfterAggregates(
            String fieldName, com.comet.opik.api.sorting.Direction direction) {

        var workspaceName = UUID.randomUUID().toString();
        var apiKey = UUID.randomUUID().toString();
        var workspaceId = UUID.randomUUID().toString();

        mockTargetWorkspace(apiKey, workspaceName, workspaceId);

        var project = createProject(apiKey, workspaceName);
        var dataset = createDataset(apiKey, workspaceName);
        var experiment1 = createExperiment(dataset, apiKey, workspaceName);
        var experiment2 = createExperiment(dataset, apiKey, workspaceName);

        List<String> feedbackScoreNames = PodamFactoryUtils.manufacturePojoList(factory, String.class);

        // Create enough dataset items to test pagination (2 calls × default PODAM list size)
        createExperimentItemWithData(experiment1.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);
        createExperimentItemWithData(experiment2.id(), dataset.id(), project.name(), feedbackScoreNames, apiKey,
                workspaceName);

        var experimentIds = Set.of(experiment1.id(), experiment2.id());
        // Replace wildcard placeholder with actual key from test data
        var resolvedFieldName = fieldName.replace("feedback_scores.*",
                "feedback_scores." + feedbackScoreNames.getFirst());
        var sorting = List.of(new com.comet.opik.api.sorting.SortingField(resolvedFieldName, direction));
        int pageSize = 2;

        // Query BEFORE populating aggregates (raw branch only)
        var beforePage1 = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), List.copyOf(experimentIds), null, null, sorting, 1, pageSize, apiKey, workspaceName);
        var beforePage2 = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), List.copyOf(experimentIds), null, null, sorting, 2, pageSize, apiKey, workspaceName);

        assertThat(beforePage1).isNotNull();
        assertThat(beforePage1.content()).hasSize(pageSize);
        assertThat(beforePage2).isNotNull();

        // Populate aggregates for all experiments
        List.of(experiment1.id(), experiment2.id()).forEach(id -> experimentAggregatesService.populateAggregations(id)
                .contextWrite(ctx -> ctx
                        .put(RequestContext.USER_NAME, USER)
                        .put(RequestContext.WORKSPACE_ID, workspaceId))
                .block());

        // Query AFTER populating aggregates (aggregated branch — push-top-limit may activate)
        var afterPage1 = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), List.copyOf(experimentIds), null, null, sorting, 1, pageSize, apiKey, workspaceName);
        var afterPage2 = datasetResourceClient.getDatasetItemsWithExperimentItems(
                dataset.id(), List.copyOf(experimentIds), null, null, sorting, 2, pageSize, apiKey, workspaceName);

        assertThat(afterPage1).isNotNull();
        assertThat(afterPage1.content()).hasSize(pageSize);
        assertThat(afterPage2).isNotNull();

        // Verify total counts match
        assertThat(afterPage1.total())
                .as("Total count should match before/after aggregation for sort by %s %s", resolvedFieldName, direction)
                .isEqualTo(beforePage1.total());

        // Verify page 1 items match exactly (same order) before and after aggregation
        var beforePage1Ids = beforePage1.content().stream().map(DatasetItem::id).toList();
        var afterPage1Ids = afterPage1.content().stream().map(DatasetItem::id).toList();
        assertThat(afterPage1Ids)
                .as("Page 1 item IDs should match exactly before/after aggregation for sort by %s %s",
                        resolvedFieldName, direction)
                .containsExactlyElementsOf(beforePage1Ids);

        // Verify page 2 items match exactly (same order) before and after aggregation
        var beforePage2Ids = beforePage2.content().stream().map(DatasetItem::id).toList();
        var afterPage2Ids = afterPage2.content().stream().map(DatasetItem::id).toList();
        assertThat(afterPage2Ids)
                .as("Page 2 item IDs should match exactly before/after aggregation for sort by %s %s",
                        resolvedFieldName, direction)
                .containsExactlyElementsOf(beforePage2Ids);

        // Verify no overlap between pages
        assertThat(afterPage1Ids)
                .as("Page 1 and page 2 should not overlap for sort by %s %s", resolvedFieldName, direction)
                .doesNotContainAnyElementsOf(afterPage2Ids);
    }

    static Stream<Arguments> sortingAndPaginationTestCases() {
        return Stream.of(
                // Static dataset item fields
                Arguments.of("id", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("id", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("description", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("description", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("tags", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("tags", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("created_at", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("created_at", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("last_updated_at", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("last_updated_at", com.comet.opik.api.sorting.Direction.DESC),
                // Aggregated experiment item fields
                Arguments.of("duration", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("duration", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("total_estimated_cost", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("total_estimated_cost", com.comet.opik.api.sorting.Direction.DESC),
                // Wildcard fields (feedback_scores.* resolved at runtime from test data)
                Arguments.of("feedback_scores.*", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("feedback_scores.*", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("usage.completion_tokens", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("usage.completion_tokens", com.comet.opik.api.sorting.Direction.DESC),
                Arguments.of("output.result", com.comet.opik.api.sorting.Direction.ASC),
                Arguments.of("output.result", com.comet.opik.api.sorting.Direction.DESC));
    }

}
